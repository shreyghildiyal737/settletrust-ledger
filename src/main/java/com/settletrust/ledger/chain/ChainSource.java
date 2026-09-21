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
    Scan depositsFrom(long fromBlock, long toBlock);

    /**
     * What one scan of a block range found.
     *
     * <p>The logs that could not be read are carried separately rather than thrown,
     * because a single one of them is otherwise enough to stop the rail for everybody.
     * The contract takes a bytes32 from anyone, so anyone can emit an event whose invoice
     * id is not UTF-8, and a decode that throws mid-scan takes the whole pass with it,
     * on that pass and on every pass afterwards, since the log stays in its block for
     * ever.
     *
     * <p>They are counted rather than dropped for the usual reason: silence about
     * something nobody looked at is indistinguishable from silence about something that
     * was fine.
     *
     * @param undecodable the {@code txHash:logIndex} of each log that could not be read
     */
    record Scan(List<ChainDeposit> deposits, List<String> undecodable) {

        public Scan {
            deposits = List.copyOf(deposits);
            undecodable = List.copyOf(undecodable);
        }

        static Scan of(List<ChainDeposit> deposits) {
            return new Scan(deposits, List.of());
        }
    }

    /**
     * The hash of the canonical block at this height, or empty if the chain is not that
     * long. Comparing it against a hash recorded earlier is how a reorganisation is
     * detected: same height, different block.
     */
    Optional<String> blockHashAt(long blockNumber);
}
