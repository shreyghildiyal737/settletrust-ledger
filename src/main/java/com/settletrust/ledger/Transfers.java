package com.settletrust.ledger;

/**
 * Moving money, independent of where the ledger is kept. The in-memory implementation is
 * the one the unit tests drive; the Postgres one is what runs. Both are held to the same
 * tests, so "it works in memory" is never allowed to mean something different from "it
 * works".
 */
public interface Transfers {

    /**
     * Moves {@code amount} from one account to another, writing a balanced pair of
     * entries, or refuses.
     *
     * <p>Repeating a call with an {@code idempotencyKey} already seen returns the
     * original transfer and moves nothing.
     *
     * @throws TransferRejected         if the transfer is not legal
     * @throws IllegalArgumentException if the idempotency key is missing or blank
     */
    Transfer transfer(AccountId from, AccountId to, Money amount, String idempotencyKey);
}
