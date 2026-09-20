package com.settletrust.ledger.reconciliation;

import com.settletrust.ledger.Account;
import com.settletrust.ledger.AccountId;
import com.settletrust.ledger.Database;
import com.settletrust.ledger.Money;
import com.settletrust.ledger.PostgresLedger;
import com.settletrust.ledger.PostgresTransferService;
import com.settletrust.ledger.TestDatabases;
import com.settletrust.ledger.chain.EscrowWatcher;
import com.settletrust.ledger.chain.FakeChain;
import com.settletrust.ledger.chain.ObservationStatus;
import com.settletrust.ledger.chain.PostgresChainObservations;
import com.settletrust.ledger.invoice.Invoice;
import com.settletrust.ledger.invoice.InvoiceStatus;
import com.settletrust.ledger.invoice.PostgresInvoices;
import com.settletrust.ledger.settlement.InvoiceSettlement;
import com.settletrust.ledger.settlement.SettlementKeys;
import org.jooq.Configuration;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.settletrust.ledger.jooq.Tables.ENTRY;
import static com.settletrust.ledger.jooq.Tables.RECONCILIATION_CHECKPOINT;
import static com.settletrust.ledger.jooq.Tables.RECONCILIATION_RUN;
import static com.settletrust.ledger.jooq.Tables.RECONCILIATION_CHECKPOINT;
import static com.settletrust.ledger.jooq.Tables.RECONCILIATION_RUN;
import static com.settletrust.ledger.jooq.Tables.CHAIN_OBSERVATION;
import static com.settletrust.ledger.jooq.Tables.TRANSFER;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reconciliation, against a book that contains nothing but what each test put there.
 *
 * <p>Every other test class in this suite isolates itself by generating its own account
 * ids and leaving everyone else's rows alone. This one cannot: a reconciliation finding
 * is a statement about the whole database, so a leftover from another test would be part
 * of the answer. Each test therefore gets a freshly migrated schema of its own, which is
 * what lets the assertions be exact — not "at least one finding" but "this finding, and
 * nothing else wrong".
 *
 * <p>The damage is dealt by writing to the tables directly, bypassing the service. That
 * is the scenario worth testing: if the only way to produce a discrepancy were to go
 * through code that prevents discrepancies, reconciliation would have nothing to find and
 * no reason to exist.
 */
class ReconcilerTest {

    private static final int CONFIRMATIONS = 3;
    private static final Money AMOUNT = Money.of(250_000L, "EURC");

    private TestDatabases.Target target;
    private Database database;
    private DSLContext dsl;

    private PostgresLedger ledger;
    private PostgresInvoices invoices;
    private PostgresTransferService transfers;
    private InvoiceSettlement settlement;
    private PostgresChainObservations observations;
    private EscrowWatcher watcher;
    private PostgresReconciliationRuns runs;
    private Reconciler reconciler;
    private Reconciler reconcilerAskingTheChain;
    private FakeChain chain;

    private String run;
    private String invoiceId;
    private String txHash;

    @BeforeEach
    void openAnInvoiceInASchemaOfItsOwn() {
        target = TestDatabases.isolated("recon");
        database = new Database(target.url(), target.username(), target.password());
        dsl = database.dsl();

        Clock clock = Clock.systemUTC();
        ledger = new PostgresLedger(dsl, clock);
        invoices = new PostgresInvoices(dsl, clock);
        transfers = new PostgresTransferService(dsl, clock);
        settlement = new InvoiceSettlement(dsl, ledger, invoices, transfers);
        observations = new PostgresChainObservations(dsl, clock);
        watcher = new EscrowWatcher(dsl, observations, settlement, CONFIRMATIONS);
        runs = new PostgresReconciliationRuns(dsl);
        chain = new FakeChain();

        // Two of them, because "what does a run report when there is no chain to ask" is
        // itself a thing worth asserting.
        reconciler = new Reconciler(dsl, clock, runs);
        reconcilerAskingTheChain = new Reconciler(dsl, clock, runs, chain);

        run = UUID.randomUUID().toString().substring(0, 8);
        invoiceId = "inv-" + run;
        txHash = "0xtx" + run;

        openInvoiceAwaitingItsEscrow(invoiceId);
    }

    @AfterEach
    void dropTheSchema() {
        if (database != null) {
            database.close();
        }
        if (target != null) {
            TestDatabases.drop(target);
        }
    }

    @Test
    @DisplayName("a deposit the watcher credited reconciles, and the run says what it checked")
    void theHappyPathAgrees() {
        confirmTheDeposit();

        ReconciliationReport report = reconciler.run().orElseThrow();

        assertAll(
                () -> assertTrue(report.agreed(), () -> "unexpected: " + report.discrepancies()),
                () -> assertEquals(1, report.observationsChecked(),
                        "a clean verdict over nothing would mean nothing"),
                () -> assertEquals(1, report.transfersChecked()));
    }

    @Test
    @DisplayName("a deposit the chain took back reconciles too, credit and reversal both present")
    void aReversedDepositAgrees() {
        long beforeTheDeposit = chain.headBlockNumber();
        confirmTheDeposit();

        chain.rollBackTo(beforeTheDeposit);
        chain.mineEmpty(CONFIRMATIONS + 1);
        watcher.poll(chain);

        ReconciliationReport report = reconciler.run().orElseThrow();

        assertAll(
                () -> assertEquals(ObservationStatus.REVERSED,
                        observations.find(txHash, 0).orElseThrow().status()),
                () -> assertTrue(report.agreed(), () -> "unexpected: " + report.discrepancies()),
                () -> assertEquals(2, report.transfersChecked(), "the credit and its reversal"));
    }

