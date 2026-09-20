package com.settletrust.ledger.api;

import com.settletrust.ledger.Money;
import com.settletrust.ledger.reconciliation.Discrepancy;
import com.settletrust.ledger.reconciliation.OpenFinding;
import com.settletrust.ledger.reconciliation.OpenFindings;
import com.settletrust.ledger.reconciliation.ReconciliationReport;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The wire shape of a reconciliation report. */
final class ReconciliationDtos {

    private ReconciliationDtos() {
    }

    /**
     * The counts travel with the verdict on purpose. A client that only reads
     * {@code agreed} cannot tell a clean book from a run that examined nothing, and
     * "nothing to check" is what a stopped watcher looks like from here.
     *
     * <p>{@code reservesChecked} says whether the chain itself was asked. False means
     * every check compared our own records against each other, which a clean verdict
     * alone would not reveal.
     *
     * <p>{@code mode} is what the counts have to be read through. An INCREMENTAL run
     * counted its window, and a window with no deposits in it is the ordinary state of a
     * quiet chain rather than the stopped watcher the same zero would mean on a FULL run.
     * {@code checkedFrom} and {@code checkedTo} say which window, in transaction ids, so
     * two consecutive reports can be seen to join up.
     */
    record ReportView(
            UUID runId,
            Instant ranAt,
            boolean agreed,
            String mode,
            long checkedFrom,
            long checkedTo,
            int observationsChecked,
            int transfersChecked,
            boolean reservesChecked,
            List<FindingView> discrepancies) {

        static ReportView of(ReconciliationReport report) {
            return new ReportView(
                    report.runId(),
                    report.ranAt(),
                    report.agreed(),
                    report.mode().name(),
                    report.range().from(),
                    report.range().to(),
                    report.observationsChecked(),
                    report.transfersChecked(),
                    report.reservesChecked(),
                    report.discrepancies().stream().map(FindingView::of).toList());
        }
    }

    /**
     * What is outstanding, and what that claim rests on.
     *
     * <p>{@code sinceRun} and {@code sinceRanAt} are not decoration. Nothing listed here
     * is open because a run said so recently; everything <i>not</i> listed is absent
     * because the full run named here did not find it, so the age of that run is the age
     * of the assurance.
     */
    record OpenFindingsView(
            UUID sinceRun,
            Instant sinceRanAt,
            boolean allClear,
            int openCount,
            List<OpenFindingView> findings) {

        static OpenFindingsView of(OpenFindings open) {
            return new OpenFindingsView(
                    open.sinceRun(),
                    open.sinceRanAt(),
                    open.allClear(),
                    open.findings().size(),
                    open.findings().stream().map(OpenFindingView::of).toList());
        }
    }

    record OpenFindingView(
            FindingView finding,
            Instant firstSeen,
            UUID firstSeenRun,
            Instant lastSeen,
            UUID lastSeenRun,
            int timesReported) {

        static OpenFindingView of(OpenFinding open) {
            return new OpenFindingView(
                    FindingView.of(open.latest()),
                    open.firstSeen(),
                    open.firstSeenRun(),
                    open.lastSeen(),
                    open.lastSeenRun(),
                    open.timesReported());
        }
    }

    record FindingView(
            String kind,
            String subject,
            String detail,
            Long expectedMinor,
            Long foundMinor,
            String currency) {

        static FindingView of(Discrepancy discrepancy) {
            return new FindingView(
                    discrepancy.kind().name(),
                    discrepancy.subject(),
                    discrepancy.detail(),
                    discrepancy.expectedAmount().map(Money::minorUnits).orElse(null),
                    discrepancy.foundAmount().map(Money::minorUnits).orElse(null),
                    discrepancy.currency().orElse(null));
        }
    }
}
