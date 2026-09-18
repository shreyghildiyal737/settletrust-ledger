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
import com.settletrust.ledger.invoice.InvoiceTransitions;
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

    /**
     * The money moved, and the step the invoice took because of it.
     *
     * <p>{@code transition} is null in exactly one case: a reversal of a deposit for an
     * invoice that is already final. The lifecycle cannot move a settled invoice and
     * should not, because it really was paid; the money still has to be put back, and the
     * correction lives in the ledger and the shortfall account instead. A book that
     * refuses to record a loss because the paperwork has been filed is not a book.
     */
    public record Settlement(InvoiceTransition transition, Transfer transfer) {
    }

    public static AccountId escrowAccountFor(String invoiceId) {
        return AccountId.of("escrow:" + invoiceId);
    }

    /**
     * The platform's own side of the chain rail. Money arriving from a blockchain has to
     * come from somewhere in a double-entry system, and this is that somewhere: its
     * negative balance is exactly the total the platform is holding on chain.
     */
    public static AccountId chainAccountFor(String currency) {
        return AccountId.of("chain:" + currency);
    }

    /**
     * Where a reversal is absorbed when the escrow has already been paid out.
     *
     * <p>Its balance is the platform's exposure to reorganisations it settled too early,
     * which is a number somebody should be watching rather than one that should be
     * impossible to compute.
     */
    public static AccountId chainShortfallAccountFor(String currency) {
        return AccountId.of("chain-shortfall:" + currency);
    }

    /**
     * Credits a confirmed on-chain deposit into the invoice's escrow and marks the invoice
     * funded, inside a transaction the watcher owns.
     *
     * <p>The transaction hash and log index are the idempotency key, which is what makes
     * this safe to call with an event the chain has handed over more than once.
     */
    public Settlement fundEscrowFromChain(
            org.jooq.Configuration config,
            String invoiceId,
            String txHash,
            int logIndex,
            Money amount) {

        String key = "chain-deposit:" + txHash + ":" + logIndex;
        Optional<Settlement> alreadyDone =
                alreadyDone(config, invoiceId, key, InvoiceStatus.ESCROW_FUNDED);
        if (alreadyDone.isPresent()) {
            return alreadyDone.get();
        }

        AccountId escrow = escrowAccountFor(invoiceId);
        AccountId chain = chainAccountFor(amount.currency());
        openIfMissing(config, escrow, amount.currency(), Account.Kind.CUSTOMER);
        openIfMissing(config, chain, amount.currency(), Account.Kind.HOUSE);

        InvoiceTransition transition = invoices.transitionWithin(
                config, invoiceId, InvoiceStatus.ESCROW_FUNDED, null, "on-chain deposit " + txHash);

        Transfer transfer = transfers.transferWithin(config, chain, escrow, amount, key);
        return new Settlement(transition, transfer);
    }

    /**
     * Undoes a deposit the chain has taken back, and freezes the invoice.
     *
     * <p>Nothing is deleted: the reversal is a new pair of entries in the opposite
     * direction, so the books show both that the money arrived and that it went away
     * again. The invoice is frozen rather than sent backwards, because there is no
     * automatic next step here that is safe; a person has to look at an invoice whose
     * funding evaporated.
     *
     * <p>If the escrow has already been released to the seller, the money cannot be taken
     * back from them and the reversal is absorbed by the shortfall account instead. That
     * is the platform's loss, and recording it as one is the only version of these books
     * that stays true.
     */
    public Settlement reverseChainDeposit(
            org.jooq.Configuration config,
            String invoiceId,
            String txHash,
            int logIndex,
            Money amount) {

        String key = "chain-reversal:" + txHash + ":" + logIndex;
        AccountId escrow = escrowAccountFor(invoiceId);
        AccountId chain = chainAccountFor(amount.currency());

        Money held = ledger.balanceOfWithin(config, escrow);
        AccountId payer = held.isLessThan(amount)
                ? chainShortfallAccountFor(amount.currency())
                : escrow;
        openIfMissing(config, payer, amount.currency(), Account.Kind.HOUSE);

        // Freeze the invoice so a person looks at it, unless it is already final. A
        // settled invoice stays settled: the seller was genuinely paid, and pretending
        // otherwise would make the lifecycle lie to cover for the chain.
        InvoiceStatus current = requireInvoice(config, invoiceId).status();
        InvoiceTransition transition = InvoiceTransitions.isAllowed(current, InvoiceStatus.FROZEN)
                ? invoices.transitionWithin(
                        config, invoiceId, InvoiceStatus.FROZEN,
                        null, "on-chain deposit reversed " + txHash)
                : null;

        Transfer transfer = transfers.transferWithin(config, payer, chain, amount, key);
        return new Settlement(transition, transfer);
    }

    private void openIfMissing(
            org.jooq.Configuration config, AccountId id, String currency, Account.Kind kind) {
        if (!ledger.exists(config, id)) {
            ledger.openWithin(config, new Account(id, currency, kind));
        }
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

            // A customer account rather than a house one: escrow holds real money that
            // arrived from somewhere, so it must never be able to go negative.
            openIfMissing(config, escrow, amount.currency(), Account.Kind.CUSTOMER);

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
