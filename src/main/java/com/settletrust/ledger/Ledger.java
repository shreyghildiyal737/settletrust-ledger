package com.settletrust.ledger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An append-only store of accounts and the entries written against them.
 *
 * <p>This implementation is in memory and is not itself synchronised: {@link
 * TransferService} owns the lock, because the unit that has to be atomic is the whole
 * read-check-write of a transfer, not any single call made here. Once this moves behind
 * Postgres the same boundary holds, with the database transaction taking the place of
 * the lock.
 */
public class Ledger {

    private final Map<AccountId, Account> accounts = new HashMap<>();
    private final List<Entry> entries = new ArrayList<>();

    public void open(Account account) {
        if (accounts.putIfAbsent(account.id(), account) != null) {
            throw new IllegalStateException("account already open: " + account.id());
        }
    }

    public Optional<Account> find(AccountId id) {
        return Optional.ofNullable(accounts.get(id));
    }

    public Account require(AccountId id) {
        return find(id).orElseThrow(() -> new TransferRejected(
                TransferRejected.Reason.UNKNOWN_ACCOUNT, id.value()));
    }

    void append(Entry... pair) {
        Collections.addAll(entries, pair);
    }

    /**
     * The balance is a projection over the entries, recomputed rather than stored. That
     * is O(n) in the number of entries and is the right trade while this is in memory:
     * there is no stored number that can disagree with the log. Persisting it will need
     * a running balance or periodic snapshots, and that is a deliberate later decision.
     */
    public Money balanceOf(AccountId id) {
        Account account = require(id);
        long total = 0L;
        for (Entry entry : entries) {
            if (entry.accountId().equals(id)) {
                total = Math.addExact(total, entry.amount().minorUnits());
            }
        }
        return Money.of(total, account.currency());
    }

    /** Every entry ever written, oldest first. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public List<Entry> entriesOf(AccountId id) {
        return entries.stream().filter(e -> e.accountId().equals(id)).toList();
    }

    /**
     * The books balance when every entry in a currency sums to zero. Money entering the
     * system from outside arrives as a transfer from a funding account, so this holds
     * across the whole ledger and not only per transfer.
     */
    public long sumOfAllEntries(String currency) {
        long total = 0L;
        for (Entry entry : entries) {
            if (entry.amount().currency().equals(currency)) {
                total = Math.addExact(total, entry.amount().minorUnits());
            }
        }
        return total;
    }
}
