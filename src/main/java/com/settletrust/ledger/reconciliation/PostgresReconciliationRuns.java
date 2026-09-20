package com.settletrust.ledger.reconciliation;

import com.settletrust.ledger.Money;
import org.jooq.Configuration;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.impl.DSL;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.settletrust.ledger.jooq.Tables.RECONCILIATION_CHECKPOINT;
import static com.settletrust.ledger.jooq.Tables.RECONCILIATION_FINDING;
import static com.settletrust.ledger.jooq.Tables.RECONCILIATION_RUN;

/**
 * Stores reconciliation reports and reads them back.
 *
 * <p>A run, its findings and its carried totals are written in one transaction. A report
 * that recorded four findings and then failed before the fifth would understate the
 * problem, which is the one thing a reconciliation record must never do, and a watermark
 * that advanced without the totals belonging to it would hand the next run a window it
 * had no starting figure for.
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

    /**
     * Stores a report inside a transaction the caller owns, which is how the reconciler
     * uses it: the report is written under the same snapshot it describes.
     */
    public void recordWithin(
            Configuration config, ReconciliationReport report, Map<String, Carried.Total> totals) {

        DSLContext transaction = DSL.using(config);

        transaction.insertInto(RECONCILIATION_RUN)
                .set(RECONCILIATION_RUN.ID, report.runId())
                .set(RECONCILIATION_RUN.RAN_AT,
                        LocalDateTime.ofInstant(report.ranAt(), ZoneOffset.UTC))
                .set(RECONCILIATION_RUN.MODE, report.mode().name())
                .set(RECONCILIATION_RUN.CHECKED_FROM, report.range().from())
                .set(RECONCILIATION_RUN.CHECKED_TO, report.range().to())
                .set(RECONCILIATION_RUN.OBSERVATIONS_CHECKED, report.observationsChecked())
                .set(RECONCILIATION_RUN.TRANSFERS_CHECKED, report.transfersChecked())
                .set(RECONCILIATION_RUN.DISCREPANCY_COUNT, report.discrepancies().size())
                .set(RECONCILIATION_RUN.RESERVES_CHECKED, report.reservesChecked())
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

        for (Map.Entry<String, Carried.Total> total : totals.entrySet()) {
            transaction.insertInto(RECONCILIATION_CHECKPOINT)
                    .set(RECONCILIATION_CHECKPOINT.RUN_ID, report.runId())
                    .set(RECONCILIATION_CHECKPOINT.CURRENCY, total.getKey())
                    .set(RECONCILIATION_CHECKPOINT.NET_MINOR, total.getValue().netMinor())
                    .set(RECONCILIATION_CHECKPOINT.ENTRIES_COUNTED,
                            total.getValue().entriesCounted())
                    .execute();
        }
    }

    /**
     * The totals and watermark the next run continues from, read inside the caller's
     * transaction.
     *
     * <p>The predecessor is the newest run that recorded a watermark at all. Runs from
     * before watermarking exist and recorded none, and a run that cannot say where it
     * stopped cannot be continued from; the next run goes deep instead, which is the
     * right answer for a database whose recent history nobody has a figure for.
     *
     * <p>Two runs recorded in the same instant are separated by an arbitrary tiebreak,
     * and that is safe rather than merely unlikely to matter: the mark and the totals are
     * read from one row, so whichever run is chosen, its totals are the totals at its own
     * mark. An older predecessor widens the next window and re-examines rows that were
     * already clean. It cannot cause one to be skipped.
     */
    public Optional<Carried> carriedFrom(Configuration config) {
        DSLContext transaction = DSL.using(config);

        return transaction.select(RECONCILIATION_RUN.ID, RECONCILIATION_RUN.CHECKED_TO)
                .from(RECONCILIATION_RUN)
                .where(RECONCILIATION_RUN.CHECKED_TO.isNotNull())
                .orderBy(RECONCILIATION_RUN.RAN_AT.desc(), RECONCILIATION_RUN.ID.desc())
                .limit(1)
                .fetchOptional()
                .map(run -> {
                    UUID runId = run.get(RECONCILIATION_RUN.ID);
                    return new Carried(
                            runId,
                            run.get(RECONCILIATION_RUN.CHECKED_TO),
                            totalsOf(transaction, runId));
                });
    }

    /** When the book was last re-derived rather than carried forward, if it ever was. */
    public Optional<Instant> lastFullRun(Configuration config) {
        LocalDateTime ranAt = DSL.using(config)
                .select(DSL.max(RECONCILIATION_RUN.RAN_AT))
                .from(RECONCILIATION_RUN)
                .where(RECONCILIATION_RUN.MODE.eq(RunMode.FULL.name()))
                .fetchOne(0, LocalDateTime.class);

        return Optional.ofNullable(ranAt).map(when -> when.toInstant(ZoneOffset.UTC));
    }

    private static Map<String, Carried.Total> totalsOf(DSLContext transaction, UUID runId) {
        Map<String, Carried.Total> totals = new HashMap<>();
        transaction.selectFrom(RECONCILIATION_CHECKPOINT)
                .where(RECONCILIATION_CHECKPOINT.RUN_ID.eq(runId))
                .fetch()
                .forEach(row -> totals.put(
                        row.get(RECONCILIATION_CHECKPOINT.CURRENCY),
                        new Carried.Total(
                                row.get(RECONCILIATION_CHECKPOINT.NET_MINOR),
                                row.get(RECONCILIATION_CHECKPOINT.ENTRIES_COUNTED))));
        return totals;
    }

    /**
     * The discrepancies still outstanding, anchored on the last run that looked
     * everywhere.
     *
     * <p>Empty when nothing has ever been reconciled, which is a different answer from
     * "nothing is wrong" and is kept different on purpose: a service whose reconciler has
     * never run must not be able to report a clean book.
     *
     * <p>See {@link OpenFindings} for why the anchor is the newest full run and why this
     * is computed rather than stored.
     */
    public Optional<OpenFindings> openFindings() {
        Record anchor = dsl.select(RECONCILIATION_RUN.ID, RECONCILIATION_RUN.RAN_AT)
                .from(RECONCILIATION_RUN)
                .where(RECONCILIATION_RUN.MODE.eq(RunMode.FULL.name()))
                .orderBy(RECONCILIATION_RUN.RAN_AT.desc(), RECONCILIATION_RUN.ID.desc())
                .limit(1)
                .fetchOne();

        if (anchor == null) {
            return Optional.empty();
        }

        UUID anchorId = anchor.get(RECONCILIATION_RUN.ID);
        LocalDateTime anchorAt = anchor.get(RECONCILIATION_RUN.RAN_AT);

        // The anchor run and everything after it. Two runs recorded in the same instant are
        // separated by the same id tiebreak the rest of this class orders by, so the cut is
        // exactly the one "the newest full run" names and not a second either side of it.
        Result<Record> reports = dsl.select()
                .from(RECONCILIATION_FINDING)
                .join(RECONCILIATION_RUN)
                .on(RECONCILIATION_RUN.ID.eq(RECONCILIATION_FINDING.RUN_ID))
                .where(RECONCILIATION_RUN.RAN_AT.gt(anchorAt)
                        .or(RECONCILIATION_RUN.RAN_AT.eq(anchorAt)
                                .and(RECONCILIATION_RUN.ID.ge(anchorId))))
                .orderBy(RECONCILIATION_RUN.RAN_AT.asc(), RECONCILIATION_RUN.ID.asc())
                .fetch();

        // Keyed on kind and subject: the same fault found by six runs is one thing wrong.
        Map<String, OpenFinding> open = new LinkedHashMap<>();
        for (Record row : reports) {
            Discrepancy discrepancy = toDiscrepancy(row);
            UUID runId = row.get(RECONCILIATION_RUN.ID);
            Instant ranAt = row.get(RECONCILIATION_RUN.RAN_AT).toInstant(ZoneOffset.UTC);

            open.merge(
                    discrepancy.kind().name() + "\u0000" + discrepancy.subject(),
                    new OpenFinding(discrepancy, ranAt, runId, ranAt, runId, 1),
                    // Rows arrive oldest first, so the incoming one is always the newer:
                    // its detail and amounts win, and the first sighting is kept.
                    (older, newer) -> new OpenFinding(
                            newer.latest(),
                            older.firstSeen(),
                            older.firstSeenRun(),
                            newer.lastSeen(),
                            newer.lastSeenRun(),
                            older.timesReported() + 1));
        }

        List<OpenFinding> sorted = open.values().stream()
                .sorted(Comparator.comparing((OpenFinding f) -> f.kind().name())
                        .thenComparing(OpenFinding::subject))
                .toList();

        return Optional.of(new OpenFindings(
                anchorId, anchorAt.toInstant(ZoneOffset.UTC), sorted));
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
                rangeOf(run),
                run.get(RECONCILIATION_RUN.OBSERVATIONS_CHECKED),
                run.get(RECONCILIATION_RUN.TRANSFERS_CHECKED),
                run.get(RECONCILIATION_RUN.RESERVES_CHECKED),
                findings);
    }

    /**
     * A run from before watermarking scanned the whole book and recorded no range, so it
     * reads back as a full run ending at zero. Zero is honest here: it says the run
     * covered everything and left no point for anything to continue from.
     */
    private static CheckedRange rangeOf(Record run) {
        Long to = run.get(RECONCILIATION_RUN.CHECKED_TO);
        if (to == null) {
            return CheckedRange.everything(0L);
        }
        return RunMode.valueOf(run.get(RECONCILIATION_RUN.MODE)) == RunMode.FULL
                ? CheckedRange.everything(to)
                : CheckedRange.since(run.get(RECONCILIATION_RUN.CHECKED_FROM), to);
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
