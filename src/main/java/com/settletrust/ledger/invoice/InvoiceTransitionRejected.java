package com.settletrust.ledger.invoice;

/**
 * A move the state machine refused. As with the ledger's refusals, the reason is an enum
 * so a caller can branch on it and the edge can map it to a status code without reading
 * English.
 */
public class InvoiceTransitionRejected extends RuntimeException {

    public enum Reason {
        UNKNOWN_INVOICE,
        ILLEGAL_TRANSITION,
        TERMINAL_STATE,
        /** The invoice moved between the caller reading it and trying to act on it. */
        STATE_CHANGED
    }

    private final Reason reason;

    public InvoiceTransitionRejected(Reason reason, String detail) {
        super(reason + ": " + detail);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
