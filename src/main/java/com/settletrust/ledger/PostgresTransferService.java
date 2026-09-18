package com.settletrust.ledger;

import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.settletrust.ledger.jooq.Tables.ENTRY;
import static com.settletrust.ledger.jooq.Tables.TRANSFER;

/**
 * Moves money in Postgres. Same rules as {@link TransferService}, different atomicity:
 * a database transaction instead of a lock.
 *
 * <p>Idempotency is enforced by the unique constraint on {@code transfer.idempotency_key}
 * rather than by checking first and trusting the gap. Checking first is still done,
 * because it is the common case and it is cheap, but the constraint is what makes the
 * guarantee true: two concurrent retries of one payment can both find no row and both
 * try to insert, and exactly one of them will succeed. The loser reads the winner's row
 * and returns it, so both callers get the same answer and money moved once.
 */
public class PostgresTransferService implements Transfers {

    private static final String UNIQUE_VIOLATION = "23505";

    private final DSLContext dsl;
    private final Clock clock;

    public PostgresTransferService(Database database) {
        this(database.dsl(), Clock.systemUTC());
    }

    public PostgresTransferService(DSLContext dsl, Clock clock) {
        this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Transfer transfer(AccountId from, AccountId to, Money amount, String idempotencyKey) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        TransferRules.requireKey(idempotencyKey);

        try {
            return dsl.transactionResult(config -> {
                DSLContext transaction = DSL.using(config);

                Optional<Transfer> alreadyDone = findByKey(transaction, idempotencyKey);
                if (alreadyDone.isPresent()) {
                    return alreadyDone.get().asReplay();
                }

                // Lock the paying account before its balance is read, so a concurrent
                // transfer cannot spend the same money between the read and the write.
                Account source = PostgresLedger.lockForSpending(config, from);
                Account target = PostgresLedger.require(transaction, to);
                Money available = PostgresLedger.balanceOf(transaction, source);
                TransferRules.validate(source, target, amount, available);

                return post(transaction, source, target, amount, idempotencyKey);
            });
        } catch (DataAccessException failure) {
            if (!isUniqueViolation(failure)) {
                throw failure;
            }
            // Lost the race on the key. The winner's transfer is the answer, and no
            // second movement happened: this transaction rolled back in full.
            return findByKey(dsl, idempotencyKey)
                    .orElseThrow(() -> failure)
                    .asReplay();
        }
    }

    private Transfer post(
            DSLContext transaction,
            Account source,
            Account target,
            Money amount,
            String idempotencyKey) {

        UUID transferId = UUID.randomUUID();
        Instant at = clock.instant();
        LocalDateTime timestamp = LocalDateTime.ofInstant(at, ZoneOffset.UTC);

        transaction.insertInto(TRANSFER)
                .set(TRANSFER.ID, transferId)
                .set(TRANSFER.IDEMPOTENCY_KEY, idempotencyKey)
                .set(TRANSFER.FROM_ACCOUNT, source.id().value())
                .set(TRANSFER.TO_ACCOUNT, target.id().value())
                .set(TRANSFER.AMOUNT_MINOR, amount.minorUnits())
                .set(TRANSFER.CURRENCY, amount.currency())
                .set(TRANSFER.COMPLETED_AT, timestamp)
                .execute();

        // Both entries and the transfer row are one statement batch inside one
        // transaction: either the pair and its key are all durable, or none of them are.
        List<Entry> pair = TransferRules.entryPair(transferId, source, target, amount, at);
        var insert = transaction.insertInto(ENTRY,
                ENTRY.ID, ENTRY.TRANSFER_ID, ENTRY.ACCOUNT_ID,
                ENTRY.AMOUNT_MINOR, ENTRY.CURRENCY, ENTRY.RECORDED_AT);
        for (Entry entry : pair) {
            insert = insert.values(
                    entry.entryId(),
                    entry.transferId(),
                    entry.accountId().value(),
                    entry.amount().minorUnits(),
                    entry.amount().currency(),
                    timestamp);
        }
        insert.execute();

        return new Transfer(
                transferId, idempotencyKey, source.id(), target.id(), amount, at, false);
    }

    private static Optional<Transfer> findByKey(DSLContext context, String idempotencyKey) {
        return context.selectFrom(TRANSFER)
                .where(TRANSFER.IDEMPOTENCY_KEY.eq(idempotencyKey))
                .fetchOptional()
                .map(record -> new Transfer(
                        record.getId(),
                        record.getIdempotencyKey(),
                        AccountId.of(record.getFromAccount()),
                        AccountId.of(record.getToAccount()),
                        Money.of(record.getAmountMinor(), record.getCurrency()),
                        record.getCompletedAt().toInstant(ZoneOffset.UTC),
                        false));
    }

    private static boolean isUniqueViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
