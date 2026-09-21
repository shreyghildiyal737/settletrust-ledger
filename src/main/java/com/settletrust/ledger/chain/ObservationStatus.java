package com.settletrust.ledger.chain;

/** What the watcher has decided about a deposit it saw. */
public enum ObservationStatus {

    /** Seen, not yet buried under enough blocks to be acted on. */
    PENDING,

    /** Confirmed and credited: the money is in the invoice's escrow. */
    CONFIRMED,

    /** Was confirmed and credited, then the chain took the block back. Entries reversed. */
    REVERSED,

    /** Seen while pending, then dropped by the chain before it was ever credited. */
    ABANDONED,

    /**
     * Names an invoice that exists, and cannot be applied to it: the wrong currency.
     *
     * <p>Terminal, unlike PENDING. Waiting longer cannot fix what arrived, and a deposit
     * left pending would be reconsidered on every pass for ever. Nothing is credited, so
     * the money stays visible as a contract balance the ledger cannot explain.
     */
    MISMATCHED
}
