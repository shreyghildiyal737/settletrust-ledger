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
     * The balance the escrow contract holds, one entry per currency, as of the chain's
     * current head.
     *
     * <p>As of the head rather than a named block, which makes this a moving number. The
     * reconciler handles that by ordering its reads rather than by pinning a block: it
     * takes the database snapshot first and asks the chain second, so the chain answer is
     * never older than the ledger it is being compared against. That makes a reported
     * shortfall real, and pushes any timing artefact into the surplus, which is the
     * finding that can afford to be soft.
     */
    List<Money> heldOnChain();
}
