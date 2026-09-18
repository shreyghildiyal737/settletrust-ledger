package com.settletrust.ledger.invoice;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What an invoice is allowed to do next, and whether it is ready to be paid. Pure: it
 * reads nothing and writes nothing, so every rule here is testable in microseconds.
 */
public final class InvoiceRules {

    private static final Set<InvoiceStatus> ESCROW_IS_FUNDED = Set.of(
            InvoiceStatus.ESCROW_FUNDED,
            InvoiceStatus.SHIPMENT_PENDING,
            InvoiceStatus.DELIVERY_CONFIRMED,
            InvoiceStatus.SETTLEMENT_PENDING);

    private static final Set<InvoiceStatus> DELIVERY_IS_CONFIRMED = Set.of(
            InvoiceStatus.DELIVERY_CONFIRMED,
            InvoiceStatus.SETTLEMENT_PENDING);

    /**
     * States an invoice cannot simply be declared to be in, because reaching them means
     * money moved. Marking an invoice settled without the entries to prove it is exactly
     * the kind of unbacked claim this whole service exists to make impossible, so these
     * two are reachable only through the settlement operations, which write the transition
     * and the transfer in one transaction.
     */
    private static final Set<InvoiceStatus> REQUIRE_MONEY_MOVEMENT = Set.of(
            InvoiceStatus.ESCROW_FUNDED,
            InvoiceStatus.SETTLED);

    private InvoiceRules() {
    }

    public static boolean requiresMoneyMovement(InvoiceStatus to) {
        return REQUIRE_MONEY_MOVEMENT.contains(to);
    }

    static void requireLegal(String invoiceId, InvoiceStatus from, InvoiceStatus to) {
        if (from == to) {
            throw new InvoiceTransitionRejected(
                    InvoiceTransitionRejected.Reason.ILLEGAL_TRANSITION,
                    invoiceId + " is already " + to.code());
        }
        if (from.isTerminal()) {
            throw new InvoiceTransitionRejected(
                    InvoiceTransitionRejected.Reason.TERMINAL_STATE,
                    invoiceId + " is " + from.code() + ", which is final");
        }
        if (!InvoiceTransitions.isAllowed(from, to)) {
            throw new InvoiceTransitionRejected(
                    InvoiceTransitionRejected.Reason.ILLEGAL_TRANSITION,
                    invoiceId + ": " + from.code() + " cannot become " + to.code());
        }
    }

    /**
     * Why this invoice cannot be settled yet, in words a person can act on. An empty list
     * means it is ready.
     *
     * <p>The wording matches the frontend's `getInvoiceBlockedReasons` so both halves say
     * the same thing to a user. Note that because a status is a single value, "escrow
     * funded" and "delivery confirmed" are not two independent facts here: reaching
     * {@code delivery_confirmed} already implies the escrow was funded earlier in the
     * history. The list stays split because each line tells the reader something
     * different about what is missing.
     */
    public static List<String> blockedReasons(InvoiceStatus status) {
        List<String> reasons = new ArrayList<>();

        if (status == InvoiceStatus.FROZEN) {
            reasons.add("Invoice is frozen by compliance.");
        }
        if (status == InvoiceStatus.DISPUTED) {
            reasons.add("Invoice has an open dispute.");
        }
        if (status == InvoiceStatus.EXPIRED) {
            reasons.add("Invoice has expired.");
        }
        if (!ESCROW_IS_FUNDED.contains(status)) {
            reasons.add("Escrow is not funded.");
        }
        if (!DELIVERY_IS_CONFIRMED.contains(status)) {
            reasons.add("Delivery has not been confirmed.");
        }
        return List.copyOf(reasons);
    }

    public static boolean readyForSettlement(InvoiceStatus status) {
        return blockedReasons(status).isEmpty();
    }
}
