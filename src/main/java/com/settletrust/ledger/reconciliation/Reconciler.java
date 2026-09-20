package com.settletrust.ledger.reconciliation;

import com.settletrust.ledger.Money;
import com.settletrust.ledger.chain.EscrowReserves;
import com.settletrust.ledger.chain.ObservationStatus;
import com.settletrust.ledger.settlement.InvoiceSettlement;
import com.settletrust.ledger.settlement.SettlementKeys;
import org.jooq.Condition;
import org.jooq.Configuration;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.impl.DSL;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import static com.settletrust.ledger.jooq.Tables.ACCOUNT;
import static com.settletrust.ledger.jooq.Tables.CHAIN_OBSERVATION;
import static com.settletrust.ledger.jooq.Tables.ENTRY;
import static com.settletrust.ledger.jooq.Tables.TRANSFER;

/**
 * Asks whether the two settlement rails still tell the same story.
 *
 * <p>The ledger and the chain are written by the same code, in the same transactions, so
 * in theory they cannot disagree. Reconciliation exists because "in theory" is the part
 * that fails: a bug, a manual correction, a half-applied migration or a restore from a
 * backup taken mid-flight all produce books that are internally consistent and wrong.
 * Every check below is derived from stored rows rather than from anything the service
 * remembers, so it would still catch a fault introduced by a process that never ran
 * through this code at all.
 *
 * <p>The checks are deliberately in both directions. Asking only "was every confirmed
 * deposit credited" finds money owed to customers and misses money credited that nobody
 * ever sent, which is the more expensive of the two.
 *
 * <p>Nothing here repairs anything. A reconciler that silently corrects what it finds
 * destroys the evidence of how the books came to be wrong, and the second occurrence then
 * looks like the first.
 *
 * <h2>Why most runs no longer read the whole book</h2>
 *
 * <p>Scanning everything every fifteen minutes is correct and does not scale, so a run
 * normally looks only at the window of transactions since the last one and adds its
 * findings to totals the previous run wrote down. {@link CheckedRange} explains how the
 * window is chosen and why it is bounded by transaction visibility rather than by an id
 * or a timestamp.
 *
 * <p>Which checks may use the window at all is decided by the data, not by convenience,
 * and the rule is that <b>only what cannot change may be skipped</b>:
 *
 * <ul>
 *   <li>Sums over {@code entry} are folded, because an entry is written once and never
 *       revised, so a window sum is a true delta.
 *   <li>Checks whose finding names one subject use the window only to choose which
 *       subjects to look at, and then read each one in full. An account with no new
 *       entries cannot newly be overdrawn; a transfer with no new entries cannot newly be
 *       unbalanced.
 *   <li>Aggregates over {@code chain_observation} are not windowed at all. That table is
 *       a cursor over a chain that changes its mind, and leaving a mutated row out of a
 *       sum does not remove what it used to contribute, so a fold over it would drift
 *       away from the truth with nothing to notice.
 * </ul>
 *
 * <p>An observation that does change is restamped by a trigger and falls back inside the
 * next window, which is what keeps the subject-by-subject deposit checks honest across a
 * reorg.
 */
public class Reconciler {

    /**
     * The lease, as a Postgres advisory lock key.
     *
     * <p>An arbitrary but fixed number. Advisory locks share one namespace per database
     * rather than per schema, so this must not collide with a key any other part of the
     * system picks, and it is written here where that can be checked.
     */
    static final long LEASE_KEY = 6_251_968_311_047_201L;

    /**
     * How stale the last full pass may get before the next run goes deep.
     *
     * <p>A day, because the deep run is what makes every incremental verdict in between
     * worth anything: it is the only run that re-derives the carried totals and the only
     * one that can see a fault in history the windows have already passed. Shorter costs
     * a full scan more often than a large book can afford; much longer leaves a window of
     * time in which a silent rewrite is nobody's finding.
     */
    private static final Duration DEFAULT_DEEP_INTERVAL = Duration.ofHours(24);

    private final DSLContext dsl;
    private final Clock clock;
    private final PostgresReconciliationRuns runs;
    private final EscrowReserves reserves;
    private final Duration deepInterval;

    /** Without a chain to ask, so every check compares our own records against each other. */
    public Reconciler(DSLContext dsl, Clock clock, PostgresReconciliationRuns runs) {
        this(dsl, clock, runs, null);
    }

