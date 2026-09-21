package com.settletrust.ledger.chain;

import java.util.List;
import java.util.Optional;

/**
 * The chain, reduced to the three questions the watcher actually has to ask.
 *
 * <p>An interface rather than a node client, because the interesting logic is confirmation
 * depth and reorganisation handling, and that logic should be provable without a node. The
 * tests drive a fake chain that can be made to reorganise on demand, which is the one
 * scenario a real testnet will not produce when you need it to.
 */
public interface ChainSource {

    /** The number of the newest block the node considers canonical. */
    long headBlockNumber();

    /**
     * Escrow deposits in blocks from {@code fromBlock} to {@code toBlock}, both included.
     *
     * <p>The caller names both ends rather than letting this read the head itself. The
     * caller is the one that records how far the chain has been read, and a range bounded
     * by a head nobody else saw is a second horizon: the cursor would then be saved from
     * one head while the scan was bounded by another, and any block between the two would
     * be marked read without ever being looked at.
     */
    List<ChainDeposit> depositsFrom(long fromBlock, long toBlock);

    /**
     * The hash of the canonical block at this height, or empty if the chain is not that
     * long. Comparing it against a hash recorded earlier is how a reorganisation is
     * detected: same height, different block.
     */
    Optional<String> blockHashAt(long blockNumber);
}
