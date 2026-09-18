package com.settletrust.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferServiceTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-09-18T12:00:00Z"), ZoneOffset.UTC);

    private static final AccountId HOUSE = AccountId.of("house-eur");
    private static final AccountId ALICE = AccountId.of("alice");
    private static final AccountId BOB = AccountId.of("bob");

    private Ledger ledger;
    private TransferService transfers;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        ledger.open(Account.house("house-eur", "EUR"));
        ledger.open(Account.customer("alice", "EUR"));
        ledger.open(Account.customer("bob", "EUR"));
        transfers = new TransferService(ledger, FIXED);

        // Fund Alice from the house account, the way real money enters the system.
        transfers.transfer(HOUSE, ALICE, Money.of(10_000L, "EUR"), "seed-alice");
    }

    @Nested
    @DisplayName("the invariant")
    class TheInvariant {

        @Test
        void aTransferWritesTwoEntriesThatSumToZero() {
            Transfer transfer = transfers.transfer(ALICE, BOB, Money.of(2_500L, "EUR"), "k1");

            List<Entry> pair = ledger.entries().stream()
                    .filter(e -> e.transferId().equals(transfer.transferId()))
                    .toList();

            assertEquals(2, pair.size(), "a transfer is exactly two entries");
            long sum = pair.stream().mapToLong(e -> e.amount().minorUnits()).sum();
            assertEquals(0L, sum, "the pair must sum to zero");
            assertEquals(1L, pair.stream().filter(Entry::isDebit).count());
            assertEquals(1L, pair.stream().filter(Entry::isCredit).count());
        }

        @Test
        void theWholeLedgerSumsToZero() {
            transfers.transfer(ALICE, BOB, Money.of(2_500L, "EUR"), "k1");
            transfers.transfer(BOB, ALICE, Money.of(400L, "EUR"), "k2");

            assertEquals(0L, ledger.sumOfAllEntries("EUR"),
                    "money is never created or destroyed, only moved");
        }

        @Test
        void balancesMoveByExactlyTheAmount() {
            transfers.transfer(ALICE, BOB, Money.of(2_500L, "EUR"), "k1");

            assertAll(
                    () -> assertEquals(Money.of(7_500L, "EUR"), ledger.balanceOf(ALICE)),
                    () -> assertEquals(Money.of(2_500L, "EUR"), ledger.balanceOf(BOB)),
                    () -> assertEquals(Money.of(-10_000L, "EUR"), ledger.balanceOf(HOUSE)));
        }

        @Test
        void aBalanceIsAProjectionAndNothingIsEverOverwritten() {
            transfers.transfer(ALICE, BOB, Money.of(1_000L, "EUR"), "k1");
            transfers.transfer(ALICE, BOB, Money.of(1_000L, "EUR"), "k2");

            assertEquals(3, ledger.entriesOf(ALICE).size(),
                    "the funding credit plus two debits, all still present");
            assertEquals(Money.of(8_000L, "EUR"), ledger.balanceOf(ALICE));
        }
    }

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        void replayingAKeyReturnsTheOriginalAndMovesNoMoneyAgain() {
            Transfer first = transfers.transfer(ALICE, BOB, Money.of(2_500L, "EUR"), "pay-1");
            Transfer replay = transfers.transfer(ALICE, BOB, Money.of(2_500L, "EUR"), "pay-1");

            assertAll(
                    () -> assertEquals(first.transferId(), replay.transferId(),
                            "a retry is the same transfer, not a new one"),
                    () -> assertEquals(Money.of(7_500L, "EUR"), ledger.balanceOf(ALICE)),
                    () -> assertEquals(Money.of(2_500L, "EUR"), ledger.balanceOf(BOB)));
        }

        @Test
        void aReplayIsFlaggedAsOneButTheOutcomeIsUnchanged() {
            Transfer first = transfers.transfer(ALICE, BOB, Money.of(100L, "EUR"), "pay-2");
            Transfer replay = transfers.transfer(ALICE, BOB, Money.of(100L, "EUR"), "pay-2");

            assertFalse(first.replayed());
            assertTrue(replay.replayed());
            assertEquals(first.amount(), replay.amount());
        }

        @Test
        void differentKeysAreDifferentPaymentsEvenWhenIdentical() {
            Transfer one = transfers.transfer(ALICE, BOB, Money.of(100L, "EUR"), "pay-3");
            Transfer two = transfers.transfer(ALICE, BOB, Money.of(100L, "EUR"), "pay-4");

            assertNotEquals(one.transferId(), two.transferId());
            assertEquals(Money.of(200L, "EUR"), ledger.balanceOf(BOB));
        }

        @Test
        void aKeyIsRequired() {
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> transfers.transfer(ALICE, BOB, Money.of(1L, "EUR"), null)),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> transfers.transfer(ALICE, BOB, Money.of(1L, "EUR"), "  ")));
        }
    }

    @Nested
    @DisplayName("what the ledger refuses")
    class Rejections {

        @Test
        void unknownAccounts() {
            AccountId ghost = AccountId.of("nobody");
            assertAll(
                    () -> assertEquals(TransferRejected.Reason.UNKNOWN_ACCOUNT, rejectionOf(
                            () -> transfers.transfer(ghost, BOB, Money.of(1L, "EUR"), "r1"))),
                    () -> assertEquals(TransferRejected.Reason.UNKNOWN_ACCOUNT, rejectionOf(
                            () -> transfers.transfer(ALICE, ghost, Money.of(1L, "EUR"), "r2"))));
        }

        @Test
        void nonPositiveAmounts() {
            assertAll(
                    () -> assertEquals(TransferRejected.Reason.AMOUNT_NOT_POSITIVE, rejectionOf(
                            () -> transfers.transfer(ALICE, BOB, Money.of(0L, "EUR"), "r3"))),
                    () -> assertEquals(TransferRejected.Reason.AMOUNT_NOT_POSITIVE, rejectionOf(
                            () -> transfers.transfer(ALICE, BOB, Money.of(-50L, "EUR"), "r4"))));
        }

        @Test
        void aTransferToItself() {
            assertEquals(TransferRejected.Reason.SAME_ACCOUNT, rejectionOf(
                    () -> transfers.transfer(ALICE, ALICE, Money.of(10L, "EUR"), "r5")));
        }

        @Test
        void crossingCurrencies() {
            ledger.open(Account.customer("carol-usd", "USD"));
            assertEquals(TransferRejected.Reason.CURRENCY_MISMATCH, rejectionOf(
                    () -> transfers.transfer(
                            ALICE, AccountId.of("carol-usd"), Money.of(10L, "EUR"), "r6")));
        }

        @Test
        void spendingMoreThanIsThere() {
            assertEquals(TransferRejected.Reason.INSUFFICIENT_FUNDS, rejectionOf(
                    () -> transfers.transfer(ALICE, BOB, Money.of(10_001L, "EUR"), "r7")));
        }

        @Test
        void aRejectedTransferWritesNothingAndBurnsNoKey() {
            assertThrows(TransferRejected.class,
                    () -> transfers.transfer(ALICE, BOB, Money.of(99_999L, "EUR"), "r8"));

            assertEquals(Money.of(10_000L, "EUR"), ledger.balanceOf(ALICE));

            // The key was never recorded, so the caller may legitimately retry it once
            // the account is funded. Recording it would strand a payment that never ran.
            transfers.transfer(HOUSE, ALICE, Money.of(90_000L, "EUR"), "top-up");
            Transfer retried = transfers.transfer(ALICE, BOB, Money.of(99_999L, "EUR"), "r8");
            assertFalse(retried.replayed());
        }

        @Test
        void theHouseAccountMayGoNegativeAndACustomerMayNot() {
            assertTrue(ledger.balanceOf(HOUSE).isNegative());
            assertEquals(TransferRejected.Reason.INSUFFICIENT_FUNDS, rejectionOf(
                    () -> transfers.transfer(BOB, ALICE, Money.of(1L, "EUR"), "r9")));
        }
    }

    private static TransferRejected.Reason rejectionOf(Runnable action) {
        return assertThrows(TransferRejected.class, action::run).reason();
    }
}