    /**
     * @param reserves the chain itself, or null where no node is configured. Null is
     *                 permitted because a deployment without a node still needs the other
     *                 checks, and the report records which of the two it was rather than
     *                 letting an unchecked run read as a verified one.
     */
    public Reconciler(
            DSLContext dsl, Clock clock, PostgresReconciliationRuns runs, EscrowReserves reserves) {
        this(dsl, clock, runs, reserves, DEFAULT_DEEP_INTERVAL);
    }

    public Reconciler(
            DSLContext dsl,
            Clock clock,
            PostgresReconciliationRuns runs,
            EscrowReserves reserves,
            Duration deepInterval) {

        this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
        this.reserves = reserves;
        this.deepInterval = Objects.requireNonNull(deepInterval, "deepInterval must not be null");
    }

    /**
     * Reconciles, choosing for itself whether the book needs re-deriving or the window
     * will do. This is what the schedule calls.
     */
    public Optional<ReconciliationReport> run() {
        return reconcile(false);
    }

    /**
     * Reconciles the whole book, whatever the schedule would have chosen.
     *
     * <p>What an operator wants during an incident. An incremental report is an answer
     * about the last few minutes, and the question in an incident is always about the
     * book.
     */
    public Optional<ReconciliationReport> runFully() {
        return reconcile(true);
    }

    /**
     * Runs every check against one snapshot of the database, records the report, and
     * returns it.
     *
     * <p>The isolation level is the whole reason this is a transaction, since nothing
     * here depends on the ledger for its own writes. Under {@code read committed} each
     * statement takes a fresh snapshot, so a settlement committing between two of these
     * checks would be counted by one and not the other, and the reconciler would report a
     * discrepancy that never existed. A job that cries wolf every few runs is worse than
     * no job at all, because it teaches an operator to close the alert without reading
     * it. {@code repeatable read} gives every statement the same snapshot and the
     * false positive cannot arise.
     *
     * <p>Not {@code serializable}: that buys protection against write skew, and this
     * writes nothing the checks read. It would only add predicate locking and
     * serialisation failures to a read-only pass.
     *
     * <p>The cost is an open snapshot for the length of the run, which holds back vacuum.
     * That is now bounded by the window rather than by the size of the book, except on
     * the deep run, which pays the old price deliberately and once a day.
     *
     * <p><b>Empty means somebody else is already reconciling.</b> Two instances against
     * one database would otherwise both scan the book and both write a report of the same
     * facts, which is wasteful rather than dangerous, and clutters the record an operator
     * has to read during an incident with duplicates of itself.
     *
     * <p>The lease is a Postgres advisory lock scoped to this transaction, chosen over a
     * row or a Redis key because it needs no cleanup and cannot go stale. An instance
     * that dies mid-run loses its connection, Postgres ends the transaction, and the lock
     * is gone with it. A lease held in a table has to be given an expiry, and choosing
     * that expiry means guessing how long a run takes on a book you have not seen yet.
     *
     * <p>The lease is also what makes the watermark safe to advance: only one run reads
     * the previous mark and writes the next one, so the marks cannot interleave.
     */
    private Optional<ReconciliationReport> reconcile(boolean deepRegardless) {
        return dsl.transactionResult(config -> {
            DSLContext snapshot = DSL.using(config);
            // Must be the first statement in the transaction, and it is: jOOQ has issued
            // no SQL yet, and Postgres takes the snapshot itself at the first query below.
            snapshot.execute("set transaction isolation level repeatable read");

            if (!leaseTaken(snapshot)) {
                return Optional.<ReconciliationReport>empty();
            }

            Optional<Carried> carried = runs.carriedFrom(config);
            CheckedRange range = rangeFor(config, carried, horizonOf(snapshot), deepRegardless);

            List<Discrepancy> found = new ArrayList<>();

            Nets nets = netsByCurrency(snapshot, range, carried);
            found.addAll(nets.discrepancies());

            found.addAll(transfersThatAreNotBalancedPairs(snapshot, range));

            Deposits deposits = depositsAgainstTheLedger(snapshot, range);
            found.addAll(deposits.discrepancies());

            found.addAll(creditsWithNoConfirmedDepositBehindThem(snapshot, range));
            found.addAll(chainAccountsAgainstWhatTheChainSent(snapshot));
            found.addAll(overdrawnCustomerAccounts(snapshot, range));

            // Last, and outside the snapshot of necessity, because the chain has no
            // snapshot to join. See reservesAgainstTheChain for why the order matters.
            found.addAll(reservesAgainstTheChain(snapshot));

            ReconciliationReport report = new ReconciliationReport(
                    UUID.randomUUID(),
                    clock.instant(),
                    range,
                    deposits.checked(),
                    transfersIn(snapshot, range),
                    reserves != null,
                    found);

            // Written inside the same transaction, so the report and the totals the next
            // run will build on land with the snapshot they describe rather than
            // alongside a database that has moved on.
            runs.recordWithin(config, report, nets.carryForward());
            return Optional.of(report);
        });
    }

