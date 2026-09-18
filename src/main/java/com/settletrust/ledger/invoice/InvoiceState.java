package com.settletrust.ledger.invoice;

import java.util.List;

/**
 * An invoice together with where it has reached. {@code sequence} is the number of the
 * transition that put it there, and a caller that intends to move the invoice should send
 * the status it saw back with the command, so the server can tell whether anything moved
 * underneath it.
 */
public record InvoiceState(Invoice invoice, InvoiceStatus status, int sequence) {

    public List<String> blockedReasons() {
        return InvoiceRules.blockedReasons(status);
    }

    public boolean readyForSettlement() {
        return InvoiceRules.readyForSettlement(status);
    }

    public List<InvoiceStatus> nextStates() {
        return InvoiceTransitions.allowedFrom(status).stream()
                .sorted()
                .toList();
    }
}
