package com.settletrust.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What makes a transfer legal, and what a legal one writes. Pure: it reads no store and
 * writes nothing, it is handed the balance it needs.
 *
 * <p>This exists so the in-memory ledger and the Postgres one cannot drift apart. They
 * differ in how they make a transfer atomic, a lock in one case and a database
 * transaction in the other, but the rules they enforce are this one class.
 */
final class TransferRules {

    private TransferRules() {
    }

    static void validate(Account source, Account target, Money amount, Money availableBalance) {
        if (!amount.isPositive()) {
            throw new TransferRejected(
                    TransferRejected.Reason.AMOUNT_NOT_POSITIVE, amount.toString());
        }
        if (source.id().equals(target.id())) {
            throw new TransferRejected(
                    TransferRejected.Reason.SAME_ACCOUNT, source.id().value());
        }
        // Both legs must be in the amount's currency. Moving EUR into a USD account is
        // an FX trade with a rate, a spread and its own audit trail, not a transfer.
        if (!source.currency().equals(amount.currency())
                || !target.currency().equals(amount.currency())) {
            throw new TransferRejected(TransferRejected.Reason.CURRENCY_MISMATCH,
                    source.currency() + " -> " + target.currency() + " for " + amount);
        }
        // The house account is the platform's own side of the books and is expected to
        // run negative; a customer account is not, so money must arrive before it leaves.
        if (!source.mayGoNegative() && availableBalance.isLessThan(amount)) {
            throw new TransferRejected(TransferRejected.Reason.INSUFFICIENT_FUNDS,
                    "balance " + availableBalance + ", requested " + amount);
        }
    }

    /** The debit and the credit, in that order. They sum to zero by construction. */
    static List<Entry> entryPair(
            UUID transferId, Account source, Account target, Money amount, Instant at) {
        return List.of(
                new Entry(UUID.randomUUID(), transferId, source.id(), amount.negated(), at),
                new Entry(UUID.randomUUID(), transferId, target.id(), amount, at));
    }

    static void requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("an idempotency key is required");
        }
    }
}