    /**
     * How much of the book this run answers for.
     *
     * <p>Deep when asked to be, when there is nothing to carry forward, and when the last
     * deep run has aged out. Otherwise the window runs from where the last one stopped.
     */
    private CheckedRange rangeFor(
            Configuration config, Optional<Carried> carried, long horizon, boolean deepRegardless) {

        if (deepRegardless || carried.isEmpty() || deepRunIsDue(config)) {
            return CheckedRange.everything(horizon);
        }

        long from = carried.get().checkedThrough();
        // The mark never moves backwards. It can fail to move forwards, because a
        // long-running transaction anywhere in the database holds xmin down, and an empty
        // window is a perfectly good answer: the run does less work rather than skipping
        // any. A window running backwards would not be an answer at all.
        return CheckedRange.since(from, Math.max(horizon, from));
    }

    private boolean deepRunIsDue(Configuration config) {
        return runs.lastFullRun(config)
                .map(last -> !last.isAfter(clock.instant().minus(deepInterval)))
                .orElse(true);
    }

    /**
     * The point below which no transaction can still arrive.
     *
     * <p>{@code pg_snapshot_xmin} is the oldest transaction still in flight when this
     * transaction took its snapshot. Every transaction below it has already been decided,
     * and every transaction that has not yet written will be given an id above it, so a
     * window ending here can never be reopened by a late commit.
     */
    private static long horizonOf(DSLContext snapshot) {
        return snapshot
                .select(DSL.field(
                        "pg_snapshot_xmin(pg_current_snapshot())::text::bigint", Long.class))
                .fetchOne(0, Long.class);
    }

    /**
     * Tries for the lease without waiting. Waiting would be worse than skipping: the
     * runs are on a timer, so the next one is minutes away, and a queue of instances
     * blocked on a lock is a queue of open snapshots holding back vacuum.
     */
    private static boolean leaseTaken(DSLContext snapshot) {
        return Boolean.TRUE.equals(snapshot
                .select(DSL.field("pg_try_advisory_xact_lock(?)", Boolean.class, LEASE_KEY))
                .fetchOne(0, Boolean.class));
    }

    /** The totals this run leaves for the next one, and what they said about this one. */
    private record Nets(Map<String, Carried.Total> carryForward, List<Discrepancy> discrepancies) {
    }

    /**
     * The oldest check in accounting: every entry in a currency must net to zero.
     *
     * <p>It is cheap and it subsumes a great deal. Any single-sided write, any half-posted
     * transfer and any entry inserted by hand shows up here, whatever produced it.
     *
     * <p>It is also the check that folds. An incremental run sums the window and adds the
     * previous run's figure, so the verdict is still about the whole book even though the
     * query read a few minutes of it. That is only sound because an entry never changes
     * after it is written, and it is only trustworthy because a deep run re-derives the
     * carried figure and reports {@link DiscrepancyKind#CHECKPOINT_DRIFT} if it has come
     * adrift.
     */
    private Nets netsByCurrency(
            DSLContext snapshot, CheckedRange range, Optional<Carried> carried) {

        Map<String, Carried.Total> totals =
                new TreeMap<>(totalsIn(snapshot, range.covering(ENTRY.XID)));

        if (!range.isFull()) {
            carried.orElseThrow(() -> new IllegalStateException(
                            "an incremental run has nothing to carry forward from"))
                    .totals()
                    .forEach((currency, carriedTotal) ->
                            totals.merge(currency, carriedTotal, Carried.Total::plus));
        }

        List<Discrepancy> found = new ArrayList<>();
        totals.forEach((currency, total) -> {
            if (total.netMinor() != 0) {
                found.add(Discrepancy.mismatch(
                        DiscrepancyKind.ENTRIES_DO_NOT_SUM_TO_ZERO,
                        currency,
                        "the " + currency + " book does not balance",
                        Money.zero(currency),
                        Money.of(total.netMinor(), currency)));
            }
        });

        if (range.isFull() && carried.isPresent()) {
            found.addAll(carriedTotalsAgainstTheEntries(snapshot, carried.get()));
        }

        return new Nets(totals, found);
    }

