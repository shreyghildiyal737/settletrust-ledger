package com.settletrust.ledger;

import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same rules as the in-memory tests, against a real Postgres. What is actually being
 * tested here is everything the in-memory version could not prove: that the migrations
 * apply, that the row lock serialises spending, that the unique constraint on the
 * idempotency key holds under a real race, and that the database itself refuses to let
 * history be rewritten.
 */
class PostgresTransferServiceTest {

    /**
     * Point these at an existing Postgres to use it instead of starting a container.
     * Testcontainers is the default and what CI uses; this exists because a developer
     * machine can have a working Docker daemon that Testcontainers still cannot reach,
     * and a test suite that cannot be run is a test suite nobody runs.
     */
    private static final String URL_OVERRIDE = "LEDGER_TEST_JDBC_URL";
    private static final String USER_OVERRIDE = "LEDGER_TEST_DB_USER";
    private static final String PASSWORD_OVERRIDE = "LEDGER_TEST_DB_PASSWORD";

    private static PostgreSQLContainer<?> container;
    private static Database database;

    private PostgresLedger ledger;
    private PostgresTransferService transfers;

    private AccountId house;
    private AccountId alice;
    private AccountId bob;

    @BeforeAll
    static void startDatabase() {
        String url = System.getenv(URL_OVERRIDE);
        if (url != null && !url.isBlank()) {
            database = new Database(
                    url,
                    System.getenv().getOrDefault(USER_OVERRIDE, "postgres"),
                    System.getenv().getOrDefault(PASSWORD_OVERRIDE, "postgres"));
            return;
        }

        container = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("settletrust")
                .withUsername("settletrust")
                .withPassword("settletrust");
        container.start();
        database = new Database(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    @AfterAll
    static void stopDatabase() {
        if (database != null) {
            database.close();
        }
        if (container != null) {
            container.stop();
        }
    }

    @BeforeEach
    void setUp() {
        ledger = new PostgresLedger(database);
        transfers = new PostgresTransferService(database);

        // The append-only triggers mean rows are never cleaned up between tests, so each
        // test works with its own accounts instead. That is a truer test anyway: the
        // ledger in production is never empty.
        String run = UUID.randomUUID().toString().substring(0, 8);
        house = AccountId.of("house-" + run);
        alice = AccountId.of("alice-" + run);
        bob = AccountId.of("bob-" + run);

        ledger.open(new Account(house, "EUR", Account.Kind.HOUSE));
        ledger.open(new Account(alice, "EUR", Account.Kind.CUSTOMER));
        ledger.open(new Account(bob, "EUR", Account.Kind.CUSTOMER));
        transfers.transfer(house, alice, Money.of(10_000L, "EUR"), "seed-" + run);
    }

    @Test
    @DisplayName("migrations apply and a transfer writes a balanced pair")
    void aTransferWritesABalancedPair() {
        Transfer transfer = transfers.transfer(alice, bob, Money.of(2_500L, "EUR"), key());

        List<Entry> pair = ledger.entriesOf(bob).stream()
                .filter(e -> e.transferId().equals(transfer.transferId()))
                .toList();

        assertAll(
                () -> assertEquals(1, pair.size(), "one half landed on bob"),
                () -> assertEquals(Money.of(7_500L, "EUR"), ledger.balanceOf(alice)),
                () -> assertEquals(Money.of(2_500L, "EUR"), ledger.balanceOf(bob)),
                () -> assertEquals(0L, ledger.sumOfAllEntries("EUR"),
                        "every entry in EUR still sums to zero"));
    }

    @Test
    @DisplayName("a replayed key returns the stored transfer and moves nothing")
    void aReplayedKeyMovesNothing() {
        String key = key();
        Transfer first = transfers.transfer(alice, bob, Money.of(2_500L, "EUR"), key);
        Transfer replay = transfers.transfer(alice, bob, Money.of(2_500L, "EUR"), key);

        assertAll(
                () -> assertEquals(first.transferId(), replay.transferId()),
                () -> assertFalse(first.replayed()),
                () -> assertTrue(replay.replayed()),
                () -> assertEquals(Money.of(2_500L, "EUR"), ledger.balanceOf(bob)));
    }

    @Test
    @DisplayName("the rules are enforced against the database too")
    void theRulesAreEnforced() {
        assertAll(
                () -> assertEquals(TransferRejected.Reason.INSUFFICIENT_FUNDS,
                        rejectionOf(() -> transfers.transfer(
                                alice, bob, Money.of(10_001L, "EUR"), key()))),
                () -> assertEquals(TransferRejected.Reason.UNKNOWN_ACCOUNT,
                        rejectionOf(() -> transfers.transfer(
                                AccountId.of("ghost"), bob, Money.of(1L, "EUR"), key()))),
                () -> assertEquals(TransferRejected.Reason.SAME_ACCOUNT,
                        rejectionOf(() -> transfers.transfer(
                                alice, alice, Money.of(1L, "EUR"), key()))),
                () -> assertEquals(TransferRejected.Reason.AMOUNT_NOT_POSITIVE,
                        rejectionOf(() -> transfers.transfer(
                                alice, bob, Money.of(0L, "EUR"), key()))));
    }

    @Test
    @DisplayName("the database refuses to let history be rewritten")
    void historyCannotBeRewritten() {
        transfers.transfer(alice, bob, Money.of(100L, "EUR"), key());

        // Not through the service, deliberately: this is what protects the ledger from a
        // console session, a bad migration, or a future bug in code nobody has written yet.
        DataAccessException onUpdate = assertThrows(DataAccessException.class, () ->
                database.dsl().execute(
                        "update entry set amount_minor = 0 where account_id = ?", bob.value()));
        DataAccessException onDelete = assertThrows(DataAccessException.class, () ->
                database.dsl().execute(
                        "delete from entry where account_id = ?", bob.value()));

        assertAll(
                () -> assertTrue(onUpdate.getMessage().contains("append-only")
                        || onUpdate.getCause().getMessage().contains("append-only")),
                () -> assertTrue(onDelete.getMessage().contains("append-only")
                        || onDelete.getCause().getMessage().contains("append-only")),
                () -> assertEquals(Money.of(100L, "EUR"), ledger.balanceOf(bob)));
    }

    @Test
    @DisplayName("a row lock stops concurrent spending from overdrawing")
    void concurrentSpendingCannotOverdraw() throws Exception {
        // Alice holds 10000. Thirty threads each try to move 1000, so at most ten fit.
        int threads = 30;
        List<Object> outcomes = runAtOnce(threads, i ->
                transfers.transfer(alice, bob, Money.of(1_000L, "EUR"), key()));

        long succeeded = outcomes.stream().filter(Transfer.class::isInstance).count();
        long refused = outcomes.stream()
                .filter(o -> o instanceof TransferRejected r
                        && r.reason() == TransferRejected.Reason.INSUFFICIENT_FUNDS)
                .count();

        assertAll(
                () -> assertEquals(10L, succeeded, "exactly ten transfers of 1000 fit inside 10000"),
                () -> assertEquals(threads - 10L, refused),
                () -> assertEquals(Money.of(0L, "EUR"), ledger.balanceOf(alice)),
                () -> assertEquals(0L, ledger.sumOfAllEntries("EUR")));
    }

    @Test
    @DisplayName("the unique constraint settles a real race on one key")
    void concurrentRetriesOfOnePaymentMoveMoneyOnce() throws Exception {
        // Every thread checks for the key, finds nothing because the winner has not
        // committed yet, and tries to insert. The constraint decides, not the check.
        String key = key();
        int threads = 16;
        List<Object> outcomes = runAtOnce(threads, i ->
                transfers.transfer(alice, bob, Money.of(250L, "EUR"), key));

        List<Transfer> completed = outcomes.stream()
                .filter(Transfer.class::isInstance)
                .map(Transfer.class::cast)
                .toList();

        assertAll(
                () -> assertEquals(threads, completed.size(), "every caller gets an answer"),
                () -> assertEquals(1L,
                        completed.stream().map(Transfer::transferId).distinct().count(),
                        "and it is the same transfer every time"),
                () -> assertEquals(Money.of(250L, "EUR"), ledger.balanceOf(bob),
                        "money moved exactly once"),
                () -> assertEquals(0L, ledger.sumOfAllEntries("EUR")));
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static TransferRejected.Reason rejectionOf(Runnable action) {
        return assertThrows(TransferRejected.class, action::run).reason();
    }

    private static List<Object> runAtOnce(int count, ThrowingIntFunction task) throws Exception {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            CyclicBarrier startLine = new CyclicBarrier(count);
            AtomicInteger next = new AtomicInteger();
            List<Callable<Object>> jobs = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                jobs.add(() -> {
                    int index = next.getAndIncrement();
                    startLine.await(30, TimeUnit.SECONDS);
                    try {
                        return task.apply(index);
                    } catch (RuntimeException failure) {
                        return failure;
                    }
                });
            }

            List<Object> results = new ArrayList<>(count);
            for (Future<Object> future : pool.invokeAll(jobs, 120, TimeUnit.SECONDS)) {
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
