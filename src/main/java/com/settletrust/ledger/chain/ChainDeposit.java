package com.settletrust.ledger.chain;

import com.settletrust.ledger.Money;

import java.util.Objects;

/**
 * A deposit into the escrow contract, as the chain reported it.
 *
 * <p>Identified by transaction hash and log index together, because one transaction can
 * emit several events and the hash alone would collide. That pair is the natural
 * idempotency key for everything downstream: the chain will hand the same event over
 * again after a restart, a re-scan, or a reorg that puts it back.
 *
 * <p>The block hash is carried as well as the number. A block number is a position and can
 * be reused by a different block after a reorganisation; the hash is the identity, and
 * comparing it is how the watcher notices that what it acted on is no longer true.
 */
public record ChainDeposit(
        String txHash,
        int logIndex,
        long blockNumber,
        String blockHash,
        String invoiceId,
        Money amount) {

    public ChainDeposit {
        requireText(txHash, "txHash");
        requireText(blockHash, "blockHash");
        requireText(invoiceId, "invoiceId");
        Objects.requireNonNull(amount, "amount must not be null");
        if (logIndex < 0) {
            throw new IllegalArgumentException("logIndex must not be negative");
        }
        if (blockNumber < 0) {
            throw new IllegalArgumentException("blockNumber must not be negative");
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a deposit must be for a positive amount");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
