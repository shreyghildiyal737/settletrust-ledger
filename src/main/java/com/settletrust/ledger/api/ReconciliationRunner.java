package com.settletrust.ledger.api;

import com.settletrust.ledger.reconciliation.PostgresReconciliationRuns;
import com.settletrust.ledger.reconciliation.ReconciliationReport;
import com.settletrust.ledger.reconciliation.Reconciler;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Runs the reconciler and records what happened, whoever asked for it.
 *
 * <p>The timer and the incident endpoint both come through here, and that is the whole
 * reason this class exists. The metrics used to be recorded by the schedule alone, so a
 * deep run requested through the API was invisible to every alert written against those
 * names: it did not reset the staleness gauge and was never counted. The alarm saying
 * nobody had reconciled recently therefore stayed lit while an operator was reconciling,
 * which is the worst moment for a monitoring system to be wrong, and the counter that is
 * supposed to reveal a reconciler failing quietly could not see the runs most likely to
 * fail.
 *
 * <p>A run is recorded here rather than inside {@link Reconciler} because what to publish
 * about a run is an operational concern and the reconciler's job is the arithmetic. The
 * two are deployed together and change for different reasons.
 */
@Component
class ReconciliationRunner {

    private final Reconciler reconciler;
    private final PostgresReconciliationRuns runs;
    private final ReconciliationMetrics metrics;

    ReconciliationRunner(
            Reconciler reconciler,
            PostgresReconciliationRuns runs,
            ReconciliationMetrics metrics) {

        this.reconciler = reconciler;
        this.runs = runs;
        this.metrics = metrics;
    }

    /**
     * Reconciles once and records the outcome.
     *
     * @param deep re-derives the whole book rather than the window since the last run
     * @return the report, or empty when another instance held the lease and no run
     *         happened at all
     * @throws RuntimeException if the run failed, counted before it is rethrown so that a
     *         caller is free to handle it however suits it without losing the fact that
     *         it happened
     */
    Optional<ReconciliationReport> reconcile(boolean deep) {
        Optional<ReconciliationReport> completed;
        try {
            completed = deep ? reconciler.runFully() : reconciler.run();
        } catch (RuntimeException failure) {
            metrics.recordFailed();
            throw failure;
        }

        if (completed.isEmpty()) {
            metrics.recordSkipped();
            return completed;
        }

        ReconciliationReport report = completed.get();
        metrics.recordRun(report);
        // Read back rather than derived from this report alone: what is open is a question
        // about every run since the last full one, and this run is only the newest of them.
        // Kept apart from the counter above so a run is always counted, even if this read
        // ever comes back empty.
        runs.openFindings().ifPresent(metrics::recordOpen);
        return completed;
    }
}
