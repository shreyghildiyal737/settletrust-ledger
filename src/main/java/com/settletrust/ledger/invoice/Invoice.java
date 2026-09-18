package com.settletrust.ledger.invoice;

import com.settletrust.ledger.Money;

import java.time.Instant;
import java.util.Objects;

/**
 * The invoice itself: the facts that do not change once it exists. Where it has got to is
 * not here, because that is derived from its transitions.
 */
public record Invoice(
        String id,
        String reference,
        String sellerId,
        String buyerId,
        Money amount,
        Instant createdAt) {

    public Invoice {
        requireText(id, "id");
        requireText(reference, "reference");
        requireText(sellerId, "sellerId");
        requireText(buyerId, "buyerId");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("an invoice must be for a positive amount");
        }
        if (sellerId.equals(buyerId)) {
            throw new IllegalArgumentException("an invoice needs two different parties");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
