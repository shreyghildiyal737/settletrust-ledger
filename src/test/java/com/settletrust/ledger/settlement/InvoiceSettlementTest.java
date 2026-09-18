package com.settletrust.ledger.settlement;

import com.settletrust.ledger.Account;
import com.settletrust.ledger.AccountId;
import com.settletrust.ledger.Database;
import com.settletrust.ledger.Money;
import com.settletrust.ledger.PostgresLedger;
import com.settletrust.ledger.PostgresTransferService;
import com.settletrust.ledger.TestDatabases;
import com.settletrust.ledger.TransferRejected;
import com.settletrust.ledger.invoice.Invoice;
import com.settletrust.ledger.invoice.InvoiceStatus;
import com.settletrust.ledger.invoice.InvoiceTransitionRejected;
import com.settletrust.ledger.invoice.PostgresInvoices;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The point where an invoice and the money stop being two stories.
 *
 * <p>Every test here is really one claim: the transition and the transfer are the same
 * atomic act. An invoice is never funded without the money arriving, never settled without
 * the money leaving, and a refusal on either side leaves nothing behind on the other.
 */
class InvoiceSettlementTest {

    private static final Money AMOUNT = Money.of(250_000L, "EUR");

    private static Database database;

    private PostgresLedger ledger;
    private PostgresInvoices invoices;
    private InvoiceSettlement settlement;

    private String invoiceId;
    private AccountId house;
    private AccountId buyer;
    private AccountId seller;

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
    void openAnInvoiceAndItsParties() {
        Clock clock = Clock.systemUTC();
        ledger = new PostgresLedger(database.dsl(), clock);
        invoices = new PostgresInvoices(database.dsl(), clock);
        PostgresTransferService transfers = new PostgresTransferService(database.dsl(), clock);
        settlement = new InvoiceSettlement(database.dsl(), ledger, invoices, transfers);

        String run = UUID.randomUUID().toString().substring(0, 8);
        invoiceId = "inv-" + run;
        house = AccountId.of("house-" + run);
        buyer = AccountId.of("buyer-" + run);
        seller = AccountId.of("seller-" + run);

        ledger.open(new Account(house, "EUR", Account.Kind.HOUSE));
        ledger.open(new Account(buyer, "EUR", Account.Kind.CUSTOMER));
        ledger.open(new Account(seller, "EUR", Account.Kind.CUSTOMER));
        transfers.transfer(house, buyer, Money.of(1_000_000L, "EUR"), "fund-buyer-" + run);

        invoices.open(new Invoice(
                invoiceId, "PO-" + run, seller.value(), buyer.value(), AMOUNT, clock.instant()));
    }

    @Test
    @DisplayName("an invoice walked end to end moves the money exactly once")
    void theWholeJourney() {
        walkTo(InvoiceStatus.ESCROW_PENDING);

        settlement.fundEscrow(invoiceId, buyer);
        AccountId escrow = InvoiceSettlement.escrowAccountFor(invoiceId);

        assertAll("after funding",
                () -> assertEquals(InvoiceStatus.ESCROW_FUNDED, invoices.require(invoiceId).status()),
                () -> assertEquals(AMOUNT, ledger.balanceOf(escrow), "the escrow holds it"),
                () -> assertEquals(Money.of(750_000L, "EUR"), ledger.balanceOf(buyer)),
                () -> assertEquals(Money.zero("EUR"), ledger.balanceOf(seller)));

        invoices.transition(invoiceId, InvoiceStatus.DELIVERY_CONFIRMED, null, "delivered");
        invoices.transition(invoiceId, InvoiceStatus.SETTLEMENT_PENDING, null, "ready");

        settlement.settle(invoiceId, seller);

        assertAll("after settlement",
                () -> assertEquals(InvoiceStatus.SETTLED, invoices.require(invoiceId).status()),
                () -> assertEquals(Money.zero("EUR"), ledger.balanceOf(escrow), "escrow emptied"),
                () -> assertEquals(AMOUNT, ledger.balanceOf(seller), "the seller was paid"),
                () -> assertEquals(0L, ledger.sumOfAllEntries("EUR"), "the books still balance"));
    }

    @Test
    @DisplayName("an invoice cannot simply be declared funded or settled")
    void moneyStatesCannotBeAsserted() {
        walkTo(InvoiceStatus.ESCROW_PENDING);

        InvoiceTransitionRejected funded = assertThrows(
                InvoiceTransitionRejected.class,
                () -> invoices.transition(invoiceId, InvoiceStatus.ESCROW_FUNDED, null, "trust me"));

        assertAll(
                () -> assertEquals(
                        InvoiceTransitionRejected.Reason.MONEY_MOVEMENT_REQUIRED, funded.reason()),
                () -> assertEquals(
                        InvoiceStatus.ESCROW_PENDING, invoices.require(invoiceId).status()));
    }

