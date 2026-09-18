package com.settletrust.ledger.invoice;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One recorded move. These are append-only, like the ledger's entries, and an invoice's
 * current status is the {@code to} of its highest sequence rather than a column anyone
 * overwrites. The whole history is therefore always answerable: not just where an invoice
 * is, but every step that got it there and when.
 *
 * <p>{@code from} is null for the first transition, where the invoice comes into being.
 */
public record InvoiceTransition(
        UUID id,
        String invoiceId,
        int sequence,
        InvoiceStatus from,
        InvoiceStatus to,
        String reason,
        Instant occurredAt) {

    public InvoiceTransition {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(invoiceId, "invoiceId must not be null");
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        if (sequence == 0 && from != null) {
            throw new IllegalArgumentException("the opening transition has no previous status");
        }
        if (sequence > 0 && from == null) {
            throw new IllegalArgumentException("only the opening transition may have no previous status");
        }
    }
}
