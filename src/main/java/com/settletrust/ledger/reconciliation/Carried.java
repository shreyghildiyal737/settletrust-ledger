package com.settletrust.ledger.reconciliation;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What the previous run left behind for the next one to build on.
 *
 * <p>An incremental run does not sum the book. It sums the window and adds the answer the
 * run before it wrote down, which is the only reason the cost of a run is proportional to
 * what changed. The consequence is that this is the one input to reconciliation that the
 * service produced itself, and the checks everywhere else here are built precisely to
 * avoid trusting such a thing.
 *
 * <p>It is trusted under a condition, and the condition is {@link RunMode#FULL}: a
 * periodic run re-derives the same totals from the entries and reports
 * {@link DiscrepancyKind#CHECKPOINT_DRIFT} when they disagree. The carried figure is
 * therefore always falsifiable, and never for longer than the interval between deep runs.
 *
 * @param runId          the run that wrote these totals, so a drift finding can name it
 * @param checkedThrough that run's watermark, which is where the next window begins
 * @param totals         by currency; a currency absent from the map has no entries yet
 */
public record Carried(UUID runId, long checkedThrough, Map<String, Total> totals) {

    public Carried {
        Objects.requireNonNull(runId, "runId must not be null");
        totals = Map.copyOf(totals);
    }

    /**
     * A currency's running position at the watermark.
     *
     * <p>The count is folded alongside the net because a net alone is a weak witness: two
     * errors in opposite directions leave it untouched, and both of them change the
     * number of entries behind it.
     */
    public record Total(long netMinor, long entriesCounted) {

        public static final Total NOTHING = new Total(0L, 0L);

        public Total plus(Total other) {
            return new Total(
                    Math.addExact(netMinor, other.netMinor),
                    Math.addExact(entriesCounted, other.entriesCounted));
        }
    }

    public Total of(String currency) {
        return totals.getOrDefault(currency, Total.NOTHING);
    }
}