    @Test
    @DisplayName("a confirmed deposit with no transfer behind it is money owed to somebody")
    void aConfirmedDepositThatNeverMovedMoney() {
        recordObservationDirectly(ObservationStatus.CONFIRMED);

        ReconciliationReport report = reconciler.run().orElseThrow();

        assertAll(
                () -> assertEquals(1, report.discrepancies().size(),
                        () -> "exactly one thing is wrong: " + report.discrepancies()),
                () -> assertEquals(DiscrepancyKind.CONFIRMED_DEPOSIT_NOT_CREDITED,
                        report.discrepancies().getFirst().kind()),
                () -> assertEquals(txHash + ":0", report.discrepancies().getFirst().subject()),
                () -> assertEquals(AMOUNT,
                        report.discrepancies().getFirst().expectedAmount().orElseThrow()),
                () -> assertEquals(Money.zero("EURC"),
                        report.discrepancies().getFirst().foundAmount().orElseThrow()));
    }

    @Test
    @DisplayName("a deposit marked reversed with nothing reversing it is caught")
    void aReversalThatNeverHappened() {
        confirmTheDeposit();

        // The watcher's own reversal path writes the transfer and the status change in one
        // transaction, so this state cannot arise through it. It can arise from a restore,
        // a partial replay or a hand-edited row, which is what reconciliation is for.
        markObservationReversedWithoutReversingIt();

        ReconciliationReport report = reconciler.run().orElseThrow();

        // Two findings, and the second one is the point: the per-deposit check and the
        // aggregate check reach the same conclusion from opposite ends of the data. The
        // chain account is still down the money it lent to the escrow, while the chain
        // now claims to have confirmed nothing, so the total no longer matches either.
        assertAll(
                () -> assertEquals(2, report.discrepancies().size(),
                        () -> "unexpected: " + report.discrepancies()),
                () -> assertEquals(1, report.of(DiscrepancyKind.REVERSAL_NOT_RECORDED).size()),
                () -> assertEquals(txHash + ":0",
                        report.of(DiscrepancyKind.REVERSAL_NOT_RECORDED).getFirst().subject()),
                () -> assertEquals(1, report.of(DiscrepancyKind.CHAIN_ACCOUNT_DISAGREES).size()));
    }

    @Test
    @DisplayName("money credited under a deposit key the chain never sent is caught")
    void aCreditWithNoDepositBehindIt() {
        AccountId source = openAccount("house-" + run, Account.Kind.HOUSE);
        AccountId destination = openAccount("customer-" + run, Account.Kind.CUSTOMER);
        transfers.transfer(
                source, destination, AMOUNT, SettlementKeys.chainDeposit("0xnever" + run, 0));

        ReconciliationReport report = reconciler.run().orElseThrow();

        assertAll(
                () -> assertEquals(1, report.discrepancies().size(),
                        () -> "exactly one thing is wrong: " + report.discrepancies()),
                () -> assertEquals(DiscrepancyKind.CREDIT_WITHOUT_CONFIRMATION,
                        report.discrepancies().getFirst().kind()),
                () -> assertEquals(AMOUNT,
                        report.discrepancies().getFirst().foundAmount().orElseThrow()));
    }

    @Test
    @DisplayName("an entry added behind the service's back unbalances its transfer and its book")
    void aStrayEntryIsCaughtTwice() {
        confirmTheDeposit();
        UUID transferId = transferIdOf(SettlementKeys.chainDeposit(txHash, 0));

        dsl.insertInto(ENTRY)
                .set(ENTRY.ID, UUID.randomUUID())
                .set(ENTRY.TRANSFER_ID, transferId)
                .set(ENTRY.ACCOUNT_ID, InvoiceSettlement.escrowAccountFor(invoiceId).value())
                .set(ENTRY.AMOUNT_MINOR, 1L)
                .set(ENTRY.CURRENCY, "EURC")
                .set(ENTRY.RECORDED_AT, LocalDateTime.now(ZoneOffset.UTC))
                .execute();

        ReconciliationReport report = reconciler.run().orElseThrow();

        assertAll(
                () -> assertEquals(1, report.of(DiscrepancyKind.TRANSFER_NOT_BALANCED).size(),
                        "the transfer now has three entries"),
                () -> assertEquals(1, report.of(DiscrepancyKind.ENTRIES_DO_NOT_SUM_TO_ZERO).size(),
                        "and the EURC book is a cent up on itself"),
                () -> assertEquals(Money.of(1L, "EURC"),
                        report.of(DiscrepancyKind.ENTRIES_DO_NOT_SUM_TO_ZERO).getFirst()
                                .foundAmount().orElseThrow()));
    }

    @Test
    @DisplayName("a customer account driven negative by a write that skipped the rules is caught")
    void anOverdrawnCustomerAccountIsCaught() {
        AccountId customer = openAccount("customer-" + run, Account.Kind.CUSTOMER);
        AccountId house = openAccount("house-" + run, Account.Kind.HOUSE);

        // A balanced pair, written straight to the tables: the books still add up, and a
        // customer is holding less than nothing. No check on totals alone would see it.
        postPairDirectly(customer, house, Money.of(500L, "EURC"));

        ReconciliationReport report = reconciler.run().orElseThrow();

        assertAll(
                () -> assertEquals(1, report.discrepancies().size(),
                        () -> "exactly one thing is wrong: " + report.discrepancies()),
                () -> assertEquals(DiscrepancyKind.CUSTOMER_ACCOUNT_OVERDRAWN,
                        report.discrepancies().getFirst().kind()),
                () -> assertEquals(customer.value(),
                        report.discrepancies().getFirst().subject()),
                () -> assertEquals(Money.of(-500L, "EURC"),
                        report.discrepancies().getFirst().foundAmount().orElseThrow()));
    }

