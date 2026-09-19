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
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.settletrust.ledger.jooq.Tables.ENTRY;
import static com.settletrust.ledger.jooq.Tables.CHAIN_OBSERVATION;
import static com.settletrust.ledger.jooq.Tables.TRANSFER;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
                    () -> assertEquals(2, reconciler.run().orElseThrow().observationsChecked(),
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

    private void postPairDirectly(AccountId from, AccountId to, Money amount) {
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
