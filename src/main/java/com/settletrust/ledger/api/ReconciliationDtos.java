package com.settletrust.ledger.api;

import com.settletrust.ledger.Money;
import com.settletrust.ledger.reconciliation.Discrepancy;
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
     */
    record ReportView(
            UUID runId,
            Instant ranAt,
            boolean agreed,
            int observationsChecked,
            int transfersChecked,
            List<FindingView> discrepancies) {

        static ReportView of(ReconciliationReport report) {
            return new ReportView(
                    report.runId(),
                    report.ranAt(),
                    report.agreed(),
                    report.observationsChecked(),
                    report.transfersChecked(),
                    report.discrepancies().stream().map(FindingView::of).toList());
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