    @Test
    @DisplayName("money leaving the chain account by any other route breaks the aggregate check")
    void theChainAccountIsCheckedAgainstTheChainItself() {
        confirmTheDeposit();

        // Every per-deposit check still passes: this transfer carries no deposit key and
        // its entries balance. Only the total the platform claims to be holding on chain
        // has moved, and only the aggregate check looks at that.
        AccountId sink = openAccount("house-" + run, Account.Kind.HOUSE);
        transfers.transfer(
                InvoiceSettlement.chainAccountFor("EURC"), sink,
                Money.of(1_000L, "EURC"), "manual-correction-" + run);

        ReconciliationReport report = reconciler.run().orElseThrow();

        List<Discrepancy> found = report.of(DiscrepancyKind.CHAIN_ACCOUNT_DISAGREES);
        assertAll(
                () -> assertEquals(1, report.discrepancies().size(),
                        () -> "exactly one thing is wrong: " + report.discrepancies()),
                () -> assertEquals(1, found.size()),
                () -> assertEquals(Money.of(-AMOUNT.minorUnits(), "EURC"),
                        found.getFirst().expectedAmount().orElseThrow()),
                () -> assertEquals(Money.of(-AMOUNT.minorUnits() - 1_000L, "EURC"),
                        found.getFirst().foundAmount().orElseThrow()));
    }

    @Test
    @DisplayName("the run and its findings are stored, and read back as they were found")
    void theReportIsEvidenceAfterTheFact() {
        recordObservationDirectly(ObservationStatus.CONFIRMED);
        ReconciliationReport asRun = reconciler.run().orElseThrow();

        ReconciliationReport asStored = runs.latest().orElseThrow();

        assertAll(
                () -> assertEquals(asRun.runId(), asStored.runId()),
                () -> assertEquals(asRun.observationsChecked(), asStored.observationsChecked()),
                () -> assertEquals(asRun.transfersChecked(), asStored.transfersChecked()),
                () -> assertEquals(asRun.discrepancies(), asStored.discrepancies()),
                () -> assertEquals(asRun.runId(), runs.find(asRun.runId()).orElseThrow().runId()));
    }

    @Test
    @DisplayName("a clean run is worth storing too")
    void aCleanRunIsStored() {
        confirmTheDeposit();
        reconciler.run().orElseThrow();

        ReconciliationReport stored = runs.latest().orElseThrow();

        assertAll(
                () -> assertTrue(stored.agreed()),
                () -> assertEquals(1, stored.observationsChecked(),
                        "the counts are what make a clean verdict mean anything"));
    }

    @Test
    @DisplayName("a settlement committing mid-run cannot make the reconciler cry wolf")
    void theWholeRunSeesOneSnapshot() {
        confirmTheDeposit();

        String secondInvoice = "inv2-" + run;
        openInvoiceAwaitingItsEscrow(secondInvoice);
        chain.mineDeposit("0xtx2" + run, secondInvoice, AMOUNT);
        chain.mineEmpty(CONFIRMATIONS);

        try (Database other = new Database(target.url(), target.username(), target.password())) {
            EscrowWatcher concurrent = watcherOn(other);
            AtomicBoolean raced = new AtomicBoolean();

            // The second deposit is committed from another connection at the worst moment
            // there is: after the reconciler has summed what the chain confirmed and
            // before it reads the chain account those deposits are supposed to match.
            // Under read committed those two statements would straddle the commit, and
            // the run would report a chain account short by exactly one deposit that had
            // in fact been credited correctly.
            Configuration racy = dsl.configuration().derive(new DefaultExecuteListenerProvider(
                    new ExecuteListener() {
                        @Override
                        public void end(ExecuteContext ctx) {
                            String sql = ctx.sql();
                            if (sql != null
                                    && sql.contains("sum(")
                                    && sql.contains("chain_observation")
                                    && raced.compareAndSet(false, true)) {
                                concurrent.poll(chain);
                            }
                        }
                    }));

            ReconciliationReport underTheRace =
                    new Reconciler(DSL.using(racy), Clock.systemUTC(), runs).run().orElseThrow();

            assertAll(
                    () -> assertTrue(raced.get(),
                            "nothing was committed mid-run, so this test proved nothing"),
                    () -> assertTrue(underTheRace.agreed(),
                            () -> "unexpected: " + underTheRace.discrepancies()),
                    () -> assertEquals(1, underTheRace.observationsChecked(),
                            "the run answered for the book as it stood when it started"),
                    // Deep, because the question is about the book rather than about the
                    // window. An ordinary run here would follow on from the one above and
                    // count only the deposit that raced it, which is right and answers
                    // something else.
                    () -> assertEquals(2, reconciler.runFully().orElseThrow().observationsChecked(),
                            "and the deposit that raced it really had committed"));
        }
    }

    @Test
    @DisplayName("a contract holding what the ledger credited reconciles, and says it asked")
    void theChainBacksWhatWasCredited() {
        confirmTheDeposit();

        ReconciliationReport report = reconcilerAskingTheChain.run().orElseThrow();

        assertAll(
                () -> assertTrue(report.agreed(), () -> "unexpected: " + report.discrepancies()),
                () -> assertTrue(report.reservesChecked(),
                        "a clean report that never asked the chain is not the same report"));
    }

    @Test
    @DisplayName("a contract holding less than the ledger credited is a shortfall")
    void aContractShortOfWhatWasCreditedIsCaught() {
        confirmTheDeposit();

        // The money leaves the contract without an event, which is what a watcher that
        // misread the chain looks like from the outside. Every other check still passes,
        // because the watcher wrote both sides of what they compare.
        chain.adjustHeldOnChain(Money.of(-1_000L, "EURC"));

        ReconciliationReport report = reconcilerAskingTheChain.run().orElseThrow();

        assertAll(
                () -> assertEquals(1, report.discrepancies().size(),
                        () -> "exactly one thing is wrong: " + report.discrepancies()),
                () -> assertEquals(DiscrepancyKind.RESERVES_SHORT,
                        report.discrepancies().getFirst().kind()),
                () -> assertEquals(AMOUNT,
                        report.discrepancies().getFirst().expectedAmount().orElseThrow()),
                () -> assertEquals(Money.of(AMOUNT.minorUnits() - 1_000L, "EURC"),
                        report.discrepancies().getFirst().foundAmount().orElseThrow()));
    }

