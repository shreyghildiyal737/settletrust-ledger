package com.settletrust.ledger.api;

import com.settletrust.ledger.reconciliation.PostgresReconciliationRuns;
import com.settletrust.ledger.reconciliation.Reconciler;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.settletrust.ledger.api.ReconciliationDtos.ReportView;

/**
 * Reconciliation over HTTP: run one now, or read the last one.
 *
 * <p>The schedule is what normally drives this. The endpoint exists because the first
 * thing anyone wants during an incident is the current answer rather than the answer from
 * up to a quarter of an hour ago, and because a report nobody can ask for is a report
 * nobody reads.
 */
@RestController
@RequestMapping(path = "/api/v1/reconciliation")
class ReconciliationController {

    private final Reconciler reconciler;
    private final PostgresReconciliationRuns runs;

    ReconciliationController(Reconciler reconciler, PostgresReconciliationRuns runs) {
        this.reconciler = reconciler;
        this.runs = runs;
    }

    /**
     * 201 whatever it finds. The run happened and is now on record, which is what the
     * status code is about; whether the books balanced is in the body, and a caller that
     * treated a discovered discrepancy as a failed request would retry it forever.
     *
     * <p>409 is the one other answer, and it means something different: another instance
     * holds the lease, so no run happened at all. Retrying that one is reasonable, which
     * is exactly why it must not share a status code with a run that found problems.
     */
    @PostMapping("/runs")
    ResponseEntity<ReportView> runNow() {
        return reconciler.run()
                .map(report -> ResponseEntity.status(HttpStatus.CREATED).body(ReportView.of(report)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).build());
    }

    @GetMapping("/runs/latest")
    ResponseEntity<ReportView> latest() {
        return runs.latest()
                .map(report -> ResponseEntity.ok(ReportView.of(report)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
