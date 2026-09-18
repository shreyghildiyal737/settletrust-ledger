package com.settletrust.ledger.settlement;

import com.settletrust.ledger.Account;
import com.settletrust.ledger.AccountId;
import com.settletrust.ledger.Money;
import com.settletrust.ledger.PostgresLedger;
import com.settletrust.ledger.PostgresTransferService;
import com.settletrust.ledger.SqlErrors;
import com.settletrust.ledger.Transfer;
import com.settletrust.ledger.invoice.InvoiceState;
import com.settletrust.ledger.invoice.InvoiceStatus;
import com.settletrust.ledger.invoice.InvoiceTransition;
import com.settletrust.ledger.invoice.InvoiceTransitionRejected;
import com.settletrust.ledger.invoice.PostgresInvoices;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;

import java.util.Objects;
import java.util.Optional;

/**
 * Where the two halves meet.
 *
 * <p>An invoice reaching {@code escrow_funded} or {@code settled} is a claim about money.
 * This class is what makes the claim true: the transition and the transfer are written in
 * one database transaction, so an invoice can never be marked funded or paid without the
 * entries that prove it, and money can never move without the invoice recording why.
 * Either both land or neither does.
 *
 * <p>Each invoice gets its own escrow account, created the first time it is funded. That
 * is not decoration: escrow is the reason the buyer's money leaves their control before
 * the seller has earned it, and holding it in a named account per invoice means the
 * question "whose money is this, and against what" always has an answer.
 */
public class InvoiceSettlement {

    private final DSLContext dsl;
    private final PostgresLedger ledger;
    private final PostgresInvoices invoices;
    private final PostgresTransferService transfers;

    public InvoiceSettlement(
            DSLContext dsl,
            PostgresLedger ledger,
            PostgresInvoices invoices,
            PostgresTransferService transfers) {
        this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
        this.invoices = Objects.requireNonNull(invoices, "invoices must not be null");
        this.transfers = Objects.requireNonNull(transfers, "transfers must not be null");
    }

    /** The money moved, and the step the invoice took because of it. */
    public record Settlement(InvoiceTransition transition, Transfer transfer) {
    }

    public static AccountId escrowAccountFor(String invoiceId) {
        return AccountId.of("escrow:" + invoiceId);
    }

    /**
     * Moves the invoice amount from the buyer into the invoice's escrow account and marks
     * the invoice funded.
     *
     * <p>If the buyer cannot cover it the transfer is refused, and because the transition
     * is in the same transaction, the invoice is not left claiming an escrow that was
     * never funded.
     */
    public Settlement fundEscrow(String invoiceId, AccountId buyerAccount) {
        Objects.requireNonNull(buyerAccount, "buyerAccount must not be null");

        String key = "escrow-fund:" + invoiceId;

        return run(invoiceId, config -> {
            InvoiceState state = requireInvoice(config, invoiceId);
            Optional<Settlement> alreadyDone =
                    alreadyDone(config, invoiceId, key, InvoiceStatus.ESCROW_FUNDED);
            if (alreadyDone.isPresent()) {
                return alreadyDone.get();
            }

            Money amount = state.invoice().amount();
            AccountId escrow = escrowAccountFor(invoiceId);

            if (!ledger.exists(config, escrow)) {
                // A customer account rather than a house one: escrow holds real money that
                // arrived from somewhere, so it must never be able to go negative.
                ledger.openWithin(config, new Account(
                        escrow, amount.currency(), Account.Kind.CUSTOMER));
            }

            InvoiceTransition transition = invoices.transitionWithin(
                    config, invoiceId, InvoiceStatus.ESCROW_FUNDED, null, "escrow funded");

            Transfer transfer = transfers.transferWithin(
                    config, buyerAccount, escrow, amount, key);

            return new Settlement(transition, transfer);
        });
    }

    /**
     * Releases the escrow to the seller and marks the invoice settled.
     *
     * <p>Only legal from {@code settlement_pending}, which the state machine enforces, so
     * an invoice cannot be paid before delivery has been confirmed.
     */
    public Settlement settle(String invoiceId, AccountId sellerAccount) {
        Objects.requireNonNull(sellerAccount, "sellerAccount must not be null");

        String key = "invoice-settle:" + invoiceId;

        return run(invoiceId, config -> {
            InvoiceState state = requireInvoice(config, invoiceId);
            Optional<Settlement> alreadyDone =
                    alreadyDone(config, invoiceId, key, InvoiceStatus.SETTLED);
            if (alreadyDone.isPresent()) {
                return alreadyDone.get();
            }

            InvoiceTransition transition = invoices.transitionWithin(
                    config, invoiceId, InvoiceStatus.SETTLED, null, "escrow released to seller");

            Transfer transfer = transfers.transferWithin(
                    config,
                    escrowAccountFor(invoiceId),
                    sellerAccount,
                    state.invoice().amount(),
                    key);

            return new Settlement(transition, transfer);
        });
    }

    /**
     * The settlement this call is asking for, if it has already happened.
     *
     * <p>A client whose connection dropped mid-settlement must be able to ask again and
     * get the same answer rather than an error it has to interpret. The transfer's
     * idempotency key is the evidence: if money moved under it, the work is done, and the
     * transition it produced is on record next to it. Without this, the only safe thing a
     * retrying client could do is nothing.
     */
    private Optional<Settlement> alreadyDone(
            org.jooq.Configuration config, String invoiceId, String key, InvoiceStatus reached) {

        return transfers.findByKeyWithin(config, key).map(transfer -> {
            InvoiceTransition transition = invoices
                    .findTransitionTo(config, invoiceId, reached)
                    .orElseThrow(() -> new IllegalStateException(
                            "money moved under " + key + " but " + invoiceId
                                    + " never recorded reaching " + reached.code()));
            return new Settlement(transition, transfer);
        });
    }

    private InvoiceState requireInvoice(org.jooq.Configuration config, String invoiceId) {
        return invoices.findWithin(config, invoiceId)
                .orElseThrow(() -> new InvoiceTransitionRejected(
                        InvoiceTransitionRejected.Reason.UNKNOWN_INVOICE, invoiceId));
    }

    /**
     * Runs the work in one transaction and turns a lost race into a refusal.
     *
     * <p>Both the invoice's sequence number and the transfer's idempotency key are unique,
     * so a second attempt at the same settlement collides on one of them. Since Postgres
     * has already aborted the transaction by then, the recovery has to happen out here,
     * after it has ended.
     */
    private Settlement run(String invoiceId, TransactionalWork work) {
        try {
            return dsl.transactionResult(work::apply);
        } catch (DataAccessException failure) {
            if (!SqlErrors.isUniqueViolation(failure)) {
                throw failure;
            }
            throw new InvoiceTransitionRejected(
                    InvoiceTransitionRejected.Reason.STATE_CHANGED,
                    invoiceId + " was already being settled by someone else");
        }
    }

    @FunctionalInterface
    private interface TransactionalWork {
        Settlement apply(org.jooq.Configuration config);
    }
}
