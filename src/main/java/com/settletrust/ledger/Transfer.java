package com.settletrust.ledger;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The record of a completed transfer, and what a replay of the same idempotency key
 * returns. {@code replayed} is false the first time and true on every repeat, so a
 * caller can tell the difference without the outcome changing.
 */
public record Transfer(
        UUID transferId,
        String idempotencyKey,
        AccountId from,
        AccountId to,
        Money amount,
        Instant completedAt,
        boolean replayed) {

    public Transfer {
        Objects.requireNonNull(transferId, "transferId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
    }

    Transfer asReplay() {
        return new Transfer(transferId, idempotencyKey, from, to, amount, completedAt, true);
    }
}
