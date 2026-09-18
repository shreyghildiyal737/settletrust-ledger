package com.settletrust.ledger;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Moves money between two accounts in the ledger.
 *
 * <p>Two properties are the point of this class. A transfer either writes both of its
 * entries or neither, so a balance is never observed mid-move; and a transfer carrying
 * an idempotency key already seen returns the original outcome without moving money
 * again, because networks, phones and users all retry.
 */
public class TransferService {

    private final Ledger ledger;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Keys seen, mapped to what happened. In Postgres this becomes a table with a unique
     * constraint on the key, written inside the same transaction as the entries, so a
     * crash between the two is impossible rather than merely unlikely.
     */
    private final Map<String, Transfer> seenKeys = new HashMap<>();

    public TransferService(Ledger ledger) {
        this(ledger, Clock.systemUTC());
    }

    public TransferService(Ledger ledger, Clock clock) {
        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public Transfer transfer(AccountId from, AccountId to, Money amount, String idempotencyKey) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("an idempotency key is required");
        }

        lock.lock();
        try {
            // Checked first and under the same lock as the write, so two concurrent
            // retries of one payment cannot both find the key absent and both pay.
            Transfer alreadyDone = seenKeys.get(idempotencyKey);
            if (alreadyDone != null) {
                return alreadyDone.asReplay();
            }

            Account source = ledger.require(from);
            Account target = ledger.require(to);
            validate(source, target, amount);

            Transfer transfer = post(source, target, amount, idempotencyKey);
            seenKeys.put(idempotencyKey, transfer);
            return transfer;
        } finally {
            lock.unlock();
        }
    }

    private void validate(Account source, Account target, Money amount) {
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
        if (!source.mayGoNegative()) {
            Money available = ledger.balanceOf(source.id());
            if (available.isLessThan(amount)) {
                throw new TransferRejected(TransferRejected.Reason.INSUFFICIENT_FUNDS,
                        "balance " + available + ", requested " + amount);
            }
        }
    }

    private Transfer post(Account source, Account target, Money amount, String idempotencyKey) {
        UUID transferId = UUID.randomUUID();
        Instant at = clock.instant();

        Entry debit = new Entry(UUID.randomUUID(), transferId, source.id(), amount.negated(), at);
        Entry credit = new Entry(UUID.randomUUID(), transferId, target.id(), amount, at);
        ledger.append(debit, credit);

        return new Transfer(transferId, idempotencyKey, source.id(), target.id(), amount, at, false);
    }
}
