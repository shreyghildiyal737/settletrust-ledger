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
    ABANDONED
}
