package com.settletrust.ledger.api;

import com.settletrust.ledger.reconciliation.Discrepancy;
import com.settletrust.ledger.reconciliation.ReconciliationReport;
import com.settletrust.ledger.reconciliation.Reconciler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Runs the reconciler on a timer.
 *
 * <p>A fixed delay rather than a fixed rate: the next run starts a set time after the
 * last one finished, so a slow pass on a large book cannot queue runs behind itself.
 *
 * <p>Several instances may run this safely. The reconciler takes a lease before it
 * starts, so exactly one of them does the work and the others find it taken and go back
 * to sleep until their next tick.
 */
@Component
@ConditionalOnProperty(
        name = "ledger.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
class ReconciliationSchedule {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationSchedule.class);

    private final Reconciler reconciler;

    ReconciliationSchedule(Reconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(
            initialDelayString = "${ledger.reconciliation.initial-delay:PT1M}",
            fixedDelayString = "${ledger.reconciliation.interval:PT15M}")
    void reconcile() {
        Optional<ReconciliationReport> completed;
        try {
            completed = reconciler.run();
        } catch (RuntimeException failure) {
            // Swallowed deliberately: the scheduler cancels a task that throws, and a
            // reconciler that quietly stops after one bad night is worse than no
            // reconciler, because the silence looks identical to a clean book.
            log.error("Reconciliation failed to complete", failure);
            return;
        }

        if (completed.isEmpty()) {
            // Expected on every instance but one, so it is not a warning.
            log.info("Another instance holds the reconciliation lease, skipping this tick");
            return;
        }

        ReconciliationReport report = completed.get();
        if (report.agreed()) {
            // The mode is logged with the counts because it is what they mean. Two
            // deposits checked by a full run is a two-deposit book; two checked by an
            // incremental one is two deposits since the last tick and says nothing at all
            // about the rest.
            log.info("Reconciliation {} clean ({}): {} deposits and {} transfers checked, "
                            + "reserves {}",
                    report.runId(), report.mode(), report.observationsChecked(),
                    report.transfersChecked(),
                    report.reservesChecked() ? "verified against the chain" : "NOT checked");
            return;
        }

        log.error("Reconciliation {} ({}) found {} discrepancies",
                report.runId(), report.mode(), report.discrepancies().size());
        for (Discrepancy discrepancy : report.discrepancies()) {
            log.error("  {} {}: {}",
                    discrepancy.kind(), discrepancy.subject(), discrepancy.detail());
        }
    }
}
