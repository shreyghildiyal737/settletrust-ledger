package com.settletrust.ledger.chain;

import com.settletrust.ledger.AccountId;
import com.settletrust.ledger.Database;
import com.settletrust.ledger.Money;
import com.settletrust.ledger.PostgresLedger;
import com.settletrust.ledger.PostgresTransferService;
import com.settletrust.ledger.TestDatabases;
import com.settletrust.ledger.invoice.Invoice;
import com.settletrust.ledger.invoice.InvoiceStatus;
import com.settletrust.ledger.invoice.PostgresInvoices;
import com.settletrust.ledger.settlement.InvoiceSettlement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chain rail, against a chain that can be made to change its mind.
 *
 * <p>Each test is one thing the chain does that a queue would not: hand the same event over
 * twice, take a block back, re-mine a transaction somewhere else, or deliver money for an
 * invoice that is not ready for it.
 */
class EscrowWatcherTest {

    private static final int CONFIRMATIONS = 3;
    private static final Money AMOUNT = Money.of(250_000L, "EURC");

    private static Database database;

    private PostgresLedger ledger;
    private PostgresInvoices invoices;
    private PostgresChainObservations observations;
    private EscrowWatcher watcher;
    private FakeChain chain;

    private String invoiceId;
    private String txHash;

    @BeforeAll
    static void startDatabase() {
        TestDatabases.Target target = TestDatabases.resolve();
        database = new Database(target.url(), target.username(), target.password());
    }

    @AfterAll
    static void stopDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void openAnInvoiceWaitingForItsEscrow() {
        Clock clock = Clock.systemUTC();
        ledger = new PostgresLedger(database.dsl(), clock);
        invoices = new PostgresInvoices(database.dsl(), clock);
        PostgresTransferService transfers = new PostgresTransferService(database.dsl(), clock);
        InvoiceSettlement settlement =
                new InvoiceSettlement(database.dsl(), ledger, invoices, transfers);
        observations = new PostgresChainObservations(database.dsl(), clock);
        watcher = new EscrowWatcher(database.dsl(), observations, settlement, CONFIRMATIONS);
        chain = new FakeChain();

        // Each test gets a fresh chain starting at block zero, so observations left by an
        // earlier test would be judged against a history that no longer contains them.
        // Clearing them is legitimate precisely because this table is the watcher's
        // cursor rather than a record of what happened: the ledger entries every test
        // produced are still there, untouched and per-invoice.
        database.dsl().execute("delete from chain_observation");
        database.dsl().execute("delete from chain_cursor");

        String run = UUID.randomUUID().toString().substring(0, 8);
        invoiceId = "inv-" + run;
        txHash = "0xtx" + run;

        invoices.open(new Invoice(
                invoiceId, "PO-" + run, "seller-" + run, "buyer-" + run, AMOUNT, clock.instant()));
        walkToEscrowPending();
    }

    @Test
    @DisplayName("a deposit is not credited until it is buried deep enough")
    void confirmationsAreWaitedFor() {
        chain.mineDeposit(txHash, invoiceId, AMOUNT);

        EscrowWatcher.PollResult seen = watcher.poll(chain);

        assertAll("seen but not acted on",
                () -> assertEquals(1, seen.recorded()),
                () -> assertEquals(0, seen.confirmed()),
                () -> assertEquals(1, seen.deferred()),
                () -> assertEquals(InvoiceStatus.ESCROW_PENDING,
                        invoices.require(invoiceId).status()),
                () -> assertEquals(Money.zero("EURC"), escrowBalance()));

        chain.mineEmpty(CONFIRMATIONS - 1);
        EscrowWatcher.PollResult buried = watcher.poll(chain);

        assertAll("now deep enough",
                () -> assertEquals(1, buried.confirmed()),
                () -> assertEquals(InvoiceStatus.ESCROW_FUNDED,
                        invoices.require(invoiceId).status()),
                () -> assertEquals(AMOUNT, escrowBalance()),
                () -> assertEquals(0L, ledger.sumOfAllEntries("EURC")));
    }

