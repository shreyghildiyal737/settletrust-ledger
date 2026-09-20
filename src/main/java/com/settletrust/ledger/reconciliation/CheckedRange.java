package com.settletrust.ledger.reconciliation;

import org.jooq.Condition;
import org.jooq.Field;

import java.util.Objects;

/**
 * The span of transaction ids one run answered for.
 *
 * <p>Half open, {@code [from, to)}, so consecutive runs tile the history with no row
 * examined twice and none skipped. {@code to} is the run's watermark: the oldest
 * transaction still in flight when it took its snapshot, and therefore a point below
 * which nothing can still arrive.
 *
 * <p>A {@link RunMode#FULL} run starts at zero, which is before any transaction id
 * Postgres will issue. That is not a special case in the SQL: a full run is an
 * incremental run whose window happens to begin at the start of the book, and the one
 * code path is why the two cannot drift apart.
 */
public record CheckedRange(RunMode mode, long from, long to) {

    public CheckedRange {
        Objects.requireNonNull(mode, "mode must not be null");
        if (from < 0 || to < from) {
            throw new IllegalArgumentException("not a range: [" + from + ", " + to + ")");
        }
        if (mode == RunMode.FULL && from != 0) {
            throw new IllegalArgumentException("a full run starts at the beginning, not " + from);
        }
    }

    public static CheckedRange everything(long to) {
        return new CheckedRange(RunMode.FULL, 0L, to);
    }

    public static CheckedRange since(long from, long to) {
        return new CheckedRange(RunMode.INCREMENTAL, from, to);
    }

    /** True when this run re-derives rather than carries forward. */
    public boolean isFull() {
        return mode == RunMode.FULL;
    }

    /** Restricts a query to the rows this run is answering for. */
    public Condition covering(Field<Long> xid) {
        return xid.ge(from).and(xid.lt(to));
    }
}