    @Test
    @DisplayName("a deposit the watcher has not read yet is not a surplus")
    void aDepositAboveTheWatchersCursorIsNotASurplus() {
        // Mined, and the watcher has not polled. The money is in the contract and there is
        // no observation at all, not even a pending one, so nothing on our side explains
        // it. Reading the balance at the head reports the poll interval as an unaccounted
        // surplus; reading it at the height the watcher has actually reached leaves the
        // deposit out of both sides.
        //
        // Found by soaking the running service rather than by reasoning: the reserve check
        // asked the chain for its head while every record it compared against described
        // the cursor, and the two horizons differed by exactly one poll.
        chain.mineDeposit(txHash, invoiceId, AMOUNT);
        chain.mineEmpty(CONFIRMATIONS);

        ReconciliationReport beforeAnyPoll = reconcilerAskingTheChain.run().orElseThrow();

        // Once the watcher has read that far, the same deposit reconciles as it always did,
        // and only now may the report claim the reserves were checked at all.
        watcher.poll(chain);
        ReconciliationReport afterThePoll = reconcilerAskingTheChain.runFully().orElseThrow();

        assertAll(
                () -> assertTrue(beforeAnyPoll.agreed(),
                        () -> "the poll interval was reported as a discrepancy: "
                                + beforeAnyPoll.discrepancies()),
                () -> assertFalse(beforeAnyPoll.reservesChecked(),
                        "a watcher that has read nothing gives the chain no height to be "
                                + "asked about, and an unchecked run must say so"),
                () -> assertTrue(afterThePoll.agreed(),
                        () -> "unexpected after the watcher caught up: "
                                + afterThePoll.discrepancies()),
                () -> assertTrue(afterThePoll.reservesChecked(),
                        "and now it really did ask the chain"));
    }

    @Test
    @DisplayName("a deposit still waiting for its confirmations explains a surplus")
    void moneyWaitingToBeCreditedIsNotASurplus() {
        confirmTheDeposit();

        // On chain and in the contract, deliberately not credited yet. Without counting
        // it, the contract would look like it was holding money nobody could explain.
        String secondInvoice = "inv2-" + run;
        openInvoiceAwaitingItsEscrow(secondInvoice);
        chain.mineDeposit("0xtx2" + run, secondInvoice, AMOUNT);
        watcher.poll(chain);

        ReconciliationReport report = reconcilerAskingTheChain.run().orElseThrow();

        assertAll(
                () -> assertEquals(ObservationStatus.PENDING,
                        observations.find("0xtx2" + run, 0).orElseThrow().status()),
                () -> assertTrue(report.agreed(), () -> "unexpected: " + report.discrepancies()));
    }

    @Test
    @DisplayName("money in the contract that nothing explains is raised as a question")
    void anUnexplainedSurplusIsRaised() {
        confirmTheDeposit();

        // Somebody sent tokens straight to the contract address, or the watcher missed an
        // event. The second is a failure no other check in here can see.
        chain.adjustHeldOnChain(Money.of(5_000L, "EURC"));

        ReconciliationReport report = reconcilerAskingTheChain.run().orElseThrow();

        assertAll(
                () -> assertEquals(1, report.discrepancies().size(),
                        () -> "exactly one thing is wrong: " + report.discrepancies()),
                () -> assertEquals(DiscrepancyKind.RESERVES_UNACCOUNTED,
                        report.discrepancies().getFirst().kind()),
                () -> assertEquals(Money.of(AMOUNT.minorUnits() + 5_000L, "EURC"),
                        report.discrepancies().getFirst().foundAmount().orElseThrow()));
    }

    @Test
    @DisplayName("with no chain to ask, the report says so rather than reading as verified")
    void anUncheckedRunDoesNotPassItselfOffAsAVerifiedOne() {
        confirmTheDeposit();
        chain.adjustHeldOnChain(Money.of(-1_000L, "EURC"));

        // The same shortfall the previous test catches. This reconciler has no chain, so
        // it cannot see it, and the only thing standing between that and a report an
        // operator would read as proof is the flag.
        ReconciliationReport report = reconciler.run().orElseThrow();

        assertAll(
                () -> assertTrue(report.agreed(),
                        "it genuinely agrees on everything it is able to look at"),
                () -> assertFalse(report.reservesChecked(),
                        "and the report has to admit what it did not look at"));
    }

    @Test
    @DisplayName("a second instance finds the lease taken and does not scan the book again")
    void onlyOneInstanceReconcilesAtATime() {
        confirmTheDeposit();

        try (Database other = new Database(target.url(), target.username(), target.password())) {
            Reconciler otherInstance =
                    new Reconciler(other.dsl(), Clock.systemUTC(),
                            new PostgresReconciliationRuns(other.dsl()));

            // Hold the lease the way a run in progress holds it, on a connection of its
            // own, and ask a second instance to reconcile underneath it.
            dsl.transaction(config -> {
                DSL.using(config).execute(
                        "select pg_advisory_xact_lock(?)", Reconciler.LEASE_KEY);

                assertTrue(otherInstance.run().isEmpty(),
                        "the second instance should have found the lease taken");
            });

            assertAll(
                    () -> assertTrue(runs.latest().isEmpty(),
                            "and written no report, rather than a duplicate of somebody else's"),
                    () -> assertTrue(otherInstance.run().isPresent(),
                            "once the lease is free it reconciles normally"));
        }
    }

    /**
     * The half of reconciliation that decides what not to look at.
     *
     * <p>A windowed reconciler is easy to write and easy to get quietly wrong, because
     * every mistake it makes is an omission, and an omission produces a clean report.
     * These tests all have the same shape: make something wrong, then make sure the run
     * that was entitled to skip it does not.
     */
    @Nested
    @DisplayName("reconciling from a watermark")
    class FromAWatermark {