    @Test
    @DisplayName("polling again does not credit the same deposit twice")
    void theSameDepositIsCreditedOnce() {
        confirmTheDeposit();

        EscrowWatcher.PollResult again = watcher.poll(chain);

        assertAll(
                () -> assertEquals(0, again.recorded(), "already known"),
                () -> assertEquals(0, again.confirmed(), "already done"),
                () -> assertEquals(AMOUNT, escrowBalance(), "credited once"),
                () -> assertEquals(0L, ledger.sumOfAllEntries("EURC")));
    }

    @Test
    @DisplayName("a reorg that removes a credited deposit reverses it and freezes the invoice")
    void aReorgReversesWhatItTookBack() {
        long beforeTheDeposit = chain.headBlockNumber();
        confirmTheDeposit();

        // The chain abandons the block the deposit was in, and builds a different history.
        chain.rollBackTo(beforeTheDeposit);
        chain.mineEmpty(CONFIRMATIONS + 1);

        EscrowWatcher.PollResult afterReorg = watcher.poll(chain);

        assertAll(
                () -> assertEquals(1, afterReorg.reversed()),
                () -> assertEquals(Money.zero("EURC"), escrowBalance(), "the credit was undone"),
                () -> assertEquals(InvoiceStatus.FROZEN, invoices.require(invoiceId).status(),
                        "a person has to look at an invoice whose funding evaporated"),
                () -> assertEquals(0L, ledger.sumOfAllEntries("EURC"), "and the books balance"),
                () -> assertEquals(2, ledger.entriesOf(escrow()).size(),
                        "the credit and its reversal, both still there, neither deleted"),
                () -> assertEquals(ObservationStatus.REVERSED,
                        observations.find(txHash, 0).orElseThrow().status()));
    }

    @Test
    @DisplayName("a deposit dropped before it was credited is abandoned, and nothing moved")
    void aPendingDepositThatVanishesCostsNothing() {
        long beforeTheDeposit = chain.headBlockNumber();
        chain.mineDeposit(txHash, invoiceId, AMOUNT);
        watcher.poll(chain);

        chain.rollBackTo(beforeTheDeposit);
        chain.mineEmpty(1);
        EscrowWatcher.PollResult afterReorg = watcher.poll(chain);

        assertAll(
                () -> assertEquals(1, afterReorg.abandoned()),
                () -> assertEquals(0, afterReorg.reversed(), "nothing to reverse"),
                () -> assertEquals(InvoiceStatus.ESCROW_PENDING,
                        invoices.require(invoiceId).status()),
                () -> assertEquals(0, ledger.entriesOf(escrow()).size()));
    }

    @Test
    @DisplayName("a transaction re-mined in another block is followed, not written off")
    void aReanchoredDepositStillConfirms() {
        long beforeTheDeposit = chain.headBlockNumber();
        chain.mineDeposit(txHash, invoiceId, AMOUNT);
        watcher.poll(chain);

        // The reorg drops the block, and the very same transaction is mined again higher up.
        chain.rollBackTo(beforeTheDeposit);
        chain.mineEmpty(1);
        chain.mineDeposit(txHash, invoiceId, AMOUNT);
        EscrowWatcher.PollResult followed = watcher.poll(chain);

        chain.mineEmpty(CONFIRMATIONS);
        EscrowWatcher.PollResult settled = watcher.poll(chain);

        assertAll(
                () -> assertTrue(followed.reanchored() + followed.abandoned() >= 1,
                        "the move was noticed one way or another"),
                () -> assertEquals(1, settled.confirmed(), "and it still gets credited"),
                () -> assertEquals(AMOUNT, escrowBalance(), "once"),
                () -> assertEquals(InvoiceStatus.ESCROW_FUNDED,
                        invoices.require(invoiceId).status()));
    }

