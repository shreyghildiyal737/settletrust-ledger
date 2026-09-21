package com.settletrust.ledger.reconciliation;

/**
 * The ways the books and the chain can fail to agree.
 *
 * <p>Each one names a specific untrue thing rather than a severity. "Warning" tells an
 * operator nothing they can act on; {@link #CONFIRMED_DEPOSIT_NOT_CREDITED} tells them
 * which deposit to go and look at.
 */
public enum DiscrepancyKind {

    /** Every entry in a currency must net to zero. If it does not, money was invented. */
    ENTRIES_DO_NOT_SUM_TO_ZERO,

    /** One transfer's entries are not a pair summing to zero, so that transfer is malformed. */
    TRANSFER_NOT_BALANCED,

    /** The chain rail says a deposit was confirmed, and no transfer moved that money. */
    CONFIRMED_DEPOSIT_NOT_CREDITED,

    /** A deposit was credited, for a different amount or a different currency than the chain reported. */
    CREDIT_AMOUNT_DISAGREES,

    /** A deposit was credited into some account other than its own invoice's escrow. */
    CREDIT_MISDIRECTED,

    /** A deposit is marked reversed, and the reversing transfer that should have undone it is missing. */
    REVERSAL_NOT_RECORDED,

    /**
     * Money moved under a chain deposit key with no confirmed deposit behind it.
     *
     * <p>The mirror image of {@link #CONFIRMED_DEPOSIT_NOT_CREDITED}, and the more
     * alarming of the two: the first is money owed to somebody, this is money credited
     * that the chain never sent.
     */
    CREDIT_WITHOUT_CONFIRMATION,

    /**
     * A chain house account's balance is not the negative of what the chain says it sent.
     *
     * <p>The aggregate version of the per-deposit checks, computed from the opposite end.
     * It can catch an imbalance the row-by-row checks miss, because it counts entries
     * they never look at: anything that touched {@code chain:<currency>} without going
     * through the watcher shows up here and nowhere else.
     */
    CHAIN_ACCOUNT_DISAGREES,

    /** A customer account is negative, which the transfer rules are supposed to make impossible. */
    CUSTOMER_ACCOUNT_OVERDRAWN,

    /**
     * The escrow contract holds less than the ledger has already credited from it.
     *
     * <p>The only finding here that the chain, rather than our own record of the chain,
     * is the authority for. Every other check would still pass if the watcher had
     * misread the chain, because it wrote both sides of what they compare. This one
     * cannot: the money is either in the contract or it is not.
     */
    RESERVES_SHORT,

    /**
     * The escrow contract holds more than the ledger can account for.
     *
     * <p>Softer than a shortfall and still worth a look. Tokens sent straight to the
     * contract address by someone skipping the flow land here, and so does an event the
     * watcher never saw, which is a failure nothing else in this reconciler detects.
     */
    RESERVES_UNACCOUNTED,

    /**
     * The total an incremental run has been carrying forward is not what the entries say.
     *
     * <p>Only a full run can raise this, and raising it is most of why full runs still
     * happen. An incremental run adds the window's entries to a figure a previous run
     * wrote down, so it never looks at history again; if that figure is wrong, every run
     * after it agrees with itself forever. The full run re-derives the same total from
     * the entries and says so when they part company.
     *
     * <p>Two faults produce it. One is arithmetic: a bug in the fold, or a window that
     * missed or double-counted a row. The other is worse: history below the watermark
     * changed after it was checked, which the append-only triggers are supposed to make
     * impossible and this is how anyone would find out they had not.
     */
    CHECKPOINT_DRIFT,

    /**
     * An invoice's escrow holds an amount that is neither nothing nor the invoice's own.
     *
     * <p>Escrow has exactly two resting states. Empty, because it has not been funded yet
     * or because it has already been released. Or holding the invoice amount exactly,
     * because that is what being funded means. Anything between the two is money that
     * arrived against this invoice and does not settle it.
     *
     * <p>The chain rail is how it happens. The contract takes an amount and a bytes32 from
     * anyone and compares neither against an invoice it has never heard of, so a deposit
     * can be short, and the contract allows one deposit per invoice id, so it cannot then
     * be topped up. The credit is kept, because the money is real and the platform is
     * holding it, and the invoice stays unfunded, because a seller reads escrow_funded as
     * a promise that the whole amount is there. This finding is what puts the resulting
     * stalemate in front of a person.
     *
     * <p>Every other check here compares the ledger against the chain or against itself.
     * This one is the only one that compares the ledger against what the business actually
     * agreed, which is why nothing caught it before it existed.
     */
    ESCROW_DOES_NOT_MATCH_INVOICE
}
