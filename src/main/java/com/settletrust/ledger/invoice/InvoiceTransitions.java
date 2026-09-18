package com.settletrust.ledger.invoice;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static com.settletrust.ledger.invoice.InvoiceStatus.BUYER_ACCEPTED;
import static com.settletrust.ledger.invoice.InvoiceStatus.CANCELLED;
import static com.settletrust.ledger.invoice.InvoiceStatus.DELIVERY_CONFIRMED;
import static com.settletrust.ledger.invoice.InvoiceStatus.DISPUTED;
import static com.settletrust.ledger.invoice.InvoiceStatus.DRAFT;
import static com.settletrust.ledger.invoice.InvoiceStatus.ESCROW_FUNDED;
import static com.settletrust.ledger.invoice.InvoiceStatus.ESCROW_PENDING;
import static com.settletrust.ledger.invoice.InvoiceStatus.EXPIRED;
import static com.settletrust.ledger.invoice.InvoiceStatus.FAILED;
import static com.settletrust.ledger.invoice.InvoiceStatus.FINANCING_APPROVED;
import static com.settletrust.ledger.invoice.InvoiceStatus.FINANCING_REJECTED;
import static com.settletrust.ledger.invoice.InvoiceStatus.FINANCING_REQUESTED;
import static com.settletrust.ledger.invoice.InvoiceStatus.FROZEN;
import static com.settletrust.ledger.invoice.InvoiceStatus.REJECTED;
import static com.settletrust.ledger.invoice.InvoiceStatus.RISK_REVIEW_COMPLETED;
import static com.settletrust.ledger.invoice.InvoiceStatus.RISK_REVIEW_PENDING;
import static com.settletrust.ledger.invoice.InvoiceStatus.SETTLED;
import static com.settletrust.ledger.invoice.InvoiceStatus.SETTLEMENT_PENDING;
import static com.settletrust.ledger.invoice.InvoiceStatus.SHIPMENT_PENDING;
import static com.settletrust.ledger.invoice.InvoiceStatus.SUBMITTED;

/**
 * Which moves are legal, and nothing else.
 *
 * <p>Ported from `src/lib/state-machine/invoice-transitions.ts` in the SettleTrust
 * frontend, state for state. The client keeps its copy so it can grey out a button
 * without a round trip, but that copy is now a convenience: this table decides, because
 * anything a browser enforces is a suggestion to whoever is not using the browser.
 */
public final class InvoiceTransitions {

    private static final Map<InvoiceStatus, Set<InvoiceStatus>> ALLOWED =
            new EnumMap<>(InvoiceStatus.class);

    static {
        ALLOWED.put(DRAFT, Set.of(SUBMITTED, CANCELLED));
        ALLOWED.put(SUBMITTED, Set.of(BUYER_ACCEPTED, REJECTED, RISK_REVIEW_PENDING, CANCELLED));
        ALLOWED.put(BUYER_ACCEPTED, Set.of(
                RISK_REVIEW_PENDING, FINANCING_REQUESTED, ESCROW_PENDING, DISPUTED, EXPIRED));
        ALLOWED.put(REJECTED, Set.of(CANCELLED));
        ALLOWED.put(RISK_REVIEW_PENDING, Set.of(RISK_REVIEW_COMPLETED, FAILED));
        ALLOWED.put(RISK_REVIEW_COMPLETED, Set.of(
                FINANCING_REQUESTED, FINANCING_APPROVED, FINANCING_REJECTED, ESCROW_PENDING, FROZEN));
        ALLOWED.put(FINANCING_REQUESTED, Set.of(FINANCING_APPROVED, FINANCING_REJECTED, FROZEN));
        ALLOWED.put(FINANCING_APPROVED, Set.of(ESCROW_PENDING, ESCROW_FUNDED, FROZEN));
        ALLOWED.put(FINANCING_REJECTED, Set.of(CANCELLED));
        ALLOWED.put(ESCROW_PENDING, Set.of(ESCROW_FUNDED, FAILED, EXPIRED, DISPUTED));
        ALLOWED.put(ESCROW_FUNDED, Set.of(
                SHIPMENT_PENDING, DELIVERY_CONFIRMED, DISPUTED, FROZEN, EXPIRED));
        ALLOWED.put(SHIPMENT_PENDING, Set.of(DELIVERY_CONFIRMED, DISPUTED, FROZEN, EXPIRED));
        ALLOWED.put(DELIVERY_CONFIRMED, Set.of(SETTLEMENT_PENDING, DISPUTED, FROZEN));
        ALLOWED.put(SETTLEMENT_PENDING, Set.of(SETTLED, FAILED));
        ALLOWED.put(SETTLED, Set.of());
        ALLOWED.put(DISPUTED, Set.of(BUYER_ACCEPTED, ESCROW_FUNDED, CANCELLED));
        ALLOWED.put(FROZEN, Set.of(BUYER_ACCEPTED, ESCROW_FUNDED, CANCELLED));
        ALLOWED.put(EXPIRED, Set.of(CANCELLED));
        ALLOWED.put(CANCELLED, Set.of());
        ALLOWED.put(FAILED, Set.of(ESCROW_PENDING, SETTLEMENT_PENDING, CANCELLED));
    }

    private InvoiceTransitions() {
    }

    public static Set<InvoiceStatus> allowedFrom(InvoiceStatus status) {
        Set<InvoiceStatus> allowed = ALLOWED.get(status);
        if (allowed == null) {
            // Only reachable if a status is added to the enum and not to this table, and
            // failing loudly is far better than silently allowing or forbidding every move.
            throw new IllegalStateException("no transitions defined for " + status);
        }
        return allowed;
    }

    public static boolean isAllowed(InvoiceStatus from, InvoiceStatus to) {
        return allowedFrom(from).contains(to);
    }
}