    @Test
    @DisplayName("money for an invoice that is not ready waits for it, then lands")
    void aDepositForAnUnreadyInvoiceIsDeferred() {
        // A buyer who paid early. The invoice is still a draft, and a draft cannot become
        // escrow_funded, so the money is real but the invoice is not entitled to it yet.
        String early = "inv-early-" + UUID.randomUUID().toString().substring(0, 8);
        invoices.open(new Invoice(
                early, "PO-" + early, "seller-" + early, "buyer-" + early,
                AMOUNT, Clock.systemUTC().instant()));

        String earlyTx = "0xtxearly" + early;
        chain.mineDeposit(earlyTx, early, AMOUNT);
        chain.mineEmpty(CONFIRMATIONS);
        EscrowWatcher.PollResult blocked = watcher.poll(chain);

        assertAll("held, not lost",
                () -> assertEquals(0, blocked.confirmed()),
                () -> assertEquals(1, blocked.deferred()),
                () -> assertEquals(InvoiceStatus.DRAFT, invoices.require(early).status()),
                () -> assertEquals(ObservationStatus.PENDING,
                        observations.find(earlyTx, 0).orElseThrow().status()));

        // The invoice catches up with the money, and the next pass credits it.
        invoices.transition(early, InvoiceStatus.SUBMITTED, null, null);
        invoices.transition(early, InvoiceStatus.BUYER_ACCEPTED, null, null);
        invoices.transition(early, InvoiceStatus.ESCROW_PENDING, null, null);

        EscrowWatcher.PollResult caughtUp = watcher.poll(chain);

        assertAll("and then it lands",
                () -> assertEquals(1, caughtUp.confirmed()),
                () -> assertEquals(InvoiceStatus.ESCROW_FUNDED, invoices.require(early).status()),
                () -> assertEquals(AMOUNT,
                        ledger.balanceOf(InvoiceSettlement.escrowAccountFor(early))));
    }

    @Test
    @DisplayName("a reversal after the escrow was paid out is recorded as the platform's loss")
    void aLateReversalLandsOnTheShortfallAccount() {
        long beforeTheDeposit = chain.headBlockNumber();
        confirmTheDeposit();

        // The seller is paid before the chain changes its mind.
        invoices.transition(invoiceId, InvoiceStatus.DELIVERY_CONFIRMED, null, "delivered");
        invoices.transition(invoiceId, InvoiceStatus.SETTLEMENT_PENDING, null, "ready");
        AccountId seller = AccountId.of("seller-paid-" + invoiceId);
        ledger.open(new com.settletrust.ledger.Account(
                seller, "EURC", com.settletrust.ledger.Account.Kind.CUSTOMER));
        new InvoiceSettlement(
                database.dsl(), ledger, invoices,
                new PostgresTransferService(database.dsl(), Clock.systemUTC()))
                .settle(invoiceId, seller);

        AccountId shortfall = InvoiceSettlement.chainShortfallAccountFor("EURC");
        // The shortfall account is the platform's, shared by every invoice and every test
        // run, so what matters is what this reversal added to it.
        long exposureBefore = ledger.find(shortfall)
                .map(account -> ledger.balanceOf(shortfall).minorUnits())
                .orElse(0L);

        chain.rollBackTo(beforeTheDeposit);
        chain.mineEmpty(CONFIRMATIONS + 1);
        watcher.poll(chain);

        assertAll(
                () -> assertEquals(InvoiceStatus.SETTLED, invoices.require(invoiceId).status(),
                        "a settled invoice stays settled: the seller really was paid"),
                () -> assertEquals(AMOUNT, ledger.balanceOf(seller),
                        "the seller keeps what they were paid"),
                () -> assertEquals(exposureBefore - AMOUNT.minorUnits(),
                        ledger.balanceOf(shortfall).minorUnits(),
                        "and the loss is somebody's, named and countable"),
                () -> assertEquals(0L, ledger.sumOfAllEntries("EURC")));
    }

    private void confirmTheDeposit() {
        chain.mineDeposit(txHash, invoiceId, AMOUNT);
        chain.mineEmpty(CONFIRMATIONS);
        watcher.poll(chain);
    }

    private void walkToEscrowPending() {
        invoices.transition(invoiceId, InvoiceStatus.SUBMITTED, null, null);
        invoices.transition(invoiceId, InvoiceStatus.BUYER_ACCEPTED, null, null);
        invoices.transition(invoiceId, InvoiceStatus.ESCROW_PENDING, null, null);
    }

    private AccountId escrow() {
        return InvoiceSettlement.escrowAccountFor(invoiceId);
    }

    private Money escrowBalance() {
        return ledger.find(escrow()).isEmpty()
                ? Money.zero("EURC")
                : ledger.balanceOf(escrow());
    }
}
