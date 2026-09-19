package com.settletrust.ledger.settlement;

/**
 * The idempotency keys settlement writes, in one place.
 *
 * <p>These strings are a contract between the code that writes a transfer and the code
 * that later looks for it: the reconciler finds a deposit's transfer by rebuilding its
 * key. Two copies of {@code "chain-deposit:" + txHash + ":" + logIndex} in two packages
 * would agree until one of them changed, and the symptom would be a reconciliation report
 * claiming money had gone missing.
 */
public final class SettlementKeys {

    public static final String CHAIN_DEPOSIT_PREFIX = "chain-deposit:";
    public static final String CHAIN_REVERSAL_PREFIX = "chain-reversal:";
    public static final String ESCROW_FUND_PREFIX = "escrow-fund:";
    public static final String INVOICE_SETTLE_PREFIX = "invoice-settle:";

    private SettlementKeys() {
    }

    public static String chainDeposit(String txHash, int logIndex) {
        return CHAIN_DEPOSIT_PREFIX + txHash + ":" + logIndex;
    }

    public static String chainReversal(String txHash, int logIndex) {
        return CHAIN_REVERSAL_PREFIX + txHash + ":" + logIndex;
    }

    public static String escrowFund(String invoiceId) {
        return ESCROW_FUND_PREFIX + invoiceId;
    }

    public static String invoiceSettle(String invoiceId) {
        return INVOICE_SETTLE_PREFIX + invoiceId;
    }
}
