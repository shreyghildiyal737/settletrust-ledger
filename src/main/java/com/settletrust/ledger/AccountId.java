package com.settletrust.ledger;

import java.util.Objects;

/**
 * An account's identity. A wrapper rather than a bare String so that an account id
 * and an invoice id cannot be passed to each other's parameters by mistake.
 */
public record AccountId(String value) {

    public AccountId {
        Objects.requireNonNull(value, "account id must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("account id must not be blank");
        }
    }

    /**
     * An id from inside the service, trusted as given. Reading a row back out of the
     * database goes through here, so an account the platform opened for itself keeps
     * working however it was named.
     */
    public static AccountId of(String value) {
        return new AccountId(value);
    }

    /**
     * An id chosen by an outside caller, refused if it claims a namespace the platform
     * keeps for its own books.
     *
     * <p>Without this an account could be opened called {@code escrow:INV-2026-0041}, and
     * the next time that invoice was funded the settlement code would find an account
     * already sitting under the name it was about to create and use it. Reconciliation
     * would go on recognising it as platform-owned escrow, because the prefix is the only
     * thing it has to go on.
     */
    public static AccountId external(String value) {
        AccountId id = new AccountId(value);
        if (InternalAccounts.isReserved(id.value())) {
            throw new IllegalArgumentException(
                    "account id must not begin with a prefix the platform reserves: " + value);
        }
        return id;
    }

    @Override
    public String toString() {
        return value;
    }
}
