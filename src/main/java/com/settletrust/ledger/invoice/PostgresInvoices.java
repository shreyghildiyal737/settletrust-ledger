package com.settletrust.ledger.invoice;

import com.settletrust.ledger.Money;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.settletrust.ledger.jooq.Tables.INVOICE;
import static com.settletrust.ledger.jooq.Tables.INVOICE_TRANSITION;

/**
 * Invoices in Postgres, with their status derived from an append-only history rather than
 * stored in a column.
 *
 * <p>The concurrency story is the same shape as the ledger's, and for the same reason. Two
 * clients acting on one invoice both read the latest sequence, both compute the same next
 * number, and the unique constraint on {@code (invoice_id, sequence)} lets exactly one
 * insert through. The loser is told the invoice moved rather than silently overwriting a
 * decision it never saw. No locks, no lost update.
 */
public class PostgresInvoices {

    private static final String UNIQUE_VIOLATION = "23505";

    private final DSLContext dsl;
    private final Clock clock;

    public PostgresInvoices(DSLContext dsl, Clock clock) {
        this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Creates the invoice and its opening transition into {@code draft}, atomically. */
    public InvoiceState open(Invoice invoice) {
        LocalDateTime createdAt = LocalDateTime.ofInstant(invoice.createdAt(), ZoneOffset.UTC);

        dsl.transaction(config -> {
            DSLContext transaction = DSL.using(config);
            transaction.insertInto(INVOICE)
                    .set(INVOICE.ID, invoice.id())
                    .set(INVOICE.REFERENCE, invoice.reference())
                    .set(INVOICE.SELLER_ID, invoice.sellerId())
                    .set(INVOICE.BUYER_ID, invoice.buyerId())
                    .set(INVOICE.AMOUNT_MINOR, invoice.amount().minorUnits())
                    .set(INVOICE.CURRENCY, invoice.amount().currency())
                    .set(INVOICE.CREATED_AT, createdAt)
                    .execute();

            transaction.insertInto(INVOICE_TRANSITION)
                    .set(INVOICE_TRANSITION.ID, UUID.randomUUID())
                    .set(INVOICE_TRANSITION.INVOICE_ID, invoice.id())
                    .set(INVOICE_TRANSITION.SEQUENCE, 0)
                    .set(INVOICE_TRANSITION.FROM_STATUS, (String) null)
                    .set(INVOICE_TRANSITION.TO_STATUS, InvoiceStatus.DRAFT.code())
                    .set(INVOICE_TRANSITION.REASON, "created")
                    .set(INVOICE_TRANSITION.OCCURRED_AT, createdAt)
                    .execute();
        });

        return new InvoiceState(invoice, InvoiceStatus.DRAFT, 0);
    }

    public Optional<InvoiceState> find(String invoiceId) {
        return find(dsl, invoiceId);
    }

    public InvoiceState require(String invoiceId) {
        return find(invoiceId).orElseThrow(() -> new InvoiceTransitionRejected(
                InvoiceTransitionRejected.Reason.UNKNOWN_INVOICE, invoiceId));
    }

    public List<InvoiceTransition> history(String invoiceId) {
        return dsl.selectFrom(INVOICE_TRANSITION)
                .where(INVOICE_TRANSITION.INVOICE_ID.eq(invoiceId))
                .orderBy(INVOICE_TRANSITION.SEQUENCE.asc())
                .fetch()
                .map(PostgresInvoices::toTransition);
    }

    /**
     * Moves the invoice, or refuses.
     *
     * @param expected the status the caller believes the invoice is in, or null to accept
     *                 whatever it is now. Sending it is what turns a blind command into a
     *                 safe one: if the invoice has moved on, the caller is told rather
     *                 than acting on a stale view.
     */
    public InvoiceTransition transition(
            String invoiceId, InvoiceStatus to, InvoiceStatus expected, String reason) {

        Objects.requireNonNull(to, "the target status must not be null");
        InvoiceState current = require(invoiceId);

        if (expected != null && expected != current.status()) {
            throw new InvoiceTransitionRejected(
                    InvoiceTransitionRejected.Reason.STATE_CHANGED,
                    invoiceId + " is " + current.status().code() + ", not " + expected.code());
        }
        InvoiceRules.requireLegal(invoiceId, current.status(), to);

        InvoiceTransition move = new InvoiceTransition(
                UUID.randomUUID(),
                invoiceId,
                current.sequence() + 1,
                current.status(),
                to,
                reason,
                clock.instant());

        try {
            dsl.insertInto(INVOICE_TRANSITION)
                    .set(INVOICE_TRANSITION.ID, move.id())
                    .set(INVOICE_TRANSITION.INVOICE_ID, move.invoiceId())
                    .set(INVOICE_TRANSITION.SEQUENCE, move.sequence())
                    .set(INVOICE_TRANSITION.FROM_STATUS, move.from().code())
                    .set(INVOICE_TRANSITION.TO_STATUS, move.to().code())
                    .set(INVOICE_TRANSITION.REASON, move.reason())
                    .set(INVOICE_TRANSITION.OCCURRED_AT,
                            LocalDateTime.ofInstant(move.occurredAt(), ZoneOffset.UTC))
                    .execute();
        } catch (DataAccessException failure) {
            if (!isUniqueViolation(failure)) {
                throw failure;
            }
            // Someone else took this sequence number. Whatever they did, this caller's
            // view of the invoice was already out of date by the time it tried to write.
            throw new InvoiceTransitionRejected(
                    InvoiceTransitionRejected.Reason.STATE_CHANGED,
                    invoiceId + " moved while this transition was being applied");
        }

        return move;
    }

    private static Optional<InvoiceState> find(DSLContext context, String invoiceId) {
        Record row = context
                .select(INVOICE.ID, INVOICE.REFERENCE, INVOICE.SELLER_ID, INVOICE.BUYER_ID,
                        INVOICE.AMOUNT_MINOR, INVOICE.CURRENCY, INVOICE.CREATED_AT,
                        INVOICE_TRANSITION.TO_STATUS, INVOICE_TRANSITION.SEQUENCE)
                .from(INVOICE)
                .join(INVOICE_TRANSITION)
                .on(INVOICE_TRANSITION.INVOICE_ID.eq(INVOICE.ID))
                .where(INVOICE.ID.eq(invoiceId))
                // The latest transition is the current status. The index on
                // (invoice_id, sequence desc) is here so this stays one row, not a scan.
                .orderBy(INVOICE_TRANSITION.SEQUENCE.desc())
                .limit(1)
                .fetchOne();

        if (row == null) {
            return Optional.empty();
        }

        Invoice invoice = new Invoice(
                row.get(INVOICE.ID),
                row.get(INVOICE.REFERENCE),
                row.get(INVOICE.SELLER_ID),
                row.get(INVOICE.BUYER_ID),
                Money.of(row.get(INVOICE.AMOUNT_MINOR), row.get(INVOICE.CURRENCY)),
                row.get(INVOICE.CREATED_AT).toInstant(ZoneOffset.UTC));

        return Optional.of(new InvoiceState(
                invoice,
                InvoiceStatus.fromCode(row.get(INVOICE_TRANSITION.TO_STATUS)),
                row.get(INVOICE_TRANSITION.SEQUENCE)));
    }

    private static InvoiceTransition toTransition(Record row) {
        String from = row.get(INVOICE_TRANSITION.FROM_STATUS);
        return new InvoiceTransition(
                row.get(INVOICE_TRANSITION.ID),
                row.get(INVOICE_TRANSITION.INVOICE_ID),
                row.get(INVOICE_TRANSITION.SEQUENCE),
                from == null ? null : InvoiceStatus.fromCode(from),
                InvoiceStatus.fromCode(row.get(INVOICE_TRANSITION.TO_STATUS)),
                row.get(INVOICE_TRANSITION.REASON),
                row.get(INVOICE_TRANSITION.OCCURRED_AT).toInstant(ZoneOffset.UTC));
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