    /**
     * The deep run's real job: checking the number every run in between has been trusting.
     *
     * <p>Re-derives the previous run's totals from the entries themselves, at exactly the
     * watermark that run stopped at, so the two figures describe the same set of rows and
     * a difference between them is a difference of substance rather than of timing.
     *
     * <p>Without this the fold is a closed loop. A total that went wrong once would be
     * carried forward by every run after it, each of them agreeing with the last and none
     * of them looking at the entries again.
     */
    private List<Discrepancy> carriedTotalsAgainstTheEntries(DSLContext snapshot, Carried carried) {
        Map<String, Carried.Total> rederived =
                totalsIn(snapshot, ENTRY.XID.lt(carried.checkedThrough()));

        Set<String> currencies = new TreeSet<>(rederived.keySet());
        currencies.addAll(carried.totals().keySet());

        List<Discrepancy> found = new ArrayList<>();
        for (String currency : currencies) {
            Carried.Total claimed = carried.of(currency);
            Carried.Total truth = rederived.getOrDefault(currency, Carried.Total.NOTHING);
            if (claimed.equals(truth)) {
                continue;
            }

            found.add(Discrepancy.mismatch(
                    DiscrepancyKind.CHECKPOINT_DRIFT,
                    currency,
                    "run " + carried.runId() + " carried "
                            + Money.of(claimed.netMinor(), currency) + " over "
                            + claimed.entriesCounted() + " entries, and re-reading the same "
                            + "entries gives " + Money.of(truth.netMinor(), currency) + " over "
                            + truth.entriesCounted(),
                    // The entries are the authority. The carried figure is the claim.
                    Money.of(truth.netMinor(), currency),
                    Money.of(claimed.netMinor(), currency)));
        }
        return found;
    }

    private static Map<String, Carried.Total> totalsIn(DSLContext snapshot, Condition of) {
        Field<BigDecimal> net = DSL.sum(ENTRY.AMOUNT_MINOR);
        Field<Integer> lines = DSL.count();

        Map<String, Carried.Total> totals = new HashMap<>();
        snapshot.select(ENTRY.CURRENCY, net, lines)
                .from(ENTRY)
                .where(of)
                .groupBy(ENTRY.CURRENCY)
                .fetch()
                .forEach(row -> totals.put(
                        row.get(ENTRY.CURRENCY),
                        new Carried.Total(row.get(net).longValueExact(), row.get(lines))));
        return totals;
    }

    /**
     * The same invariant one transfer at a time.
     *
     * <p>Two faults can cancel out across the whole book and still be two faults, so the
     * per-currency check is not enough on its own. The entry count is checked as well as
     * the sum, because three entries netting to zero is a transfer this service has no
     * way of having written.
     *
     * <p>The window picks which transfers to look at and nothing more: every entry of a
     * transfer is written in the transaction that wrote the transfer, so a stray entry
     * added later carries its own transaction id and drags the transfer it names back
     * into view. The group is then summed in full, which is what lets the finding say
     * three entries rather than the one that happened to arrive late.
     */
    private List<Discrepancy> transfersThatAreNotBalancedPairs(
            DSLContext snapshot, CheckedRange range) {

        Field<BigDecimal> net = DSL.sum(ENTRY.AMOUNT_MINOR);
        Field<Integer> lines = DSL.count();
        com.settletrust.ledger.jooq.tables.Entry touched = ENTRY.as("touched");

        return snapshot.select(ENTRY.TRANSFER_ID, net, lines)
                .from(ENTRY)
                .where(ENTRY.TRANSFER_ID.in(DSL.select(touched.TRANSFER_ID)
                        .from(touched)
                        .where(range.covering(touched.XID))))
                .groupBy(ENTRY.TRANSFER_ID)
                .having(net.ne(BigDecimal.ZERO).or(lines.ne(2)))
                .fetch()
                .map(row -> Discrepancy.of(
                        DiscrepancyKind.TRANSFER_NOT_BALANCED,
                        row.get(ENTRY.TRANSFER_ID).toString(),
                        row.get(lines) + " entries netting " + row.get(net).toPlainString()
                                + " minor units"));
    }

    /** How many observations were examined, and what was wrong with them. */
    private record Deposits(int checked, List<Discrepancy> discrepancies) {
    }

