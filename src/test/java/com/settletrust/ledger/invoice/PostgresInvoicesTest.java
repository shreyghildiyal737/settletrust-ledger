package com.settletrust.ledger.invoice;

import com.settletrust.ledger.Database;
import com.settletrust.ledger.Money;
import com.settletrust.ledger.TestDatabases;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lifecycle against a real database. What these prove, and the in-memory tests cannot,
 * is that the status really is derived from history, that the history cannot be rewritten,
 * and that two clients racing to move one invoice cannot both win.
 */
class PostgresInvoicesTest {

    private static Database database;

    private PostgresInvoices invoices;
    private String invoiceId;

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
    void openAnInvoice() {
        invoices = new PostgresInvoices(database.dsl(), Clock.systemUTC());
        invoiceId = "inv-" + UUID.randomUUID().toString().substring(0, 8);
        invoices.open(new Invoice(
                invoiceId,
                "PO-" + invoiceId,
                "seller-acme",
                "buyer-globex",
                Money.of(250_000L, "EUR"),
                Clock.systemUTC().instant()));
    }

    @Test
    @DisplayName("a new invoice opens in draft with an opening transition")
    void aNewInvoiceOpensInDraft() {
        InvoiceState state = invoices.require(invoiceId);
        List<InvoiceTransition> history = invoices.history(invoiceId);

        assertAll(
                () -> assertEquals(InvoiceStatus.DRAFT, state.status()),
                () -> assertEquals(0, state.sequence()),
                () -> assertEquals(1, history.size()),
                () -> assertNull(history.get(0).from(), "nothing came before the first state"),
                () -> assertEquals(InvoiceStatus.DRAFT, history.get(0).to()),
                () -> assertEquals(Money.of(250_000L, "EUR"), state.invoice().amount()));
    }

    @Test
    @DisplayName("the status is the latest transition, and the history keeps every step")
    void theStatusIsAProjectionOverHistory() {
        invoices.transition(invoiceId, InvoiceStatus.SUBMITTED, InvoiceStatus.DRAFT, "sent");
        invoices.transition(invoiceId, InvoiceStatus.BUYER_ACCEPTED, null, "buyer signed");
        invoices.transition(invoiceId, InvoiceStatus.ESCROW_PENDING, null, null);

        InvoiceState state = invoices.require(invoiceId);
        List<InvoiceTransition> history = invoices.history(invoiceId);

        assertAll(
                () -> assertEquals(InvoiceStatus.ESCROW_PENDING, state.status()),
                () -> assertEquals(3, state.sequence()),
                () -> assertEquals(4, history.size(), "the opening plus three moves"),
                () -> assertEquals(List.of(0, 1, 2, 3),
                        history.stream().map(InvoiceTransition::sequence).toList()),
                () -> assertEquals("buyer signed", history.get(2).reason()));
    }

    @Test
    @DisplayName("an illegal move is refused and leaves no trace")
    void anIllegalMoveLeavesNoTrace() {
        InvoiceTransitionRejected rejection = assertThrows(
                InvoiceTransitionRejected.class,
                () -> invoices.transition(invoiceId, InvoiceStatus.SETTLED, null, "wishful"));

        assertAll(
                () -> assertEquals(
                        InvoiceTransitionRejected.Reason.ILLEGAL_TRANSITION, rejection.reason()),
                () -> assertEquals(InvoiceStatus.DRAFT, invoices.require(invoiceId).status()),
                () -> assertEquals(1, invoices.history(invoiceId).size()));
    }

    @Test
    @DisplayName("a stale expectation is a conflict, not an action")
    void aStaleExpectationIsRefused() {
        invoices.transition(invoiceId, InvoiceStatus.SUBMITTED, InvoiceStatus.DRAFT, null);

        // A second client still holding the view it read before that move.
        InvoiceTransitionRejected rejection = assertThrows(
                InvoiceTransitionRejected.class,
                () -> invoices.transition(
                        invoiceId, InvoiceStatus.CANCELLED, InvoiceStatus.DRAFT, "too late"));

        assertAll(
                () -> assertEquals(
                        InvoiceTransitionRejected.Reason.STATE_CHANGED, rejection.reason()),
                () -> assertEquals(InvoiceStatus.SUBMITTED, invoices.require(invoiceId).status()));
    }

    @Test
    @DisplayName("an unknown invoice is not found")
    void anUnknownInvoiceIsNotFound() {
        InvoiceTransitionRejected rejection = assertThrows(
                InvoiceTransitionRejected.class,
                () -> invoices.transition("inv-nowhere", InvoiceStatus.SUBMITTED, null, null));

        assertEquals(InvoiceTransitionRejected.Reason.UNKNOWN_INVOICE, rejection.reason());
    }

    @Test
    @DisplayName("history cannot be rewritten, only added to")
    void historyCannotBeRewritten() {
        invoices.transition(invoiceId, InvoiceStatus.SUBMITTED, null, null);

        assertAll(
                () -> assertThrows(DataAccessException.class, () -> database.dsl().execute(
                        "update invoice_transition set to_status = 'settled' where invoice_id = ?",
                        invoiceId)),
                () -> assertThrows(DataAccessException.class, () -> database.dsl().execute(
                        "delete from invoice_transition where invoice_id = ?", invoiceId)),
                () -> assertEquals(InvoiceStatus.SUBMITTED, invoices.require(invoiceId).status()));
    }

    @Test
    @DisplayName("only one of several racing clients can move the invoice")
    void onlyOneRacingClientWins() throws Exception {
        // Twelve clients, all having read the same draft, all trying a legal but different
        // next move at once. The sequence number is unique per invoice, so exactly one
        // insert survives and the rest are told the invoice moved under them.
        int clients = 12;
        List<Object> outcomes = runAtOnce(clients, index -> invoices.transition(
                invoiceId,
                index % 2 == 0 ? InvoiceStatus.SUBMITTED : InvoiceStatus.CANCELLED,
                InvoiceStatus.DRAFT,
                "client " + index));

        long applied = outcomes.stream().filter(InvoiceTransition.class::isInstance).count();
        long conflicted = outcomes.stream()
                .filter(o -> o instanceof InvoiceTransitionRejected r
                        && r.reason() == InvoiceTransitionRejected.Reason.STATE_CHANGED)
                .count();

        assertAll(
                () -> assertEquals(1L, applied, "exactly one move is applied"),
                () -> assertEquals(clients - 1L, conflicted, "the rest are told, not ignored"),
                () -> assertEquals(2, invoices.history(invoiceId).size(),
                        "the opening transition and one move"),
                () -> assertTrue(List.of(InvoiceStatus.SUBMITTED, InvoiceStatus.CANCELLED)
                        .contains(invoices.require(invoiceId).status())));
    }

    private static List<Object> runAtOnce(int count, ThrowingIntFunction task) throws Exception {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            CyclicBarrier startLine = new CyclicBarrier(count);
            List<Callable<Object>> jobs = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int index = i;
                jobs.add(() -> {
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
