package com.settletrust.ledger.settlement;

/**
 * A deposit names an invoice that exists and cannot be applied to it.
 *
 * <p>Distinct from {@link com.settletrust.ledger.invoice.InvoiceTransitionRejected}, and
 * the distinction decides what the watcher does next. A rejected transition means "not
 * yet": the invoice has steps of its own to finish and the deposit should be tried again.
 * This means "not ever": the fault is in what arrived rather than in when it arrived, and
 * no number of retries changes an amount or a currency that has already been mined.
 */
public class ChainDepositMismatch extends RuntimeException {

    public ChainDepositMismatch(String message) {
        super(message);
    }
}
