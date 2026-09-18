package com.settletrust.ledger;

/**
 * A transfer the ledger refused. The reason is an enum rather than a message so that
 * callers can branch on it and an API layer can map it to a status code without parsing
 * English.
 */
public class TransferRejected extends RuntimeException {

    public enum Reason {
        UNKNOWN_ACCOUNT,
        AMOUNT_NOT_POSITIVE,
        SAME_ACCOUNT,
        CURRENCY_MISMATCH,
        INSUFFICIENT_FUNDS
    }

    private final Reason reason;

    public TransferRejected(Reason reason, String detail) {
        super(reason + ": " + detail);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
