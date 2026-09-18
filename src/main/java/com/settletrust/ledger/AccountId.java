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

    public static AccountId of(String value) {
        return new AccountId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
