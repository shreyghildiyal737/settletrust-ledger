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
    RESERVES_UNACCOUNTED
}
