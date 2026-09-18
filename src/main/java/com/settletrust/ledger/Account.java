package com.settletrust.ledger;

import java.util.Objects;

/**
 * An account is an identity, the currency it is denominated in, and what it is allowed
 * to do. It carries no balance field on purpose: the balance is derived from the entries
 * written against it, so there is no number here that could drift away from the ledger.
 */
public record Account(AccountId id, String currency, Kind kind) {

    /**
     * Whether the account may hold a negative balance.
     *
     * <p>A {@code CUSTOMER} account may not: money has to arrive before it can leave.
     * A {@code HOUSE} account may, and must, because it is the platform's own side of
     * the books. When a customer is funded from outside, that money is credited to them
     * and debited from the house account, which is exactly the platform recording what
     * it now owes. Without this distinction the first deposit into an empty system has
     * nowhere legitimate to come from.
     */
    public enum Kind {
        CUSTOMER,
        HOUSE
    }

    public Account {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        if (currency.length() != 3) {
            throw new IllegalArgumentException("currency must be a 3-letter code, got: " + currency);
        }
        currency = currency.toUpperCase();
    }

    public static Account customer(String id, String currency) {
        return new Account(AccountId.of(id), currency, Kind.CUSTOMER);
    }

    public static Account house(String id, String currency) {
        return new Account(AccountId.of(id), currency, Kind.HOUSE);
    }

    public boolean mayGoNegative() {
        return kind == Kind.HOUSE;
    }
}
