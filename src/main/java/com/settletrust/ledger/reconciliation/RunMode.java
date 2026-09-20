package com.settletrust.ledger.reconciliation;

/**
 * Whether a run answered for the whole book or only for what has changed since the last
 * one.
 *
 * <p>Stored with every report, because the two produce the same words and mean different
 * things. "Clean" from a {@link #FULL} run is a statement about the ledger; "clean" from
 * an {@link #INCREMENTAL} one is a statement about a window, and a window can be empty.
 */
public enum RunMode {

    /**
     * Everything below the watermark, re-derived from the rows.
     *
     * <p>The only run that can say the book is sound, and the only one that checks the
     * carried totals an incremental run builds on. Necessarily the first run against a
     * database, since there is nothing to carry forward yet.
     */
    FULL,

    /**
     * The window since the last run, plus the checks that cannot be windowed at all.
     *
     * <p>Cheap enough to run every few minutes on a book that a full pass could not be.
     * What it cannot do is notice a fault in history it has already passed, which is what
     * the periodic full run is for.
     */
    INCREMENTAL
}
