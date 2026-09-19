package com.settletrust.ledger.api;

import com.settletrust.ledger.reconciliation.Discrepancy;
import com.settletrust.ledger.reconciliation.ReconciliationReport;
import com.settletrust.ledger.reconciliation.Reconciler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the reconciler on a timer.
 *
 * <p>A fixed delay rather than a fixed rate: the next run starts a set time after the
 * last one finished, so a slow pass on a large book cannot queue runs behind itself.
 *
 * <p>There is exactly one of these per deployment, which is a real constraint: two
 * instances on the same database would both reconcile and both write a report of the
 * same facts. That is wasteful rather than dangerous, since reconciliation only reads the
 * ledger, and it is called out here because the fix is a lease and this does not have one
 * yet.
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
        ReconciliationReport report;
        try {
            report = reconciler.run();
        } catch (RuntimeException failure) {
            // Swallowed deliberately: the scheduler cancels a task that throws, and a
            // reconciler that quietly stops after one bad night is worse than no
            // reconciler, because the silence looks identical to a clean book.
            log.error("Reconciliation failed to complete", failure);
            return;
        }

        if (report.agreed()) {
            log.info("Reconciliation {} clean: {} deposits and {} transfers checked",
                    report.runId(), report.observationsChecked(), report.transfersChecked());
            return;
        }

        log.error("Reconciliation {} found {} discrepancies",
                report.runId(), report.discrepancies().size());
        for (Discrepancy discrepancy : report.discrepancies()) {
            log.error("  {} {}: {}",
                    discrepancy.kind(), discrepancy.subject(), discrepancy.detail());
        }
    }
}
