package com.settletrust.ledger.reconciliation;

import com.settletrust.ledger.Money;
import org.jooq.Configuration;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.settletrust.ledger.jooq.Tables.RECONCILIATION_FINDING;
import static com.settletrust.ledger.jooq.Tables.RECONCILIATION_RUN;

/**
 * Stores reconciliation reports and reads them back.
 *
 * <p>A run and its findings are written in one transaction. A report that recorded four
 * findings and then failed before the fifth would understate the problem, which is the
 * one thing a reconciliation record must never do.
 *
 * <p>{@link #recordWithin} lets that transaction be the caller's, so the reconciler can
 * store a report under the same snapshot the report describes.
 */
public class PostgresReconciliationRuns {

    /** Long enough for the longest detail the reconciler writes; the column agrees. */
    private static final int MAX_DETAIL = 512;

    private final DSLContext dsl;

    public PostgresReconciliationRuns(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
    }

    public void record(ReconciliationReport report) {
        dsl.transaction(config -> recordWithin(config, report));
    }

    /**
     * Stores a report inside a transaction the caller owns, which is how the reconciler
     * uses it: the report is written under the same snapshot it describes.
     */
    public void recordWithin(Configuration config, ReconciliationReport report) {
        DSLContext transaction = DSL.using(config);

        transaction.insertInto(RECONCILIATION_RUN)
                .set(RECONCILIATION_RUN.ID, report.runId())
                .set(RECONCILIATION_RUN.RAN_AT,
                        LocalDateTime.ofInstant(report.ranAt(), ZoneOffset.UTC))
                .set(RECONCILIATION_RUN.OBSERVATIONS_CHECKED, report.observationsChecked())
                .set(RECONCILIATION_RUN.TRANSFERS_CHECKED, report.transfersChecked())
                .set(RECONCILIATION_RUN.DISCREPANCY_COUNT, report.discrepancies().size())
                .execute();

        for (Discrepancy discrepancy : report.discrepancies()) {
            transaction.insertInto(RECONCILIATION_FINDING)
                    .set(RECONCILIATION_FINDING.ID, UUID.randomUUID())
                    .set(RECONCILIATION_FINDING.RUN_ID, report.runId())
                    .set(RECONCILIATION_FINDING.KIND, discrepancy.kind().name())
                    .set(RECONCILIATION_FINDING.SUBJECT, discrepancy.subject())
                    .set(RECONCILIATION_FINDING.DETAIL, truncated(discrepancy.detail()))
                    .set(RECONCILIATION_FINDING.EXPECTED_MINOR,
                            discrepancy.expectedAmount().map(Money::minorUnits).orElse(null))
                    .set(RECONCILIATION_FINDING.FOUND_MINOR,
                            discrepancy.foundAmount().map(Money::minorUnits).orElse(null))
                    .set(RECONCILIATION_FINDING.CURRENCY,
                            discrepancy.currency().orElse(null))
                    .execute();
        }
    }

    public Optional<ReconciliationReport> latest() {
        return dsl.selectFrom(RECONCILIATION_RUN)
                .orderBy(RECONCILIATION_RUN.RAN_AT.desc(), RECONCILIATION_RUN.ID.desc())
                .limit(1)
                .fetchOptional()
                .map(run -> toReport(run, findingsOf(run.get(RECONCILIATION_RUN.ID))));
    }

    public Optional<ReconciliationReport> find(UUID runId) {
        return dsl.selectFrom(RECONCILIATION_RUN)
                .where(RECONCILIATION_RUN.ID.eq(runId))
                .fetchOptional()
                .map(run -> toReport(run, findingsOf(runId)));
    }

    private List<Discrepancy> findingsOf(UUID runId) {
        return dsl.selectFrom(RECONCILIATION_FINDING)
                .where(RECONCILIATION_FINDING.RUN_ID.eq(runId))
                .orderBy(RECONCILIATION_FINDING.KIND.asc(), RECONCILIATION_FINDING.SUBJECT.asc())
                .fetch()
                .map(PostgresReconciliationRuns::toDiscrepancy);
    }

    private static ReconciliationReport toReport(Record run, List<Discrepancy> findings) {
        return new ReconciliationReport(
                run.get(RECONCILIATION_RUN.ID),
                run.get(RECONCILIATION_RUN.RAN_AT).toInstant(ZoneOffset.UTC),
                run.get(RECONCILIATION_RUN.OBSERVATIONS_CHECKED),
                run.get(RECONCILIATION_RUN.TRANSFERS_CHECKED),
                findings);
    }

    private static Discrepancy toDiscrepancy(Record row) {
        Long expected = row.get(RECONCILIATION_FINDING.EXPECTED_MINOR);
        Long found = row.get(RECONCILIATION_FINDING.FOUND_MINOR);
        String currency = row.get(RECONCILIATION_FINDING.CURRENCY);

        return new Discrepancy(
                DiscrepancyKind.valueOf(row.get(RECONCILIATION_FINDING.KIND)),
                row.get(RECONCILIATION_FINDING.SUBJECT),
                row.get(RECONCILIATION_FINDING.DETAIL),
                expected == null ? null : Money.of(expected, currency),
                found == null ? null : Money.of(found, currency));
    }

    /**
     * The detail is prose for a human and the column is bounded, so an unusually long one
     * is cut rather than allowed to fail the insert. Losing the tail of a sentence is
     * survivable; losing the finding because the sentence was long is not.
     */
    private static String truncated(String detail) {
        return detail.length() <= MAX_DETAIL ? detail : detail.substring(0, MAX_DETAIL);
    }
}
