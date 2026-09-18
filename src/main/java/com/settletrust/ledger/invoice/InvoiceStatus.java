package com.settletrust.ledger.invoice;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Where an invoice is in its life.
 *
 * <p>The codes are the same strings the SettleTrust frontend already uses, so the two
 * halves name the same state identically and nothing has to be translated in between.
 * The enum is the authority: a status that is not one of these cannot be stored.
 */
public enum InvoiceStatus {

    DRAFT("draft"),
    SUBMITTED("submitted"),
    BUYER_ACCEPTED("buyer_accepted"),
    REJECTED("rejected"),
    RISK_REVIEW_PENDING("risk_review_pending"),
    RISK_REVIEW_COMPLETED("risk_review_completed"),
    FINANCING_REQUESTED("financing_requested"),
    FINANCING_APPROVED("financing_approved"),
    FINANCING_REJECTED("financing_rejected"),
    ESCROW_PENDING("escrow_pending"),
    ESCROW_FUNDED("escrow_funded"),
    SHIPMENT_PENDING("shipment_pending"),
    DELIVERY_CONFIRMED("delivery_confirmed"),
    SETTLEMENT_PENDING("settlement_pending"),
    SETTLED("settled"),
    DISPUTED("disputed"),
    FROZEN("frozen"),
    EXPIRED("expired"),
    CANCELLED("cancelled"),
    FAILED("failed");

    private static final Map<String, InvoiceStatus> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toMap(InvoiceStatus::code, Function.identity()));

    private final String code;

    InvoiceStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static InvoiceStatus fromCode(String code) {
        InvoiceStatus status = BY_CODE.get(code == null ? null : code.toLowerCase());
        if (status == null) {
            throw new IllegalArgumentException("unknown invoice status: " + code);
        }
        return status;
    }

    /** A state with nowhere left to go. An invoice here is finished, for good or ill. */
    public boolean isTerminal() {
        return InvoiceTransitions.allowedFrom(this).isEmpty();
    }
}