    @Test
    @DisplayName("a buyer who cannot pay leaves the invoice exactly where it was")
    void aFailedFundingRollsBackTheTransition() {
        // Drain the buyer so the escrow transfer cannot succeed.
        PostgresTransferService transfers =
                new PostgresTransferService(database.dsl(), Clock.systemUTC());
        transfers.transfer(buyer, house, Money.of(1_000_000L, "EUR"), "drain-" + invoiceId);

        walkTo(InvoiceStatus.ESCROW_PENDING);

        TransferRejected refusal = assertThrows(
                TransferRejected.class, () -> settlement.fundEscrow(invoiceId, buyer));

        assertAll(
                () -> assertEquals(TransferRejected.Reason.INSUFFICIENT_FUNDS, refusal.reason()),
                () -> assertEquals(InvoiceStatus.ESCROW_PENDING,
                        invoices.require(invoiceId).status(),
                        "the invoice did not move"),
                () -> assertEquals(4, invoices.history(invoiceId).size(),
                        "and nothing was written to its history"),
                () -> assertEquals(0L, ledger.sumOfAllEntries("EUR")));
    }

    @Test
    @DisplayName("settling twice returns the first settlement and pays once")
    void settlingTwiceIsARetryNotASecondPayment() {
        walkTo(InvoiceStatus.ESCROW_PENDING);
        settlement.fundEscrow(invoiceId, buyer);
        invoices.transition(invoiceId, InvoiceStatus.DELIVERY_CONFIRMED, null, null);
        invoices.transition(invoiceId, InvoiceStatus.SETTLEMENT_PENDING, null, null);

        InvoiceSettlement.Settlement first = settlement.settle(invoiceId, seller);
        // A client whose connection dropped and asked again.
        InvoiceSettlement.Settlement retry = settlement.settle(invoiceId, seller);

        assertAll(
                () -> assertEquals(first.transition().id(), retry.transition().id(),
                        "the same step, not a new one"),
                () -> assertEquals(first.transfer().transferId(), retry.transfer().transferId()),
                () -> assertEquals(true, retry.transfer().replayed()),
                () -> assertEquals(AMOUNT, ledger.balanceOf(seller), "paid once, not twice"),
                () -> assertEquals(0L, ledger.sumOfAllEntries("EUR")));
    }

    @Test
    @DisplayName("funding twice is a retry, and the buyer is charged once")
    void fundingTwiceIsARetry() {
        walkTo(InvoiceStatus.ESCROW_PENDING);

        InvoiceSettlement.Settlement first = settlement.fundEscrow(invoiceId, buyer);
        InvoiceSettlement.Settlement retry = settlement.fundEscrow(invoiceId, buyer);

        assertAll(
                () -> assertEquals(first.transfer().transferId(), retry.transfer().transferId()),
                () -> assertEquals(true, retry.transfer().replayed()),
                () -> assertEquals(Money.of(750_000L, "EUR"), ledger.balanceOf(buyer),
                        "charged once"),
                () -> assertEquals(AMOUNT,
                        ledger.balanceOf(InvoiceSettlement.escrowAccountFor(invoiceId))));
    }

    @Test
    @DisplayName("an invoice cannot be settled before delivery is confirmed")
    void settlementWaitsForDelivery() {
        walkTo(InvoiceStatus.ESCROW_PENDING);
        settlement.fundEscrow(invoiceId, buyer);

        InvoiceTransitionRejected tooEarly = assertThrows(
                InvoiceTransitionRejected.class, () -> settlement.settle(invoiceId, seller));

        assertAll(
                () -> assertEquals(
                        InvoiceTransitionRejected.Reason.ILLEGAL_TRANSITION, tooEarly.reason()),
                () -> assertEquals(Money.zero("EUR"), ledger.balanceOf(seller)),
                () -> assertEquals(AMOUNT,
                        ledger.balanceOf(InvoiceSettlement.escrowAccountFor(invoiceId)),
                        "the money stayed in escrow"));
    }

    /** Walks the invoice up to the given status through the ordinary transitions. */
    private void walkTo(InvoiceStatus target) {
        invoices.transition(invoiceId, InvoiceStatus.SUBMITTED, null, null);
        invoices.transition(invoiceId, InvoiceStatus.BUYER_ACCEPTED, null, null);
        if (target == InvoiceStatus.BUYER_ACCEPTED) {
            return;
        }
        invoices.transition(invoiceId, InvoiceStatus.ESCROW_PENDING, null, null);
    }
}
