package com.settletrust.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single-threaded tests prove the rules. These prove the rules still hold when the
 * calls arrive at once, which is the only way they will ever arrive in production.
 *
 * <p>A barrier is used rather than simply submitting the tasks, so that every thread is
 * released at the same instant and genuinely contends instead of being serialised by the
 * executor warming up.
 */
class ConcurrentTransferTest {

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
        transfers = new TransferService(ledger);
        transfers.transfer(HOUSE, ALICE, Money.of(1_000L, "EUR"), "seed-alice");
    }

    @Test
    @DisplayName("concurrent spending cannot overdraw an account")
    void concurrentSpendingCannotOverdraw() throws Exception {
        // Alice holds 1000. Fifty threads each try to move 100, so at most ten can
        // succeed. Without a lock around read-check-write, more of them would.
        int threads = 50;
        List<Object> outcomes = runAtOnce(threads, i ->
                transfers.transfer(ALICE, BOB, Money.of(100L, "EUR"), "spend-" + i));

        long succeeded = outcomes.stream().filter(o -> o instanceof Transfer).count();
        long refused = outcomes.stream()
                .filter(o -> o instanceof TransferRejected r
                        && r.reason() == TransferRejected.Reason.INSUFFICIENT_FUNDS)
                .count();

        assertEquals(10L, succeeded, "exactly ten transfers of 100 fit inside 1000");
        assertEquals(threads - 10L, refused, "every other attempt is refused, not lost");
        assertEquals(Money.of(0L, "EUR"), ledger.balanceOf(ALICE));
        assertFalse(ledger.balanceOf(ALICE).isNegative());
        assertEquals(0L, ledger.sumOfAllEntries("EUR"), "the books still balance");
    }

    @Test
    @DisplayName("concurrent retries of one payment move money once")
    void concurrentRetriesOfOnePaymentMoveMoneyOnce() throws Exception {
        // The same payment retried by twenty racing clients: a phone with a flaky
        // connection, or a gateway that resends. All twenty must land on one transfer.
        int threads = 20;
        List<Object> outcomes = runAtOnce(threads, i ->
                transfers.transfer(ALICE, BOB, Money.of(250L, "EUR"), "one-payment"));

        List<Transfer> completed = outcomes.stream()
                .filter(Transfer.class::isInstance)
                .map(Transfer.class::cast)
                .toList();

        assertEquals(threads, completed.size(), "every caller gets an answer");
        assertEquals(1L, completed.stream().map(Transfer::transferId).distinct().count(),
                "and it is the same transfer every time");
        assertEquals(1L, completed.stream().filter(t -> !t.replayed()).count(),
                "exactly one caller did the work; the rest were replays");
        assertEquals(Money.of(250L, "EUR"), ledger.balanceOf(BOB), "money moved once");
        assertEquals(0L, ledger.sumOfAllEntries("EUR"));
    }

    @Test
    @DisplayName("money is conserved under a mixed load")
    void moneyIsConservedUnderMixedLoad() throws Exception {
        transfers.transfer(HOUSE, BOB, Money.of(1_000L, "EUR"), "seed-bob");

        int threads = 60;
        runAtOnce(threads, i -> {
            boolean aliceToBob = i % 2 == 0;
            AccountId from = aliceToBob ? ALICE : BOB;
            AccountId to = aliceToBob ? BOB : ALICE;
            return transfers.transfer(from, to, Money.of(50L, "EUR"), "mixed-" + i);
        });

        long alice = ledger.balanceOf(ALICE).minorUnits();
        long bob = ledger.balanceOf(BOB).minorUnits();

        assertTrue(alice >= 0L, "alice never went negative");
        assertTrue(bob >= 0L, "bob never went negative");
        assertEquals(2_000L, alice + bob, "the two customers still hold what was issued");
        assertEquals(0L, ledger.sumOfAllEntries("EUR"));
    }

    /**
     * Runs {@code count} tasks that all start at the same moment, and returns what each
     * one produced: the returned value, or the exception it threw. Failures are collected
     * rather than rethrown because a refused transfer is a legitimate outcome here.
     */
    private static List<Object> runAtOnce(int count, ThrowingIntFunction task) throws Exception {
        // One virtual thread per task, so every task really is running when the barrier
        // releases. A bounded pool smaller than the barrier's party count deadlocks.
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            CyclicBarrier startLine = new CyclicBarrier(count);
            AtomicInteger next = new AtomicInteger();
            List<Callable<Object>> jobs = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                jobs.add(() -> {
                    int index = next.getAndIncrement();
                    startLine.await(10, TimeUnit.SECONDS);
                    try {
                        return task.apply(index);
                    } catch (RuntimeException failure) {
                        return failure;
                    }
                });
            }

            List<Object> results = new ArrayList<>(count);
            for (Future<Object> future : pool.invokeAll(jobs, 30, TimeUnit.SECONDS)) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ThrowingIntFunction {
        Object apply(int index);
    }
}
