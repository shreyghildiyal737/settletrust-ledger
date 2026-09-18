package com.settletrust.ledger;

import java.util.Objects;

/**
 * An amount in minor units of a single currency: 1250 EUR minor units is twelve euro
 * fifty. Money is never held as a floating point number, because a settlement that is
 * out by a fraction of a cent is a settlement that fails reconciliation.
 *
 * <p>Currency is carried on the amount rather than assumed globally: SettleTrust
 * settles cross-border, so two accounts in different currencies is the normal case,
 * not the exception. Converting between them is an FX concern and deliberately not
 * part of a transfer.
 */
public record Money(long minorUnits, String currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
        if (currency.length() != 3) {
            throw new IllegalArgumentException("currency must be a 3-letter code, got: " + currency);
        }
        currency = currency.toUpperCase();
    }

    public static Money of(long minorUnits, String currency) {
        return new Money(minorUnits, currency);
    }

    public static Money zero(String currency) {
        return new Money(0L, currency);
    }

    public boolean isPositive() {
        return minorUnits > 0L;
    }

    public boolean isNegative() {
        return minorUnits < 0L;
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money negated() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    public boolean isLessThan(Money other) {
        requireSameCurrency(other);
        return minorUnits < other.minorUnits;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "cannot combine " + currency + " with " + other.currency);
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    @Override
    public String toString() {
        return minorUnits + " " + currency;
    }
}
