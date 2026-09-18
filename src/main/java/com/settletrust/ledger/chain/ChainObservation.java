package com.settletrust.ledger.chain;

import com.settletrust.ledger.Money;

import java.time.Instant;

/** A deposit the watcher has recorded, and what it has done about it so far. */
public record ChainObservation(
        String txHash,
        int logIndex,
        long blockNumber,
        String blockHash,
        String invoiceId,
        Money amount,
        ObservationStatus status,
        Instant firstSeen,
        Instant settledAt) {

    /** How many blocks deep this one is, given the current head. */
    public long depth(long headBlockNumber) {
        return headBlockNumber - blockNumber + 1;
    }
}
