package com.settletrust.ledger.chain;

import com.settletrust.ledger.Database;
import com.settletrust.ledger.Money;
import com.settletrust.ledger.PostgresLedger;
import com.settletrust.ledger.PostgresTransferService;
import com.settletrust.ledger.TestDatabases;
import com.settletrust.ledger.invoice.Invoice;
import com.settletrust.ledger.invoice.InvoiceStatus;
import com.settletrust.ledger.invoice.PostgresInvoices;
import com.settletrust.ledger.reconciliation.PostgresReconciliationRuns;
import com.settletrust.ledger.reconciliation.ReconciliationReport;
import com.settletrust.ledger.reconciliation.Reconciler;
import com.settletrust.ledger.settlement.InvoiceSettlement;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole on-chain rail, end to end, with nothing faked.
 *
 * <p>Real contract, real node, real Postgres: money is deposited on chain, the watcher
 * finds it, the invoice is funded and the reconciler afterwards asks the token contract
 * whether the money is really there. Every other test of this rail substitutes something
 * for one of those, and each substitution is reasonable on its own; this is the one that
 * says the pieces fit together.
 *
 * <p>It is also the only test in the suite that produces a report with
 * {@code reservesChecked: true}. Everywhere else that flag is false and the README says
 * so, because no node was configured. Here one is.
 *
 * <p>Its own schema, for the reason the reconciliation tests have their own: a finding is
 * a statement about a whole database, so a leftover from another test would be part of
 * the answer.
 */
@EnabledIf("chainIsRunning")
class OnChainRailTest {

    private static final int CONFIRMATIONS = 3;
    private static final Money AMOUNT = Money.of(2_500_000L, "USDC");

    static boolean chainIsRunning() {
        return Anvil.available();
    }

    private TestDatabases.Target target;
    private Database database;
    private DSLContext dsl;
    private Anvil chain;

    private PostgresInvoices invoices;
    private EscrowWatcher watcher;
    private PostgresReconciliationRuns runs;

    private String token;
    private String escrow;
    private String buyer;
    private String seller;
    private String invoiceId;

    private EthereumChainSource deposits;
    private EscrowContractReserves reserves;

    @BeforeEach
    void openAnInvoiceAndAnEscrowForIt() {
        target = TestDatabases.isolated("chain");
        database = new Database(target.url(), target.username(), target.password());
        dsl = database.dsl();

        Clock clock = Clock.systemUTC();
        PostgresLedger ledger = new PostgresLedger(dsl, clock);
        invoices = new PostgresInvoices(dsl, clock);
        PostgresTransferService transfers = new PostgresTransferService(dsl, clock);
        InvoiceSettlement settlement = new InvoiceSettlement(dsl, ledger, invoices, transfers);
        watcher = new EscrowWatcher(
                dsl, new PostgresChainObservations(dsl, clock), settlement, CONFIRMATIONS);
        runs = new PostgresReconciliationRuns(dsl);

        chain = new Anvil();
        buyer = chain.account(1);
        seller = chain.account(2);
        token = chain.deploy("TestStablecoin", "");
        escrow = chain.deploy("InvoiceEscrow",
                Hex.addressWord(chain.account(0)) + Hex.addressWord(token));

        deposits = new EthereumChainSource(chain.rpc(), escrow, AMOUNT.currency());
        reserves = new EscrowContractReserves(chain.rpc(), token, escrow, AMOUNT.currency());

        // Short enough to fit the contract's bytes32, which is the constraint InvoiceRef
        // enforces and a realistic id has to live within.
        invoiceId = "inv-" + UUID.randomUUID().toString().substring(0, 8);
        openInvoiceAwaitingItsEscrow();
    }

    @AfterEach
    void hangUp() {
        if (chain != null) {
            chain.close();
        }
        if (database != null) {
            database.close();
        }
        if (target != null) {
            TestDatabases.drop(target);
        }
    }

    @Test
    @DisplayName("money paid on chain funds the invoice, once it is deep enough")
    void aRealDepositFundsARealInvoice() {
        fundOnChain();

        // Not buried yet, so the watcher has seen it and deliberately done nothing.
        EscrowWatcher.PollResult firstLook = watcher.poll(deposits);

        chain.mineEmpty(CONFIRMATIONS);
        EscrowWatcher.PollResult afterConfirmations = watcher.poll(deposits);

        assertAll(
                () -> assertEquals(InvoiceStatus.ESCROW_FUNDED,
                        invoices.find(invoiceId).orElseThrow().status(),
                        () -> "the invoice was not funded: " + afterConfirmations),
                () -> assertTrue(firstLook.confirmed() == 0,
                        "a deposit one block deep is not yet a deposit to act on"),
                () -> assertEquals(1, afterConfirmations.confirmed(),
                        "and once buried it is credited exactly once"));
    }

