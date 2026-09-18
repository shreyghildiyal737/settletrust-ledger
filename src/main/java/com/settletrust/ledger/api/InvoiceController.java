package com.settletrust.ledger.api;

import com.settletrust.ledger.Money;
import com.settletrust.ledger.invoice.Invoice;
import com.settletrust.ledger.invoice.InvoiceState;
import com.settletrust.ledger.invoice.InvoiceStatus;
import com.settletrust.ledger.invoice.InvoiceTransition;
import com.settletrust.ledger.invoice.PostgresInvoices;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The invoice lifecycle over HTTP. The transition table lives in the domain and the client
 * keeps a copy for greying out buttons; this endpoint is what actually decides.
 */
@RestController
@RequestMapping(path = "/api/v1/invoices")
class InvoiceController {

    private final PostgresInvoices invoices;
    private final Clock clock;

    InvoiceController(PostgresInvoices invoices, Clock clock) {
        this.invoices = invoices;
        this.clock = clock;
    }

    record OpenInvoiceRequest(
            String id,

            @NotBlank(message = "reference is required")
            String reference,

            @NotBlank(message = "sellerId is required")
            String sellerId,

            @NotBlank(message = "buyerId is required")
            String buyerId,

            long amountMinor,

            @NotBlank(message = "currency is required")
            @Pattern(regexp = "[A-Za-z]{3}", message = "currency must be a 3-letter code")
            String currency) {
    }

    /**
     * {@code expected} is the status the caller believes the invoice is in. Sending it
     * turns a blind command into a safe one: if the invoice moved since the caller read
     * it, the answer is 409 rather than an action taken on a stale view. It is optional,
     * because a background job acting on a query it just ran does not need it.
     */
    record TransitionRequest(
            @NotBlank(message = "to is required")
            String to,
            String expected,
            String reason) {
    }

    record InvoiceView(
            String id,
            String reference,
            String sellerId,
            String buyerId,
            long amountMinor,
            String currency,
            String status,
            int sequence,
            boolean readyForSettlement,
            List<String> blockedReasons,
            List<String> nextStates,
            Instant createdAt) {

        static InvoiceView of(InvoiceState state) {
            Invoice invoice = state.invoice();
            return new InvoiceView(
                    invoice.id(),
                    invoice.reference(),
                    invoice.sellerId(),
                    invoice.buyerId(),
                    invoice.amount().minorUnits(),
                    invoice.amount().currency(),
                    state.status().code(),
                    state.sequence(),
                    state.readyForSettlement(),
                    state.blockedReasons(),
                    state.nextStates().stream().map(InvoiceStatus::code).toList(),
                    invoice.createdAt());
        }
    }

    record TransitionView(
            UUID id,
            String invoiceId,
            int sequence,
            String from,
            String to,
            String reason,
            Instant occurredAt) {

        static TransitionView of(InvoiceTransition transition) {
            return new TransitionView(
                    transition.id(),
                    transition.invoiceId(),
                    transition.sequence(),
                    transition.from() == null ? null : transition.from().code(),
                    transition.to().code(),
                    transition.reason(),
                    transition.occurredAt());
        }
    }

    @PostMapping
    ResponseEntity<InvoiceView> open(@Valid @RequestBody OpenInvoiceRequest request) {
        Invoice invoice = fromClient(() -> new Invoice(
                request.id() == null || request.id().isBlank()
                        ? UUID.randomUUID().toString()
                        : request.id(),
                request.reference(),
                request.sellerId(),
                request.buyerId(),
                Money.of(request.amountMinor(), request.currency()),
                clock.instant()));

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(InvoiceView.of(invoices.open(invoice)));
    }

    @GetMapping("/{id}")
    InvoiceView invoice(@PathVariable("id") String id) {
        return InvoiceView.of(invoices.require(id));
    }

    @GetMapping("/{id}/transitions")
    List<TransitionView> history(@PathVariable("id") String id) {
        invoices.require(id);
        return invoices.history(id).stream().map(TransitionView::of).toList();
    }

    @PostMapping("/{id}/transitions")
    ResponseEntity<TransitionView> transition(
            @PathVariable("id") String id,
            @Valid @RequestBody TransitionRequest request) {

        InvoiceStatus to = fromClient(() -> InvoiceStatus.fromCode(request.to()));
        InvoiceStatus expected = request.expected() == null || request.expected().isBlank()
                ? null
                : fromClient(() -> InvoiceStatus.fromCode(request.expected()));

        InvoiceTransition move = invoices.transition(id, to, expected, request.reason());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(TransitionView.of(move));
    }

    /** As in {@link LedgerController}: only the client's own input becomes a 400. */
    private static <T> T fromClient(Supplier<T> conversion) {
        try {
            return conversion.get();
        } catch (IllegalArgumentException rejected) {
            throw new MalformedRequest(rejected.getMessage());
        }
    }
}
