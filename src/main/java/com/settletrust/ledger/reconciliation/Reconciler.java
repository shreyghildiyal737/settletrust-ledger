package com.settletrust.ledger.reconciliation;

import com.settletrust.ledger.Money;
import com.settletrust.ledger.chain.EscrowReserves;
import com.settletrust.ledger.chain.ObservationStatus;
import com.settletrust.ledger.settlement.InvoiceSettlement;
import com.settletrust.ledger.settlement.SettlementKeys;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.impl.DSL;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 */
public class Reconciler {

    private final DSLContext dsl;
    private final Clock clock;
    private final PostgresReconciliationRuns runs;
    private final EscrowReserves reserves;

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
        this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
        this.reserves = reserves;
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
     * <p>The cost is an open snapshot for the length of the run, which holds back vacuum
     * on a large book. That is one of the reasons the next version of this reconciles
     * from a watermark rather than scanning everything.
     */
    public ReconciliationReport run() {
        return dsl.transactionResult(config -> {
            DSLContext snapshot = DSL.using(config);
            // Must be the first statement in the transaction, and it is: jOOQ has issued
            // no SQL yet, and Postgres takes the snapshot itself at the first query below.
            snapshot.execute("set transaction isolation level repeatable read");

            List<Discrepancy> found = new ArrayList<>();

            found.addAll(currenciesThatDoNotNetToZero(snapshot));
            found.addAll(transfersThatAreNotBalancedPairs(snapshot));

            Deposits deposits = depositsAgainstTheLedger(snapshot);
            found.addAll(deposits.discrepancies());

            found.addAll(creditsWithNoConfirmedDepositBehindThem(snapshot));
            found.addAll(chainAccountsAgainstWhatTheChainSent(snapshot));
            found.addAll(overdrawnCustomerAccounts(snapshot));

            // Last, and outside the snapshot of necessity, because the chain has no
            // snapshot to join. See reservesAgainstTheChain for why the order matters.
            found.addAll(reservesAgainstTheChain(snapshot));

            ReconciliationReport report = new ReconciliationReport(
                    UUID.randomUUID(),
                    clock.instant(),
                    deposits.checked(),
                    countOfTransfers(snapshot),
                    reserves != null,
                    found);

            // Written inside the same transaction, so the report lands with the snapshot
            // it describes rather than alongside a database that has moved on.
            runs.recordWithin(config, report);
            return report;
        });
    }

    /**
     * The oldest check in accounting: every entry in a currency must net to zero.
     *
     * <p>It is cheap and it subsumes a great deal. Any single-sided write, any half-posted
     * transfer and any entry inserted by hand shows up here, whatever produced it.
     */
    private List<Discrepancy> currenciesThatDoNotNetToZero(DSLContext snapshot) {
        Field<BigDecimal> net = DSL.sum(ENTRY.AMOUNT_MINOR);

        return snapshot.select(ENTRY.CURRENCY, net)
                .from(ENTRY)
                .groupBy(ENTRY.CURRENCY)
                .having(net.ne(BigDecimal.ZERO))
                .fetch()
                .map(row -> {
                    String currency = row.get(ENTRY.CURRENCY);
                    Money drift = Money.of(row.get(net).longValueExact(), currency);
                    return Discrepancy.mismatch(
                            DiscrepancyKind.ENTRIES_DO_NOT_SUM_TO_ZERO,
                            currency,
                            "the " + currency + " book does not balance",
                            Money.zero(currency),
                            drift);
                });
    }

    /**
     * The same invariant one transfer at a time.
     *
     * <p>Two faults can cancel out across the whole book and still be two faults, so the
     * per-currency check is not enough on its own. The entry count is checked as well as
     * the sum, because three entries netting to zero is a transfer this service has no
     * way of having written.
     */
    private List<Discrepancy> transfersThatAreNotBalancedPairs(DSLContext snapshot) {
        Field<BigDecimal> net = DSL.sum(ENTRY.AMOUNT_MINOR);
        Field<Integer> lines = DSL.count();

        return snapshot.select(ENTRY.TRANSFER_ID, net, lines)
                .from(ENTRY)
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
     */
    private Deposits depositsAgainstTheLedger(DSLContext snapshot) {
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
     */
    private List<Discrepancy> creditsWithNoConfirmedDepositBehindThem(DSLContext snapshot) {
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
     */
    private List<Discrepancy> overdrawnCustomerAccounts(DSLContext snapshot) {
        Field<BigDecimal> balance = DSL.coalesce(DSL.sum(ENTRY.AMOUNT_MINOR), BigDecimal.ZERO);

        return snapshot.select(ACCOUNT.ID, ACCOUNT.CURRENCY, balance)
                .from(ACCOUNT)
                .leftJoin(ENTRY).on(ENTRY.ACCOUNT_ID.eq(ACCOUNT.ID))
                .where(ACCOUNT.KIND.eq("CUSTOMER"))
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

    private static int countOfTransfers(DSLContext snapshot) {
        return snapshot.fetchCount(TRANSFER);
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