    /**
     * Every deposit the chain rail claims to have acted on, against the money that moved.
     *
     * <p>Joined on the idempotency key rather than matched in Java, because the key is
     * exactly the link the settlement code created and rebuilding it here is the whole
     * check. Both transfers are outer-joined in one pass: a reversed deposit has to have
     * both the credit that happened and the reversal that undid it, and a reversal
     * standing alone means the ledger is holding money the chain took back.
     *
     * <p>The window applies to all three rows, because any of them can be the new one. A
     * deposit whose status changed is restamped by the trigger and returns here under its
     * new status; a credit or a reversal written after its observation was last examined
     * brings the pair back on its own account.
     */
    private Deposits depositsAgainstTheLedger(DSLContext snapshot, CheckedRange range) {
        com.settletrust.ledger.jooq.tables.Transfer credit = TRANSFER.as("credit");
        com.settletrust.ledger.jooq.tables.Transfer reversal = TRANSFER.as("reversal");

        Result<Record> rows = snapshot.select()
                .from(CHAIN_OBSERVATION)
                .leftJoin(credit)
                .on(credit.IDEMPOTENCY_KEY.eq(keyFor(SettlementKeys.CHAIN_DEPOSIT_PREFIX)))
                .leftJoin(reversal)
                .on(reversal.IDEMPOTENCY_KEY.eq(keyFor(SettlementKeys.CHAIN_REVERSAL_PREFIX)))
                .where(CHAIN_OBSERVATION.STATUS.in(
                        ObservationStatus.CONFIRMED.name(), ObservationStatus.REVERSED.name()))
                .and(range.covering(CHAIN_OBSERVATION.XID)
                        .or(range.covering(credit.XID))
                        .or(range.covering(reversal.XID)))
                .orderBy(CHAIN_OBSERVATION.BLOCK_NUMBER.asc(), CHAIN_OBSERVATION.LOG_INDEX.asc())
                .fetch();

        List<Discrepancy> found = new ArrayList<>();
        for (Record row : rows) {
            String subject = row.get(CHAIN_OBSERVATION.TX_HASH)
                    + ":" + row.get(CHAIN_OBSERVATION.LOG_INDEX);
            String invoiceId = row.get(CHAIN_OBSERVATION.INVOICE_ID);
            Money reported = Money.of(
                    row.get(CHAIN_OBSERVATION.AMOUNT_MINOR),
                    row.get(CHAIN_OBSERVATION.CURRENCY));

            if (row.get(credit.ID) == null) {
                found.add(Discrepancy.mismatch(
                        DiscrepancyKind.CONFIRMED_DEPOSIT_NOT_CREDITED,
                        subject,
                        "the chain rail credited invoice " + invoiceId
                                + " and no transfer moved the money",
                        reported,
                        Money.zero(reported.currency())));
            } else {
                found.addAll(creditMatches(subject, invoiceId, reported, row, credit));
            }

            if (ObservationStatus.REVERSED.name().equals(row.get(CHAIN_OBSERVATION.STATUS))) {
                found.addAll(reversalMatches(subject, reported, row, reversal));
            }
        }
        return new Deposits(rows.size(), found);
    }

    private List<Discrepancy> creditMatches(
            String subject,
            String invoiceId,
            Money reported,
            Record row,
            com.settletrust.ledger.jooq.tables.Transfer credit) {

        List<Discrepancy> found = new ArrayList<>();
        Money credited = Money.of(row.get(credit.AMOUNT_MINOR), row.get(credit.CURRENCY));
        if (!credited.equals(reported)) {
            found.add(Discrepancy.mismatch(
                    DiscrepancyKind.CREDIT_AMOUNT_DISAGREES,
                    subject,
                    "the chain reported " + reported + " and the ledger moved " + credited,
                    reported,
                    credited));
        }

        String expectedEscrow = InvoiceSettlement.escrowAccountFor(invoiceId).value();
        String creditedTo = row.get(credit.TO_ACCOUNT);
        if (!expectedEscrow.equals(creditedTo)) {
            found.add(Discrepancy.of(
                    DiscrepancyKind.CREDIT_MISDIRECTED,
                    subject,
                    "credited to " + creditedTo + " rather than " + expectedEscrow));
        }
        return found;
    }

