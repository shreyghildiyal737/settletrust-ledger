package com.settletrust.ledger.chain;

import com.settletrust.ledger.Money;

import java.util.List;

/**
 * What the escrow contract actually holds, asked of the chain itself.
 *
 * <p>Separate from {@link ChainSource} on purpose. That interface is the chain reduced to
 * the three questions the watcher has to ask while it is following events. This is a
 * different question, asked by a different caller for a different reason, and the two
 * should be implementable apart: a reconciler that only needs a balance should not be
 * handed the ability to replay history.
 *
 * <p>It exists because of a real hole in the reconciler. Every other check compares the
 * ledger against {@code chain_observation}, and the watcher wrote both. If the watcher
 * misread the chain, credited an event twice or invented one, the two sides agree
 * perfectly and are both wrong. Only the chain can settle that, and only by being asked
 * directly.
 */
public interface EscrowReserves {

    /**
     * The balance the escrow contract holds, one entry per currency, as of a named block.
     *
     * <p><b>The block is a parameter because the alternative was wrong.</b> This used to
     * answer for the chain's current head, on the reasoning that asking the chain last
     * meant its answer was never older than the ledger it was compared against, so any
     * timing artefact would land on the surplus rather than on the alarm.
     *
     * <p>That reasoning had a hole, and a soak found it in four minutes. The ledger's
     * records describe the chain only as far as the watcher has read. A deposit that has
     * landed on chain but that the watcher has not polled yet is in the contract's balance
     * and in no record at all, not even as a pending observation, so nothing explains it
     * and the run reports an unaccounted surplus that is simply the poll interval. It is
     * the same mistake as scanning logs to a moving {@code latest}: two sides of a
     * comparison describing different heights.
     *
     * <p>So the caller names the height, and passes the one its own records describe.
     *
     * @param asOfBlock the block to read the balance at. Zero is a legitimate answer of
     *                  "nothing has been read yet" rather than an error: a watcher that
     *                  has never run has observed nothing, so there is nothing to compare
     *                  and no finding to make. Whether the watcher is alive is a different
     *                  question, answered by how old its last pass is.
     */
    List<Money> heldOnChain(long asOfBlock);
}
