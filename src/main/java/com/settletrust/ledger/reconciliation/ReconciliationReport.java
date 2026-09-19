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
 */
public record ReconciliationReport(
        UUID runId,
        Instant ranAt,
        int observationsChecked,
        int transfersChecked,
        List<Discrepancy> discrepancies) {

    public ReconciliationReport {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(ranAt, "ranAt must not be null");
        discrepancies = List.copyOf(discrepancies);
    }

    /** True when the ledger and the chain agree on everything this run looked at. */
    public boolean agreed() {
        return discrepancies.isEmpty();
    }

    public List<Discrepancy> of(DiscrepancyKind kind) {
        return discrepancies.stream().filter(d -> d.kind() == kind).toList();
    }
}
