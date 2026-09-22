package com.settletrust.ledger.settlement;

import com.settletrust.ledger.Account;
import com.settletrust.ledger.AccountId;
import com.settletrust.ledger.InternalAccounts;
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

    /**
     * Account naming, exposed because reconciliation has to recognise these accounts from
     * their ids alone. An account id is the only thing a {@code chain:EURC} row carries to
     * say what it is for, so the prefix is part of the schema, not a formatting detail.
     */
    public static final String ESCROW_ACCOUNT_PREFIX = InternalAccounts.ESCROW_PREFIX;
    public static final String CHAIN_ACCOUNT_PREFIX = InternalAccounts.CHAIN_PREFIX;
    public static final String CHAIN_SHORTFALL_ACCOUNT_PREFIX =
            InternalAccounts.CHAIN_SHORTFALL_PREFIX;

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
        return AccountId.of(ESCROW_ACCOUNT_PREFIX + invoiceId);
    }

    /**
     * The platform's own side of the chain rail. Money arriving from a blockchain has to
     * come from somewhere in a double-entry system, and this is that somewhere: its
     * negative balance is exactly the total the platform is holding on chain.
     */
    public static AccountId chainAccountFor(String currency) {
        return AccountId.of(CHAIN_ACCOUNT_PREFIX + currency);
    }

    /**
     * Where a reversal is absorbed when the escrow has already been paid out.
     *
     * <p>Its balance is the platform's exposure to reorganisations it settled too early,
     * which is a number somebody should be watching rather than one that should be
     * impossible to compute.
     */
    public static AccountId chainShortfallAccountFor(String currency) {
        return AccountId.of(CHAIN_SHORTFALL_ACCOUNT_PREFIX + currency);
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

        String key = SettlementKeys.chainDeposit(txHash, logIndex);
        Optional<Settlement> alreadyDone =
                alreadyCredited(config, invoiceId, key, InvoiceStatus.ESCROW_FUNDED);
        if (alreadyDone.isPresent()) {
            return alreadyDone.get();
        }

        // What the contract accepted is not what this invoice is owed. The contract takes
        // an amount and a bytes32 from anyone and checks neither against an invoice it has
        // never heard of, so the comparison has to happen here or nowhere.
        Money owed = requireInvoice(config, invoiceId).invoice().amount();
        if (!amount.currency().equals(owed.currency())) {
            throw new ChainDepositMismatch(
                    "the deposit is " + amount + " and invoice " + invoiceId + " is in "
                            + owed.currency() + ", which its escrow cannot hold");
        }

        AccountId escrow = escrowAccountFor(invoiceId);
        AccountId chain = chainAccountFor(amount.currency());
        openIfMissing(config, escrow, amount.currency(), Account.Kind.CUSTOMER);
        openIfMissing(config, chain, amount.currency(), Account.Kind.HOUSE);

        // The money is credited either way: it arrived, the platform controls it, and a
        // ledger that omitted it would be understating what is being held. What a short or
        // over-sized deposit does not do is fund the invoice, because escrow_funded is a
        // claim that the whole amount is there and the seller ships on the strength of it.
        //
        // The contract allows one deposit per invoice id, so this cannot be topped up and
        // somebody has to decide whether to refund it or invoice the difference. Leaving
        // the invoice in escrow_pending is what puts that decision in front of them; the
        // reconciler raises it as ESCROW_UNDERFUNDED rather than waiting to be asked.
        boolean coversTheInvoice = amount.equals(owed);
        InvoiceTransition transition = coversTheInvoice
                ? invoices.transitionWithin(
                        config, invoiceId, InvoiceStatus.ESCROW_FUNDED,
                        null, "on-chain deposit " + txHash)
                : null;

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

        String key = SettlementKeys.chainReversal(txHash, logIndex);
        AccountId escrow = escrowAccountFor(invoiceId);
        AccountId chain = chainAccountFor(amount.currency());

        // Normally the escrow still holds the money and gives it back. If it does not, the
        // payout already happened and the shortfall account absorbs it, which is the one
        // account allowed to go negative for this: the platform is out of pocket until
        // somebody sorts it out, and that is exactly what a negative house balance says.
        Money held = ledger.balanceOfWithin(config, escrow);
        boolean alreadyPaidOut = held.isLessThan(amount);
        AccountId payer = alreadyPaidOut ? chainShortfallAccountFor(amount.currency()) : escrow;

        // The kind has to follow the account, not the branch. An escrow account is a
        // CUSTOMER account precisely so it cannot go negative, and asking for it as HOUSE
        // here was harmless only because it always already exists by the time a deposit is
        // being reversed. Naming the wrong kind for an account that is about to be created
        // would have opened escrow that could be overdrawn.
        Account.Kind kind = alreadyPaidOut ? Account.Kind.HOUSE : Account.Kind.CUSTOMER;
        openIfMissing(config, payer, amount.currency(), kind);

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

    /**
     * Opens one of the platform's own accounts, or checks that the one already there is
     * the account it was going to open.
     *
     * <p>The check is not redundant with the reserved-prefix rule at the edge. That rule
     * stops an outside caller claiming the name; this one catches the case where the name
     * was taken by something that got past it, by a migration, by a fixture, or by a hand
     * at a psql prompt. Adopting a stranger's account silently is how escrow ends up being
     * held somewhere nobody chose, and the only clue would be a currency mismatch surfacing
     * several steps later as a rejected transfer.
     */
    private void openIfMissing(
            org.jooq.Configuration config, AccountId id, String currency, Account.Kind kind) {
        Optional<Account> existing = ledger.findWithin(config, id);
        if (existing.isEmpty()) {
            ledger.openWithin(config, new Account(id, currency, kind));
            return;
        }

        Account account = existing.get();
        if (!account.currency().equals(currency) || account.kind() != kind) {
            throw new IllegalStateException(
                    "the platform account " + id + " already exists as " + account.currency()
                            + "/" + account.kind() + ", but is needed as " + currency + "/"
                            + kind);
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

        String key = SettlementKeys.escrowFund(invoiceId);

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

        String key = SettlementKeys.invoiceSettle(invoiceId);

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
     * The chain's version of {@link #alreadyDone}, which tolerates a credit that moved
     * money without advancing the invoice.
     *
     * <p>The stricter method treats a transfer with no matching transition as corruption,
     * and everywhere else it is: the two are written in one transaction precisely so that
     * neither can exist alone. A short on-chain deposit is the one legitimate exception,
     * because it is credited on purpose and deliberately does not fund the invoice. Asking
     * the strict question about it would turn every replay of that deposit into a failed
     * pass.
     */
    private Optional<Settlement> alreadyCredited(
            org.jooq.Configuration config, String invoiceId, String key, InvoiceStatus reached) {

        return transfers.findByKeyWithin(config, key).map(transfer -> new Settlement(
                invoices.findTransitionTo(config, invoiceId, reached).orElse(null),
                transfer));
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