    private List<Discrepancy> reversalMatches(
            String subject,
            Money reported,
            Record row,
            com.settletrust.ledger.jooq.tables.Transfer reversal) {

        if (row.get(reversal.ID) == null) {
            return List.of(Discrepancy.mismatch(
                    DiscrepancyKind.REVERSAL_NOT_RECORDED,
                    subject,
                    "the chain took this deposit back and the ledger still holds the money",
                    reported,
                    Money.zero(reported.currency())));
        }

        Money undone = Money.of(row.get(reversal.AMOUNT_MINOR), row.get(reversal.CURRENCY));
        if (undone.equals(reported)) {
            return List.of();
        }
        return List.of(Discrepancy.mismatch(
                DiscrepancyKind.CREDIT_AMOUNT_DISAGREES,
                subject,
                "reversed " + undone + " against a deposit of " + reported,
                reported,
                undone));
    }

    /**
     * The check in the other direction: money credited under a deposit key that the chain
     * rail does not vouch for.
     *
     * <p>A pending or abandoned observation counts as not vouching for it. Those deposits
     * are ones the watcher has seen and deliberately not acted on, so a transfer carrying
     * their key means something moved money the watcher had decided not to trust yet.
     *
     * <p>Windowed from either side, and the observation side is the one that matters: a
     * deposit demoted from confirmed back to pending is restamped, so the credit that was
     * legitimate when it was last examined comes back under its new circumstances.
     */
    private List<Discrepancy> creditsWithNoConfirmedDepositBehindThem(
            DSLContext snapshot, CheckedRange range) {

        return snapshot.select(TRANSFER.IDEMPOTENCY_KEY, TRANSFER.AMOUNT_MINOR, TRANSFER.CURRENCY,
                        CHAIN_OBSERVATION.STATUS)
                .from(TRANSFER)
                .leftJoin(CHAIN_OBSERVATION)
                .on(keyFor(SettlementKeys.CHAIN_DEPOSIT_PREFIX).eq(TRANSFER.IDEMPOTENCY_KEY))
                .where(TRANSFER.IDEMPOTENCY_KEY.startsWith(SettlementKeys.CHAIN_DEPOSIT_PREFIX))
                .and(CHAIN_OBSERVATION.STATUS.isNull()
                        .or(CHAIN_OBSERVATION.STATUS.notIn(
                                ObservationStatus.CONFIRMED.name(),
                                ObservationStatus.REVERSED.name())))
                .and(range.covering(TRANSFER.XID)
                        .or(range.covering(CHAIN_OBSERVATION.XID)))
                .fetch()
                .map(row -> {
                    String status = row.get(CHAIN_OBSERVATION.STATUS);
                    Money moved = Money.of(
                            row.get(TRANSFER.AMOUNT_MINOR), row.get(TRANSFER.CURRENCY));
                    return Discrepancy.mismatch(
                            DiscrepancyKind.CREDIT_WITHOUT_CONFIRMATION,
                            row.get(TRANSFER.IDEMPOTENCY_KEY),
                            status == null
                                    ? "no deposit on record was ever seen for this key"
                                    : "the deposit behind this credit is " + status,
                            Money.zero(moved.currency()),
                            moved);
                });
    }

