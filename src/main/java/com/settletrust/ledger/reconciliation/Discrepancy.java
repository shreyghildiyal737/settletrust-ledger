package com.settletrust.ledger.reconciliation;

import com.settletrust.ledger.Money;

import java.util.Objects;
import java.util.Optional;

/**
 * One thing that did not add up, with enough detail to go and look at it.
 *
 * <p>{@code subject} is the identifier of whatever is wrong: a transaction hash and log
 * index, an account id, a currency code. It is what an operator pastes into a query, so
 * it carries no prose.
 *
 * <p>{@code expected} and {@code found} are absent for the findings that are not about a
 * quantity. A transfer with three entries instead of two is wrong in a way no pair of
 * amounts describes, and inventing zeroes to fill the fields would make it look like a
 * balance problem.
 */
public record Discrepancy(
        DiscrepancyKind kind, String subject, String detail, Money expected, Money found) {

    public Discrepancy {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
        if ((expected == null) != (found == null)) {
            throw new IllegalArgumentException(
                    "expected and found are both amounts or neither is: " + kind + " " + subject);
        }
    }

    public static Discrepancy of(DiscrepancyKind kind, String subject, String detail) {
        return new Discrepancy(kind, subject, detail, null, null);
    }

    public static Discrepancy mismatch(
            DiscrepancyKind kind, String subject, String detail, Money expected, Money found) {
        Objects.requireNonNull(expected, "expected must not be null");
        Objects.requireNonNull(found, "found must not be null");
        return new Discrepancy(kind, subject, detail, expected, found);
    }

    public Optional<Money> expectedAmount() {
        return Optional.ofNullable(expected);
    }

    public Optional<Money> foundAmount() {
        return Optional.ofNullable(found);
    }

    /**
     * The currency this finding is denominated in, if it is about money at all.
     *
     * <p>Taken from the expected side, which is the authoritative one by definition. The
     * two sides can disagree about the currency as well as the amount; when they do, the
     * detail says both, because a single column cannot.
     */
    public Optional<String> currency() {
        return expectedAmount().map(Money::currency);
    }
}
