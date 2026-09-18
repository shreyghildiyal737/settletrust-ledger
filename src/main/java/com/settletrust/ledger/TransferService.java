package com.settletrust.ledger;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Moves money between two accounts in an in-memory {@link Ledger}.
 *
 * <p>Two properties are the point of this class. A transfer either writes both of its
 * entries or neither, so a balance is never observed mid-move; and a transfer carrying
 * an idempotency key already seen returns the original outcome without moving money
 * again, because networks, phones and users all retry.
 *
 * <p>Atomicity here comes from a lock. {@link PostgresTransferService} does the same job
 * with a database transaction, and both enforce the identical {@link TransferRules}.
 */
public class TransferService implements Transfers {

    private final Ledger ledger;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Keys seen, mapped to what happened. In Postgres this is a unique constraint on the
     * transfer table, written inside the same transaction as the entries, so a crash
     * between the two is impossible rather than merely unlikely.
     */
    private final Map<String, Transfer> seenKeys = new HashMap<>();

    public TransferService(Ledger ledger) {
        this(ledger, Clock.systemUTC());
    }

    public TransferService(Ledger ledger, Clock clock) {
        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Transfer transfer(AccountId from, AccountId to, Money amount, String idempotencyKey) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        TransferRules.requireKey(idempotencyKey);

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
            TransferRules.validate(source, target, amount, ledger.balanceOf(from));

            UUID transferId = UUID.randomUUID();
            Instant at = clock.instant();
            List<Entry> pair = TransferRules.entryPair(transferId, source, target, amount, at);
            ledger.append(pair.get(0), pair.get(1));

            Transfer transfer = new Transfer(
                    transferId, idempotencyKey, source.id(), target.id(), amount, at, false);
            seenKeys.put(idempotencyKey, transfer);
            return transfer;
        } finally {
            lock.unlock();
        }
    }
}