        @Test
        @DisplayName("the first run is deep, and the next one continues where it stopped")
        void theWindowsTileTheHistory() {
            ReconciliationReport first = reconciler.run().orElseThrow();
            ReconciliationReport second = reconciler.run().orElseThrow();

            assertAll(
                    () -> assertEquals(RunMode.FULL, first.mode(),
                            "there was nothing to carry forward, so there was nothing to skip"),
                    () -> assertEquals(0L, first.range().from(),
                            "a deep run starts below any transaction Postgres has issued"),
                    () -> assertEquals(RunMode.INCREMENTAL, second.mode()),
                    () -> assertEquals(first.range().to(), second.range().from(),
                            "the windows meet exactly: no row examined twice, and none skipped"));
        }

        @Test
        @DisplayName("a deep run comes round again once the last one has aged out")
        void theDeepRunComesRoundAgain() {
            Reconciler impatient =
                    new Reconciler(dsl, Clock.systemUTC(), runs, null, Duration.ZERO);

            impatient.run().orElseThrow();

            assertEquals(RunMode.FULL, impatient.run().orElseThrow().mode(),
                    "with no tolerance for a stale full pass, every run re-derives the book");
        }

        @Test
        @DisplayName("a book that was already wrong stays wrong in the eyes of an empty window")
        void damageBelowTheWatermarkIsStillReported() {
            AccountId customer = openAccount("customer-" + run, Account.Kind.CUSTOMER);
            AccountId house = openAccount("house-" + run, Account.Kind.HOUSE);
            UUID transferId = postPairDirectly(house, customer, Money.of(500L, "EURC"));
            insertEntry(dsl, transferId, customer, Money.of(1L, "EURC"),
                    LocalDateTime.now(ZoneOffset.UTC));

            ReconciliationReport deep = reconciler.run().orElseThrow();
            ReconciliationReport windowed = reconciler.run().orElseThrow();

            assertAll(
                    () -> assertEquals(1,
                            deep.of(DiscrepancyKind.ENTRIES_DO_NOT_SUM_TO_ZERO).size()),
                    () -> assertEquals(1, deep.of(DiscrepancyKind.TRANSFER_NOT_BALANCED).size()),
                    () -> assertEquals(1,
                            windowed.of(DiscrepancyKind.ENTRIES_DO_NOT_SUM_TO_ZERO).size(),
                            "the net is carried forward rather than re-queried, so it is still "
                                    + "the whole book that does not balance"),
                    () -> assertEquals(Money.of(1L, "EURC"),
                            windowed.of(DiscrepancyKind.ENTRIES_DO_NOT_SUM_TO_ZERO).getFirst()
                                    .foundAmount().orElseThrow()),
                    () -> assertEquals(0, windowed.of(DiscrepancyKind.TRANSFER_NOT_BALANCED).size(),
                            "and the check that names one transfer has no new transfer to name"));
        }

        @Test
        @DisplayName("an entry added after a clean run pulls its whole transfer back into view")
        void aStrayEntryAddedLaterIsCaughtWithItsSiblings() {
            AccountId customer = openAccount("customer-" + run, Account.Kind.CUSTOMER);
            AccountId house = openAccount("house-" + run, Account.Kind.HOUSE);
            UUID transferId = postPairDirectly(house, customer, Money.of(500L, "EURC"));

            assertTrue(reconciler.run().orElseThrow().agreed(), "the book starts sound");

            insertEntry(dsl, transferId, customer, Money.of(1L, "EURC"),
                    LocalDateTime.now(ZoneOffset.UTC));
            ReconciliationReport windowed = reconciler.run().orElseThrow();

            assertAll(
                    () -> assertEquals(RunMode.INCREMENTAL, windowed.mode()),
                    () -> assertEquals(1,
                            windowed.of(DiscrepancyKind.TRANSFER_NOT_BALANCED).size()),
                    () -> assertTrue(windowed.of(DiscrepancyKind.TRANSFER_NOT_BALANCED).getFirst()
                                    .detail().startsWith("3 entries"),
                            "the window chose the transfer and the whole group was then read, "
                                    + "or the finding would have said one entry"));
        }

        @Test
        @DisplayName("an account driven negative after a clean run is caught by the window")
        void anAccountOverdrawnLaterIsCaught() {
            AccountId customer = openAccount("customer-" + run, Account.Kind.CUSTOMER);
            AccountId house = openAccount("house-" + run, Account.Kind.HOUSE);

            assertTrue(reconciler.run().orElseThrow().agreed(), "nothing has moved yet");

            postPairDirectly(customer, house, Money.of(500L, "EURC"));
            ReconciliationReport windowed = reconciler.run().orElseThrow();

            assertAll(
                    () -> assertEquals(1, windowed.discrepancies().size(),
                            () -> "exactly one thing is wrong: " + windowed.discrepancies()),
                    () -> assertEquals(DiscrepancyKind.CUSTOMER_ACCOUNT_OVERDRAWN,
                            windowed.discrepancies().getFirst().kind()),
                    () -> assertEquals(customer.value(),
                            windowed.discrepancies().getFirst().subject()));
        }

        @Test
        @DisplayName("a deposit reversed after it was checked is examined again")
        void aChangedObservationFallsBackIntoTheWindow() {
            confirmTheDeposit();

            assertTrue(reconciler.run().orElseThrow().agreed(),
                    "the deposit was credited properly, so the first run has nothing to say");

            // The chain changes its mind and nothing in the ledger undoes it. The row sits
            // below the watermark and would be skipped for ever, except that changing it
            // restamps it with the transaction that did.
            markObservationReversedWithoutReversingIt();
            ReconciliationReport windowed = reconciler.run().orElseThrow();

            assertAll(
                    () -> assertEquals(RunMode.INCREMENTAL, windowed.mode()),
                    () -> assertEquals(1,
                            windowed.of(DiscrepancyKind.REVERSAL_NOT_RECORDED).size(),
                            () -> "the restamped observation was skipped: "
                                    + windowed.discrepancies()),
                    () -> assertEquals(1, windowed.observationsChecked(),
                            "and it is the one deposit the window contained"));
        }