    @Test
    @DisplayName("a second pass over the same chain credits nothing twice")
    void pollingAgainChangesNothing() {
        fundOnChain();
        chain.mineEmpty(CONFIRMATIONS);
        watcher.poll(deposits);

        EscrowWatcher.PollResult again = watcher.poll(deposits);

        assertAll(
                () -> assertEquals(0, again.confirmed(),
                        "the chain hands the same log over on every scan, and the "
                                + "(hash, index) key is what stops it being paid twice"),
                () -> assertEquals(InvoiceStatus.ESCROW_FUNDED,
                        invoices.find(invoiceId).orElseThrow().status()));
    }

    @Test
    @DisplayName("the reconciler asks the token contract, and this time it really was asked")
    void theReconcilerVerifiesAgainstTheRealContract() {
        fundOnChain();
        chain.mineEmpty(CONFIRMATIONS);
        watcher.poll(deposits);

        ReconciliationReport report =
                new Reconciler(dsl, Clock.systemUTC(), runs, reserves).run().orElseThrow();

        assertAll(
                () -> assertTrue(report.agreed(),
                        () -> "the two rails disagree: " + report.discrepancies()),
                () -> assertTrue(report.reservesChecked(),
                        "and the run is entitled to say the money was verified on chain"),
                () -> assertEquals(1, report.observationsChecked()),
                () -> assertEquals(List.of(AMOUNT), reserves.heldOnChain(chain.headBlockNumber()),
                        "which is the amount the contract is actually holding"));
    }

    @Test
    @DisplayName("a payout the ledger has not noticed is caught, one poll after it happens")
    void aRealShortfallIsCaught() {
        fundOnChain();
        chain.mineEmpty(CONFIRMATIONS);
        watcher.poll(deposits);

        // The settler releases the escrow on chain while the ledger still believes the
        // money is held. That is what a premature or duplicated payout looks like from
        // here, and no check that compares our records against each other can see it.
        chain.release(escrow, invoiceId);

        // Not yet, and deliberately. The release is in a block above the watcher's cursor,
        // and the reserve check answers for the height the watcher has read. Reporting it
        // now would mean comparing a balance against records that do not describe the same
        // chain, which is the fault that made a poll interval look like a surplus.
        ReconciliationReport beforeTheWatcherCatchesUp =
                new Reconciler(dsl, Clock.systemUTC(), runs, reserves).run().orElseThrow();

        // One poll later the cursor is past the release, and both sides describe it.
        watcher.poll(deposits);
        ReconciliationReport report =
                new Reconciler(dsl, Clock.systemUTC(), runs, reserves).runFully().orElseThrow();

        assertAll(
                () -> assertTrue(beforeTheWatcherCatchesUp.agreed(),
                        () -> "a payout the watcher has not read is not yet a finding: "
                                + beforeTheWatcherCatchesUp.discrepancies()),
                () -> assertEquals(1, report.discrepancies().size(),
                        () -> "expected exactly the shortfall: " + report.discrepancies()),
                () -> assertEquals("RESERVES_SHORT",
                        report.discrepancies().getFirst().kind().name()),
                () -> assertEquals(Money.zero(AMOUNT.currency()),
                        report.discrepancies().getFirst().foundAmount().orElseThrow(),
                        "the contract is empty and the ledger has not noticed"));
    }

    private void fundOnChain() {
        chain.mint(token, buyer, AMOUNT.minorUnits());
        chain.approve(token, buyer, escrow, AMOUNT.minorUnits());
        chain.deposit(escrow, buyer, invoiceId, seller, AMOUNT.minorUnits());
    }

    private void openInvoiceAwaitingItsEscrow() {
        invoices.open(new Invoice(
                invoiceId, "PO-" + invoiceId, "seller-" + invoiceId, "buyer-" + invoiceId,
                AMOUNT, Clock.systemUTC().instant()));
        invoices.transition(invoiceId, InvoiceStatus.SUBMITTED, null, null);
        invoices.transition(invoiceId, InvoiceStatus.BUYER_ACCEPTED, null, null);
        invoices.transition(invoiceId, InvoiceStatus.ESCROW_PENDING, null, null);
    }
}
