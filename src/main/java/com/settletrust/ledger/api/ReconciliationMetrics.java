package com.settletrust.ledger.api;

import com.settletrust.ledger.reconciliation.Discrepancy;
import com.settletrust.ledger.reconciliation.OpenFindings;
import com.settletrust.ledger.reconciliation.PostgresReconciliationRuns;
import com.settletrust.ledger.reconciliation.ReconciliationReport;
import com.settletrust.ledger.reconciliation.RunMode;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The two numbers worth waking somebody for, and the counters that explain them.
 *
 * <p>The service exposes what it knows and something else decides what is worth an alert.
 * That is the whole reason there is no push client here: a service that sends its own
 * alerts has to be told where to send them, and that address then becomes configuration
 * nobody can change without a deployment.
 *
 * <p><b>No gauge here reads the database when it is scraped.</b> A scrape happens every
 * few seconds and a reconciliation run every fifteen minutes, so a gauge backed by a query
 * would run that query hundreds of times between changes to its own value and turn the
 * monitoring system into a load generator. The values are held in memory, read once at
 * startup so a restart does not blank them, and updated by the runs themselves.
 *
 * <p><b>Unknown is NaN, not zero.</b> Before the first run there is no answer, and zero
 * open findings is an answer. Prometheus keeps NaN and no {@code > 0} alert fires on it,
 * so the absence is covered by alerting on staleness instead, which is the one that
 * matters: a reconciler nobody has heard from is the failure that looks exactly like a
 * clean book.
 */
@Component
class ReconciliationMetrics {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationMetrics.class);

    private final Clock clock;

    /** Negative until a run has said otherwise, which is reported as NaN. */
    private final AtomicInteger openFindings = new AtomicInteger(-1);

    private final AtomicReference<Instant> lastFullRun = new AtomicReference<>();
    private final AtomicReference<Instant> lastRun = new AtomicReference<>();

    private final MeterRegistry registry;

    ReconciliationMetrics(
            MeterRegistry registry, Clock clock, PostgresReconciliationRuns runs) {

        this.registry = registry;
        this.clock = clock;

        primeFromTheRecord(runs);

        Gauge.builder("settletrust.reconciliation.open.findings", openFindings,
                        holder -> holder.get() < 0 ? Double.NaN : holder.get())
                .description("Discrepancies still outstanding as of the last full run")
                .register(registry);

        Gauge.builder("settletrust.reconciliation.full.run.age", lastFullRun, this::ageInSeconds)
                .description("Seconds since the book was last re-derived in full")
                .baseUnit("seconds")
                .register(registry);

        Gauge.builder("settletrust.reconciliation.run.age", lastRun, this::ageInSeconds)
                .description("Seconds since any reconciliation run completed")
                .baseUnit("seconds")
                .register(registry);
    }

    /**
     * Reads the current position once, at startup.
     *
     * <p>Without this a restart reports NaN until the first scheduled run, and a service
     * that restarts often would spend most of its life unable to say whether anything was
     * wrong. Failure here is logged and not fatal: metrics are how you find out the
     * service is unwell, and refusing to start because they could not be primed gets that
     * backwards.
     */
    private void primeFromTheRecord(PostgresReconciliationRuns runs) {
        try {
            runs.openFindings().ifPresent(open -> {
                openFindings.set(open.findings().size());
                lastFullRun.set(open.sinceRanAt());
            });
            runs.latest().ifPresent(latest -> lastRun.set(latest.ranAt()));
        } catch (RuntimeException unreadable) {
            log.warn("Could not read the reconciliation record at startup, "
                    + "metrics will report unknown until the first run: {}",
                    unreadable.getMessage());
        }
    }

    /** Called by the schedule after every run that happened. */
    void recordRun(ReconciliationReport report) {
        lastRun.set(report.ranAt());
        if (report.mode() == RunMode.FULL) {
            lastFullRun.set(report.ranAt());
        }

        registry.counter("settletrust.reconciliation.runs",
                        "mode", report.mode().name().toLowerCase(),
                        "outcome", report.agreed() ? "clean" : "discrepancies")
                .increment();

        // Counted as they are reported rather than as they are open, so the rate says how
        // often something new goes wrong. The gauge above is the standing total.
        for (Discrepancy discrepancy : report.discrepancies()) {
            registry.counter("settletrust.reconciliation.findings.reported",
                            "kind", discrepancy.kind().name().toLowerCase())
                    .increment();
        }
    }

    /** The standing total, which is a question about every run since the last full one. */
    void recordOpen(OpenFindings open) {
        openFindings.set(open.findings().size());
    }

    /** A run that never happened, because another instance held the lease. */
    void recordSkipped() {
        registry.counter("settletrust.reconciliation.runs",
                        "mode", "none", "outcome", "lease_held")
                .increment();
    }

    void recordFailed() {
        registry.counter("settletrust.reconciliation.runs",
                        "mode", "none", "outcome", "failed")
                .increment();
    }

    private double ageInSeconds(AtomicReference<Instant> at) {
        Instant when = at.get();
        return when == null
                ? Double.NaN
                : Duration.between(when, clock.instant()).toSeconds();
    }
}