    /**
     * The aggregate cross-rail check: what the platform's chain account says it sent,
     * against what the chain says it received.
     *
     * <p>A {@code chain:<currency>} account's balance is negative by construction, because
     * every confirmed deposit moves money out of it and into an escrow. Its magnitude is
     * therefore the total the platform is holding on chain, and that total has an
     * independent second source: the observations themselves. Reversed deposits cancel
     * out, since the reversal pays back into the same account, so only confirmed ones
     * count.
     *
     * <p><b>Not windowed, on either side, deliberately.</b> One side aggregates
     * {@code chain_observation}, where a row that leaves the window has not stopped
     * contributing to the total it was counted in, so no fold over it is sound. Leaving
     * the ledger side windowed while the chain side was not would be worse still: the two
     * halves would be answering about different sets of deposits, and the difference
     * between them would be arithmetic rather than a fault. Both sides scan, and the
     * table they scan is bounded by how often the chain pays rather than by how much the
     * ledger has ever done.
     */
    private List<Discrepancy> chainAccountsAgainstWhatTheChainSent(DSLContext snapshot) {
        Map<String, Long> confirmedByCurrency = new HashMap<>();
        Field<BigDecimal> deposited = DSL.sum(CHAIN_OBSERVATION.AMOUNT_MINOR);
        snapshot.select(CHAIN_OBSERVATION.CURRENCY, deposited)
                .from(CHAIN_OBSERVATION)
                .where(CHAIN_OBSERVATION.STATUS.eq(ObservationStatus.CONFIRMED.name()))
                .groupBy(CHAIN_OBSERVATION.CURRENCY)
                .fetch()
                .forEach(row -> confirmedByCurrency.put(
                        row.get(CHAIN_OBSERVATION.CURRENCY), row.get(deposited).longValueExact()));

        Field<BigDecimal> balance = DSL.coalesce(DSL.sum(ENTRY.AMOUNT_MINOR), BigDecimal.ZERO);

        return snapshot.select(ACCOUNT.ID, ACCOUNT.CURRENCY, balance)
                .from(ACCOUNT)
                .leftJoin(ENTRY).on(ENTRY.ACCOUNT_ID.eq(ACCOUNT.ID))
                .where(ACCOUNT.ID.startsWith(InvoiceSettlement.CHAIN_ACCOUNT_PREFIX))
                .groupBy(ACCOUNT.ID, ACCOUNT.CURRENCY)
                .fetch()
                .stream()
                .map(row -> {
                    String currency = row.get(ACCOUNT.CURRENCY);
                    Money held = Money.of(row.get(balance).longValueExact(), currency);
                    Money owed = Money.of(
                            -confirmedByCurrency.getOrDefault(currency, 0L), currency);
                    return held.equals(owed)
                            ? null
                            : Discrepancy.mismatch(
                                    DiscrepancyKind.CHAIN_ACCOUNT_DISAGREES,
                                    row.get(ACCOUNT.ID),
                                    "the chain confirmed " + owed.negated()
                                            + " into escrow and the ledger moved "
                                            + held.negated(),
                                    owed,
                                    held);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * The rule the transfer path enforces, checked against the rows rather than the code.
     *
     * <p>A customer account holds money that arrived from somewhere, so it can never be
     * overdrawn; a house account is the platform's own position and may be negative all
     * day. If a customer account has gone below zero, the lock that was supposed to
     * serialise its spending did not, and no amount of re-reading the transfer code will
     * show that.
     *
     * <p>The window chooses the accounts and the balance is still summed over every entry
     * they have. An account nothing was posted to cannot have changed its balance, so
     * skipping it costs nothing; summing only the window's entries would have reported
     * every account that happened to pay money out this quarter of an hour.
     */
    private List<Discrepancy> overdrawnCustomerAccounts(DSLContext snapshot, CheckedRange range) {
        Field<BigDecimal> balance = DSL.coalesce(DSL.sum(ENTRY.AMOUNT_MINOR), BigDecimal.ZERO);
        com.settletrust.ledger.jooq.tables.Entry touched = ENTRY.as("touched");

        return snapshot.select(ACCOUNT.ID, ACCOUNT.CURRENCY, balance)
                .from(ACCOUNT)
                .leftJoin(ENTRY).on(ENTRY.ACCOUNT_ID.eq(ACCOUNT.ID))
                .where(ACCOUNT.KIND.eq("CUSTOMER"))
                .and(ACCOUNT.ID.in(DSL.select(touched.ACCOUNT_ID)
                        .from(touched)
                        .where(range.covering(touched.XID))))
                .groupBy(ACCOUNT.ID, ACCOUNT.CURRENCY)
                .having(balance.lt(BigDecimal.ZERO))
                .fetch()
                .map(row -> {
                    String currency = row.get(ACCOUNT.CURRENCY);
                    Money held = Money.of(row.get(balance).longValueExact(), currency);
                    return Discrepancy.mismatch(
                            DiscrepancyKind.CUSTOMER_ACCOUNT_OVERDRAWN,
                            row.get(ACCOUNT.ID),
                            "a customer account is overdrawn",
                            Money.zero(currency),
                            held);
                });
    }

    /**
     * The one check the chain, rather than our record of the chain, is the authority for.
     *
     * <p>Everything above compares the ledger against {@code chain_observation}, and the
     * watcher wrote both. A watcher that misread the chain produces two records that
     * agree beautifully and are both wrong, and no amount of cross-checking them finds
     * it. So this asks the contract what it is actually holding.
     *
     * <p><b>A shortfall is the alarm.</b> A contract holding less than the ledger has
     * already credited out of it means the platform has paid out against money that is
     * not there. It is computed from the {@code chain:<currency>} balance, which is a
     * ledger figure, rather than from the observations, so the two sides of the
     * comparison share no source.
     *
     * <p><b>A surplus is a question, not an alarm.</b> The contract legitimately holds
     * more than the ledger has credited, because deposits still waiting for their
     * confirmations are already in there. Only what those fail to explain is reported,
     * and that remainder is either somebody sending tokens straight to the contract
     * address or an event the watcher never saw. The second is a real failure that
     * nothing else here can detect.
     *
     * <p><b>Why the reads are ordered as they are.</b> The database snapshot is taken
     * first and the chain asked last, so the chain answer is never older than the ledger
     * it is compared against. A deposit confirmed while the run is in flight therefore
     * shows up in the chain balance and not in the ledger, which inflates the surplus and
     * can never manufacture a shortfall. The timing artefact lands on the finding that
     * tolerates one, and the alarming finding stays true whenever it fires.
     *
     * <p><b>Not windowed.</b> The contract reports what it holds now, in total; there is
     * no such thing as asking it for the window. The ledger side is left whole to match.
     */
    private List<Discrepancy> reservesAgainstTheChain(DSLContext snapshot) {
        if (reserves == null) {
            return List.of();
        }

        Field<BigDecimal> balance = DSL.coalesce(DSL.sum(ENTRY.AMOUNT_MINOR), BigDecimal.ZERO);
        Map<String, Long> credited = new HashMap<>();
        snapshot.select(ACCOUNT.CURRENCY, balance)
                .from(ACCOUNT)
                .leftJoin(ENTRY).on(ENTRY.ACCOUNT_ID.eq(ACCOUNT.ID))
                .where(ACCOUNT.ID.startsWith(InvoiceSettlement.CHAIN_ACCOUNT_PREFIX))
                .groupBy(ACCOUNT.CURRENCY)
                .fetch()
                // Negated: the chain account is down by whatever it has lent to escrows,
                // and that is exactly what the contract should still be holding.
                .forEach(row -> credited.put(
                        row.get(ACCOUNT.CURRENCY), -row.get(balance).longValueExact()));

        Field<BigDecimal> waiting = DSL.sum(CHAIN_OBSERVATION.AMOUNT_MINOR);
        Map<String, Long> awaitingConfirmation = new HashMap<>();
        snapshot.select(CHAIN_OBSERVATION.CURRENCY, waiting)
                .from(CHAIN_OBSERVATION)
                .where(CHAIN_OBSERVATION.STATUS.eq(ObservationStatus.PENDING.name()))
                .groupBy(CHAIN_OBSERVATION.CURRENCY)
                .fetch()
                .forEach(row -> awaitingConfirmation.put(
                        row.get(CHAIN_OBSERVATION.CURRENCY), row.get(waiting).longValueExact()));

        Map<String, Long> held = new HashMap<>();
        for (Money onChain : reserves.heldOnChain()) {
            held.merge(onChain.currency(), onChain.minorUnits(), Long::sum);
        }

        List<Discrepancy> found = new ArrayList<>();
        Set<String> currencies = new TreeSet<>(held.keySet());
        currencies.addAll(credited.keySet());

        for (String currency : currencies) {
            long onChain = held.getOrDefault(currency, 0L);
            long owed = credited.getOrDefault(currency, 0L);

            if (onChain < owed) {
                found.add(Discrepancy.mismatch(
                        DiscrepancyKind.RESERVES_SHORT,
                        InvoiceSettlement.chainAccountFor(currency).value(),
                        "the ledger has credited " + Money.of(owed, currency)
                                + " out of a contract holding " + Money.of(onChain, currency),
                        Money.of(owed, currency),
                        Money.of(onChain, currency)));
                continue;
            }

            long explained = Math.addExact(owed, awaitingConfirmation.getOrDefault(currency, 0L));
            if (onChain > explained) {
                found.add(Discrepancy.mismatch(
                        DiscrepancyKind.RESERVES_UNACCOUNTED,
                        InvoiceSettlement.chainAccountFor(currency).value(),
                        "the contract holds " + Money.of(onChain - explained, currency)
                                + " more than credited and pending deposits explain",
                        Money.of(explained, currency),
                        Money.of(onChain, currency)));
            }
        }
        return found;
    }

    /** How many transfers fell in this run's window, which on a deep run is all of them. */
    private static int transfersIn(DSLContext snapshot, CheckedRange range) {
        return snapshot.fetchCount(TRANSFER, range.covering(TRANSFER.XID));
    }

    /**
     * Rebuilds a settlement idempotency key in SQL from the observation's own columns, so
     * the join matches the key the settlement code wrote.
     */
    private static Field<String> keyFor(String prefix) {
        return DSL.concat(
                DSL.val(prefix),
                CHAIN_OBSERVATION.TX_HASH,
                DSL.val(":"),
                CHAIN_OBSERVATION.LOG_INDEX.cast(String.class));
    }
}
