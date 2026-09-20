package com.settletrust.ledger.reconciliation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A discrepancy that is still outstanding, and how long it has been.
 *
 * <p>Identified by kind and subject together, because that pair is what makes two reports
 * the same fault rather than two faults. The same overdrawn account found by six
 * consecutive runs is one thing wrong, and an operator who sees it six times learns to
 * stop reading.
 *
 * @param latest         the most recent report of it, whose detail and amounts are the
 *                       current ones. An amount can move while the fault stays the same
 *                       fault, and the newest figure is the one worth acting on.
 * @param firstSeen      when it was first reported, which is the number that says whether
 *                       this is new or has been ignored for a week
 * @param lastSeen       when it was last reported
 * @param timesReported  how many runs have found it
 */
public record OpenFinding(
        Discrepancy latest,
        Instant firstSeen,
        UUID firstSeenRun,
        Instant lastSeen,
        UUID lastSeenRun,
        int timesReported) {

    public OpenFinding {
        Objects.requireNonNull(latest, "latest must not be null");
        Objects.requireNonNull(firstSeen, "firstSeen must not be null");
        Objects.requireNonNull(lastSeen, "lastSeen must not be null");
    }

    public DiscrepancyKind kind() {
        return latest.kind();
    }

    public String subject() {
        return latest.subject();
    }
}