        @Test
        @DisplayName("a carried total the entries do not support is itself a finding")
        void aCarriedTotalIsCheckedAgainstTheEntries() {
            AccountId customer = openAccount("customer-" + run, Account.Kind.CUSTOMER);
            AccountId house = openAccount("house-" + run, Account.Kind.HOUSE);
            postPairDirectly(house, customer, Money.of(500L, "EURC"));

            ReconciliationReport honest = reconciler.run().orElseThrow();

            // A run that wrote down totals the book cannot justify. Every incremental run
            // after it would add its own window to these and agree with itself for ever.
            UUID impostor = UUID.randomUUID();
            recordRun(impostor, honest.range().to());
            claim(impostor, "EURC", 999L, 2L);
            claim(impostor, "USDC", 0L, 3L);

            ReconciliationReport deep = reconciler.runFully().orElseThrow();
            List<Discrepancy> drift = deep.of(DiscrepancyKind.CHECKPOINT_DRIFT);

            assertAll(
                    () -> assertEquals(2, drift.size(),
                            () -> "both claims were false: " + deep.discrepancies()),
                    () -> assertEquals(List.of("EURC", "USDC"),
                            drift.stream().map(Discrepancy::subject).toList()),
                    () -> assertEquals(Money.of(999L, "EURC"),
                            drift.getFirst().foundAmount().orElseThrow(),
                            "the carried figure is what was found, not what was expected"),
                    () -> assertEquals(Money.zero("EURC"),
                            drift.getFirst().expectedAmount().orElseThrow(),
                            "the entries are the authority"),
                    () -> assertTrue(drift.get(1).detail().contains("3 entries"),
                            () -> "a net can be right while the count behind it is not: "
                                    + drift.get(1).detail()));
        }

        @Test
        @DisplayName("a transaction that commits out of order is not passed over")
        void aLateCommitIsNotLost() throws Exception {
            AccountId customer = openAccount("customer-" + run, Account.Kind.CUSTOMER);
            AccountId house = openAccount("house-" + run, Account.Kind.HOUSE);
            UUID transferId = postPairDirectly(house, customer, Money.of(500L, "EURC"));

            UUID strayId = UUID.randomUUID();
            UUID overtakingId;
            ReconciliationReport first;

            // The row every watermark built on an id or a timestamp loses. It is written
            // first and commits last, and a run that has already seen the transaction that
            // overtook it would mark itself past both. Here the mark is xmin, and this open
            // transaction is what holds xmin down, so the run stops short of its own
            // visible rows rather than stepping over an invisible one.
            try (Connection late = DriverManager.getConnection(
                    target.url(), target.username(), target.password())) {
                late.setAutoCommit(false);
                writeStrayEntry(late, strayId, transferId, customer);

                // Started after the stray entry and committed before it, which is the whole
                // difficulty: by every measure the row itself chose, this one is newer.
                overtakingId = postPairDirectly(house, customer, Money.of(200L, "EURC"));

                first = reconciler.run().orElseThrow();
                late.commit();
            }

            ReconciliationReport second = reconciler.run().orElseThrow();

            long strayXid = xidOfEntry(strayId);
            long overtakingXid = xidOfTransfer(overtakingId);
            long firstStoppedAt = first.range().to();

            assertAll(
                    () -> assertTrue(strayXid < overtakingXid,
                            "the test proves nothing unless the later commit really is older"),
                    () -> assertTrue(firstStoppedAt <= strayXid,
                            "the run must stop below the transaction still in flight, even "
                                    + "though it could already see a newer one"),
                    () -> assertTrue(first.agreed(),
                            () -> "nothing in that window was wrong: " + first.discrepancies()),
                    () -> assertEquals(1, first.transfersChecked(),
                            "the overtaking transfer was visible and still outside the window"),
                    () -> assertEquals(firstStoppedAt, second.range().from()),
                    () -> assertTrue(overtakingXid >= firstStoppedAt
                                    && overtakingXid < second.range().to(),
                            "and the next window picks up the transfer it deferred"),
                    () -> assertEquals(1, second.transfersChecked(),
                            "which is that transfer and no other"),
                    () -> assertEquals(1,
                            second.of(DiscrepancyKind.ENTRIES_DO_NOT_SUM_TO_ZERO).size(),
                            () -> "the late commit was skipped: " + second.discrepancies()),
                    () -> assertEquals(Money.of(1L, "EURC"),
                            second.of(DiscrepancyKind.ENTRIES_DO_NOT_SUM_TO_ZERO).getFirst()
                                    .foundAmount().orElseThrow()));
        }

        private long xidOfEntry(UUID entryId) {
            return dsl.select(ENTRY.XID).from(ENTRY).where(ENTRY.ID.eq(entryId))
                    .fetchOne(ENTRY.XID);
        }

        private long xidOfTransfer(UUID transferId) {
            return dsl.select(TRANSFER.XID).from(TRANSFER).where(TRANSFER.ID.eq(transferId))
                    .fetchOne(TRANSFER.XID);
        }

        private void recordRun(UUID runId, long checkedTo) {
            dsl.insertInto(RECONCILIATION_RUN)
                    .set(RECONCILIATION_RUN.ID, runId)
                    // A second later, so this is the predecessor the next run reads.
                    .set(RECONCILIATION_RUN.RAN_AT,
                            LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1))
                    .set(RECONCILIATION_RUN.MODE, RunMode.INCREMENTAL.name())
                    .set(RECONCILIATION_RUN.CHECKED_FROM, 0L)
                    .set(RECONCILIATION_RUN.CHECKED_TO, checkedTo)
                    .set(RECONCILIATION_RUN.OBSERVATIONS_CHECKED, 0)
                    .set(RECONCILIATION_RUN.TRANSFERS_CHECKED, 0)
                    .set(RECONCILIATION_RUN.DISCREPANCY_COUNT, 0)
                    .execute();
        }

