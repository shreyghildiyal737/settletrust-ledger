package com.settletrust.ledger.reconciliation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What one reconciliation pass found.
 *
 * <p>The counts are part of the answer, not decoration. "No discrepancies" means nothing
 * on its own: a run that checked no observations because the watcher had silently stopped
 * would report exactly the same thing as a healthy one. The counts are how an operator
 * tells a clean book from a blind check.
 *
 * <p>{@code reservesChecked} is there for the same reason and matters more. Every check
 * but one compares our own records against each other; only the reserve check asks the
 * chain. A deployment with no node configured still produces clean reports, and without
 * this flag they would look exactly like reports that had verified the money was really
 * there.
 *
 * <p>{@code range} is the third thing that qualifies the verdict, and it changes what the
 * counts mean. A {@link RunMode#FULL} run counts the book; an {@link RunMode#INCREMENTAL}
 * one counts its window, where zero observations is the normal state of a quiet chain
 * rather than a stopped watcher. Read the counts through the mode or do not read them.
 */
public record ReconciliationReport(
        UUID runId,
        Instant ranAt,
        CheckedRange range,
        int observationsChecked,
        int transfersChecked,
        boolean reservesChecked,
        List<Discrepancy> discrepancies) {

    public ReconciliationReport {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(ranAt, "ranAt must not be null");
        Objects.requireNonNull(range, "range must not be null");
        discrepancies = List.copyOf(discrepancies);
    }

    /**
     * True when everything this run looked at agreed.
     *
     * <p>Deliberately says nothing about what was looked at. Read it next to
     * {@link #reservesChecked()}, {@link #range()} and the counts, never on its own.
     */
    public boolean agreed() {
        return discrepancies.isEmpty();
    }

    public RunMode mode() {
        return range.mode();
    }

    public List<Discrepancy> of(DiscrepancyKind kind) {
        return discrepancies.stream().filter(d -> d.kind() == kind).toList();
    }
}
