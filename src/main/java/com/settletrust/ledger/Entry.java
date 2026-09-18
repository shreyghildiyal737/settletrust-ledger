package com.settletrust.ledger;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One half of a double-entry pair. The amount is signed: negative debits the account,
 * positive credits it. The two entries of a transfer always sum to zero, which is the
 * invariant the whole ledger exists to protect.
 *
 * <p>Entries are never updated or deleted. A correction is a new pair in the opposite
 * direction, because an overwritten entry has destroyed the evidence of why a balance
 * changed and a settlement platform cannot destroy that evidence.
 */
public record Entry(
        UUID entryId,
        UUID transferId,
        AccountId accountId,
        Money amount,
        Instant recordedAt) {

    public Entry {
        Objects.requireNonNull(entryId, "entryId must not be null");
        Objects.requireNonNull(transferId, "transferId must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        if (amount.minorUnits() == 0L) {
            throw new IllegalArgumentException("an entry of zero records nothing");
        }
    }

    public boolean isDebit() {
        return amount.isNegative();
    }

    public boolean isCredit() {
        return amount.isPositive();
    }
}