        private void claim(UUID runId, String currency, long netMinor, long entries) {
            dsl.insertInto(RECONCILIATION_CHECKPOINT)
                    .set(RECONCILIATION_CHECKPOINT.RUN_ID, runId)
                    .set(RECONCILIATION_CHECKPOINT.CURRENCY, currency)
                    .set(RECONCILIATION_CHECKPOINT.NET_MINOR, netMinor)
                    .set(RECONCILIATION_CHECKPOINT.ENTRIES_COUNTED, entries)
                    .execute();
        }

        /** Raw JDBC, because the point is to hold the transaction open across a run. */
        private void writeStrayEntry(
                Connection connection, UUID entryId, UUID transferId, AccountId account)
                throws Exception {

            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into entry (id, transfer_id, account_id, amount_minor, currency, "
                            + "recorded_at) values (?, ?, ?, ?, ?, ?)")) {
                insert.setObject(1, entryId);
                insert.setObject(2, transferId);
                insert.setString(3, account.value());
                insert.setLong(4, 1L);
                insert.setString(5, "EURC");
                insert.setTimestamp(6, Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC)));
                insert.executeUpdate();
            }
        }
    }

    /**
     * "What is wrong now", which is not a question any single report answers.
     *
     * <p>The reason this exists at all is the windowed run: it reports a fault once and
     * then moves past it, so a fault nobody fixed stops appearing and a clean latest
     * report stops meaning a clean book. Every test here is about that difference.
     */
    @Nested
    @DisplayName("what is still open")
    class StillOpen {

        @Test
        @DisplayName("nothing reconciled yet is not the same answer as nothing wrong")
        void anUnreconciledBookHasNoAnswer() {
            assertTrue(runs.openFindings().isEmpty(),
                    "a service whose reconciler has never run must not report a clean book");
        }

        @Test
        @DisplayName("a fault a deep run found is open, and dated from when it found it")
        void aFaultTheDeepRunFoundIsOpen() {
            AccountId customer = openAccount("customer-" + run, Account.Kind.CUSTOMER);
            AccountId house = openAccount("house-" + run, Account.Kind.HOUSE);
            postPairDirectly(customer, house, Money.of(500L, "EURC"));

            ReconciliationReport deep = reconciler.run().orElseThrow();
            OpenFindings open = runs.openFindings().orElseThrow();

            assertAll(
                    () -> assertEquals(1, open.findings().size(),
                            () -> "expected the overdraw: " + open.findings()),
                    () -> assertEquals(DiscrepancyKind.CUSTOMER_ACCOUNT_OVERDRAWN,
                            open.findings().getFirst().kind()),
                    () -> assertEquals(customer.value(), open.findings().getFirst().subject()),
                    () -> assertEquals(deep.runId(), open.sinceRun(),
                            "anchored on the run that looked everywhere"),
                    () -> assertEquals(deep.runId(), open.findings().getFirst().firstSeenRun()),
                    () -> assertFalse(open.allClear()));
        }

        /**
         * The whole point. A windowed run finds something, the next windowed run has no
         * reason to look at it again, and the fault is still there.
         */
        @Test
        @DisplayName("a fault a window found stays open after later windows pass it by")
        void aFaultFoundInAWindowStaysOpen() {
            AccountId customer = openAccount("customer-" + run, Account.Kind.CUSTOMER);
            AccountId house = openAccount("house-" + run, Account.Kind.HOUSE);

            assertTrue(reconciler.run().orElseThrow().agreed(), "the book starts sound");

            postPairDirectly(customer, house, Money.of(500L, "EURC"));
            ReconciliationReport found = reconciler.run().orElseThrow();
            ReconciliationReport quiet = reconciler.run().orElseThrow();

            OpenFindings open = runs.openFindings().orElseThrow();

            assertAll(
                    () -> assertEquals(1, found.discrepancies().size(),
                            "the window that contained the damage reported it"),
                    () -> assertTrue(quiet.agreed(),
                            "and the next window had no reason to look at it again"),
                    () -> assertEquals(1, open.findings().size(),
                            () -> "so the report went quiet and the fault did not: "
                                    + open.findings()),
                    () -> assertEquals(found.runId(),
                            open.findings().getFirst().lastSeenRun(),
                            "last seen by the run that found it, not by the quiet one"));
        }

        @Test
        @DisplayName("a fault seen by several runs is one finding, counted")
        void repeatedSightingsAreOneFinding() {
            AccountId customer = openAccount("customer-" + run, Account.Kind.CUSTOMER);
            AccountId house = openAccount("house-" + run, Account.Kind.HOUSE);
            postPairDirectly(customer, house, Money.of(500L, "EURC"));

            ReconciliationReport first = reconciler.runFully().orElseThrow();
            ReconciliationReport second = reconciler.runFully().orElseThrow();

            OpenFindings open = runs.openFindings().orElseThrow();

            assertAll(
                    () -> assertEquals(1, open.findings().size(),
                            "one account is overdrawn, however many runs say so"),
                    () -> assertEquals(1, open.findings().getFirst().timesReported(),
                            "and the count is of runs since the anchor, which is the "
                                    + "second one"),
                    () -> assertEquals(second.runId(), open.sinceRun(),
                            "the anchor moved to the newer deep run"),
                    () -> assertNotEquals(first.runId(), open.sinceRun()));
        }

        @Test
        @DisplayName("a fault a deep run no longer finds is closed")
        void aFixedFaultDropsOut() {
            AccountId customer = openAccount("customer-" + run, Account.Kind.CUSTOMER);
            AccountId house = openAccount("house-" + run, Account.Kind.HOUSE);
            postPairDirectly(customer, house, Money.of(500L, "EURC"));

            reconciler.run().orElseThrow();
            assertEquals(1, runs.openFindings().orElseThrow().findings().size());

            // Put the money back, the only way this ledger allows: a new pair in the
            // opposite direction, never an edit to the one that caused it.
            postPairDirectly(house, customer, Money.of(500L, "EURC"));
            reconciler.runFully().orElseThrow();

            OpenFindings open = runs.openFindings().orElseThrow();

            assertAll(
                    () -> assertTrue(open.allClear(),
                            () -> "a pass that looked everywhere did not find it: "
                                    + open.findings()),
                    () -> assertEquals(0, open.findings().size()));
        }

        @Test
        @DisplayName("only a deep run closes anything, because only it looked everywhere")
        void aWindowCannotCloseWhatItDidNotLookAt() {
            AccountId customer = openAccount("customer-" + run, Account.Kind.CUSTOMER);
            AccountId house = openAccount("house-" + run, Account.Kind.HOUSE);
            postPairDirectly(customer, house, Money.of(500L, "EURC"));

            reconciler.run().orElseThrow();
            postPairDirectly(house, customer, Money.of(500L, "EURC"));

            // Windowed, and the window does contain the repair, so this run is clean.
            assertTrue(reconciler.run().orElseThrow().agreed());

            assertEquals(1, runs.openFindings().orElseThrow().findings().size(),
                    "the window agreeing is not evidence of a fix: it saw one account, "
                            + "and the anchor is still the run that found the fault");
        }
    }

    private void confirmTheDeposit() {
        chain.mineDeposit(txHash, invoiceId, AMOUNT);
        chain.mineEmpty(CONFIRMATIONS);
        watcher.poll(chain);
    }

    private void openInvoiceAwaitingItsEscrow(String id) {
        invoices.open(new Invoice(
                id, "PO-" + id, "seller-" + run, "buyer-" + run, AMOUNT, Clock.systemUTC().instant()));
        invoices.transition(id, InvoiceStatus.SUBMITTED, null, null);
        invoices.transition(id, InvoiceStatus.BUYER_ACCEPTED, null, null);
        invoices.transition(id, InvoiceStatus.ESCROW_PENDING, null, null);
    }

    /** A second watcher on a second pool, so its writes commit on a connection of their own. */
    private EscrowWatcher watcherOn(Database other) {
        Clock clock = Clock.systemUTC();
        PostgresLedger otherLedger = new PostgresLedger(other.dsl(), clock);
        PostgresInvoices otherInvoices = new PostgresInvoices(other.dsl(), clock);
        PostgresTransferService otherTransfers = new PostgresTransferService(other.dsl(), clock);
        return new EscrowWatcher(
                other.dsl(),
                new PostgresChainObservations(other.dsl(), clock),
                new InvoiceSettlement(other.dsl(), otherLedger, otherInvoices, otherTransfers),
                CONFIRMATIONS);
    }

    private AccountId openAccount(String id, Account.Kind kind) {
        AccountId accountId = AccountId.of(id);
        ledger.open(new Account(accountId, "EURC", kind));
        return accountId;
    }

    private void recordObservationDirectly(ObservationStatus status) {
        dsl.insertInto(CHAIN_OBSERVATION)
                .set(CHAIN_OBSERVATION.TX_HASH, txHash)
                .set(CHAIN_OBSERVATION.LOG_INDEX, 0)
                .set(CHAIN_OBSERVATION.BLOCK_NUMBER, 1L)
                .set(CHAIN_OBSERVATION.BLOCK_HASH, "0xblock" + run)
                .set(CHAIN_OBSERVATION.INVOICE_ID, invoiceId)
                .set(CHAIN_OBSERVATION.AMOUNT_MINOR, AMOUNT.minorUnits())
                .set(CHAIN_OBSERVATION.CURRENCY, AMOUNT.currency())
                .set(CHAIN_OBSERVATION.STATUS, status.name())
                .set(CHAIN_OBSERVATION.FIRST_SEEN, LocalDateTime.now(ZoneOffset.UTC))
                .execute();
    }

    private void markObservationReversedWithoutReversingIt() {
        // chain_observation is the one table the append-only triggers do not cover,
        // because the watcher legitimately moves a deposit's status as the chain changes
        // its mind. That is exactly why its status cannot be trusted on its own.
        dsl.update(CHAIN_OBSERVATION)
                .set(CHAIN_OBSERVATION.STATUS, ObservationStatus.REVERSED.name())
                .where(CHAIN_OBSERVATION.TX_HASH.eq(txHash))
                .execute();
    }

    private UUID transferIdOf(String idempotencyKey) {
        return dsl.select(TRANSFER.ID)
                .from(TRANSFER)
                .where(TRANSFER.IDEMPOTENCY_KEY.eq(idempotencyKey))
                .fetchOptional(TRANSFER.ID)
                .orElseThrow(() -> new IllegalStateException("no transfer under " + idempotencyKey));
    }

    private UUID postPairDirectly(AccountId from, AccountId to, Money amount) {
        UUID transferId = UUID.randomUUID();
        LocalDateTime at = LocalDateTime.now(ZoneOffset.UTC);

        dsl.transaction(config -> {
            DSLContext transaction = DSL.using(config);
            transaction.insertInto(TRANSFER)
                    .set(TRANSFER.ID, transferId)
                    .set(TRANSFER.IDEMPOTENCY_KEY, "bypassed-" + transferId)
                    .set(TRANSFER.FROM_ACCOUNT, from.value())
                    .set(TRANSFER.TO_ACCOUNT, to.value())
                    .set(TRANSFER.AMOUNT_MINOR, amount.minorUnits())
                    .set(TRANSFER.CURRENCY, amount.currency())
                    .set(TRANSFER.COMPLETED_AT, at)
                    .execute();

            insertEntry(transaction, transferId, from, amount.negated(), at);
            insertEntry(transaction, transferId, to, amount, at);
        });

        return transferId;
    }

    private static void insertEntry(
            DSLContext transaction, UUID transferId, AccountId account, Money amount,
            LocalDateTime at) {
        transaction.insertInto(ENTRY)
                .set(ENTRY.ID, UUID.randomUUID())
                .set(ENTRY.TRANSFER_ID, transferId)
                .set(ENTRY.ACCOUNT_ID, account.value())
                .set(ENTRY.AMOUNT_MINOR, amount.minorUnits())
                .set(ENTRY.CURRENCY, amount.currency())
                .set(ENTRY.RECORDED_AT, at)
                .execute();
    }
}
