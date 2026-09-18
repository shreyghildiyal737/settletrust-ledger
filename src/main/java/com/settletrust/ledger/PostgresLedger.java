package com.settletrust.ledger;

import org.jooq.Configuration;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.settletrust.ledger.jooq.Tables.ACCOUNT;
import static com.settletrust.ledger.jooq.Tables.ENTRY;

/**
 * The ledger, kept in Postgres. Reads here are outside any transaction; the ones that
 * have to be consistent with a write are done by {@link PostgresTransferService} inside
 * its own transaction, using the package-private overloads below.
 *
 * <p>Timestamps are stored as UTC {@code timestamp} rather than {@code timestamptz}:
 * the ledger has one clock, UTC, and a column that silently reinterprets itself by the
 * session's timezone is a reconciliation bug waiting to happen.
 */
public class PostgresLedger {

    private final DSLContext dsl;
    private final Clock clock;

    public PostgresLedger(Database database) {
        this(database.dsl(), Clock.systemUTC());
    }

    public PostgresLedger(DSLContext dsl, Clock clock) {
        this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public void open(Account account) {
        int inserted = dsl.insertInto(ACCOUNT)
                .set(ACCOUNT.ID, account.id().value())
                .set(ACCOUNT.CURRENCY, account.currency())
                .set(ACCOUNT.KIND, account.kind().name())
                .set(ACCOUNT.OPENED_AT, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .onConflictDoNothing()
                .execute();

        if (inserted == 0) {
            throw new IllegalStateException("account already open: " + account.id());
        }
    }

    public Optional<Account> find(AccountId id) {
        return find(dsl, id);
    }

    public Account require(AccountId id) {
        return require(dsl, id);
    }

    public Money balanceOf(AccountId id) {
        return balanceOf(dsl, require(id));
    }

    public List<Entry> entriesOf(AccountId id) {
        return dsl.selectFrom(ENTRY)
                .where(ENTRY.ACCOUNT_ID.eq(id.value()))
                .orderBy(ENTRY.RECORDED_AT.asc(), ENTRY.ID.asc())
                .fetch()
                .map(PostgresLedger::toEntry);
    }

    /** Every entry in a currency must sum to zero, or the books do not balance. */
    public long sumOfAllEntries(String currency) {
        return dsl.select(DSL.coalesce(DSL.sum(ENTRY.AMOUNT_MINOR), BigDecimal.ZERO))
                .from(ENTRY)
                .where(ENTRY.CURRENCY.eq(currency))
                .fetchOne(0, BigDecimal.class)
                .longValueExact();
    }

    static Optional<Account> find(DSLContext context, AccountId id) {
        return context.selectFrom(ACCOUNT)
                .where(ACCOUNT.ID.eq(id.value()))
                .fetchOptional()
                .map(record -> new Account(
                        AccountId.of(record.getId()),
                        record.getCurrency(),
                        Account.Kind.valueOf(record.getKind())));
    }

    static Account require(DSLContext context, AccountId id) {
        return find(context, id).orElseThrow(() -> new TransferRejected(
                TransferRejected.Reason.UNKNOWN_ACCOUNT, id.value()));
    }

    /**
     * Takes a row lock on the account before reading it, so that two transfers spending
     * from the same account are serialised by Postgres rather than racing. Only the
     * source is locked, and only one row per transaction, so there is no lock ordering
     * between transfers and therefore no deadlock to order around.
     */
    static Account lockForSpending(Configuration config, AccountId id) {
        Record row = DSL.using(config)
                .selectFrom(ACCOUNT)
                .where(ACCOUNT.ID.eq(id.value()))
                .forUpdate()
                .fetchOne();

        if (row == null) {
            throw new TransferRejected(TransferRejected.Reason.UNKNOWN_ACCOUNT, id.value());
        }
        return new Account(
                AccountId.of(row.get(ACCOUNT.ID)),
                row.get(ACCOUNT.CURRENCY),
                Account.Kind.valueOf(row.get(ACCOUNT.KIND)));
    }

    static Money balanceOf(DSLContext context, Account account) {
        BigDecimal total = context
                .select(DSL.coalesce(DSL.sum(ENTRY.AMOUNT_MINOR), BigDecimal.ZERO))
                .from(ENTRY)
                .where(ENTRY.ACCOUNT_ID.eq(account.id().value()))
                .fetchOne(0, BigDecimal.class);
        return Money.of(total.longValueExact(), account.currency());
    }

    private static Entry toEntry(org.jooq.Record record) {
        return new Entry(
                record.get(ENTRY.ID),
                record.get(ENTRY.TRANSFER_ID),
                AccountId.of(record.get(ENTRY.ACCOUNT_ID)),
                Money.of(record.get(ENTRY.AMOUNT_MINOR), record.get(ENTRY.CURRENCY)),
                record.get(ENTRY.RECORDED_AT).toInstant(ZoneOffset.UTC));
    }
}
