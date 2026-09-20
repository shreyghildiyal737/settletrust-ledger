package com.settletrust.ledger.chain;

import com.settletrust.ledger.invoice.InvoiceTransitionRejected;
import com.settletrust.ledger.settlement.InvoiceSettlement;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Turns what happened on a chain into what happened in the ledger.
 *
 * <p>Three problems make this harder than reading a queue, and each one shapes the code
 * below.
 *
 * <ul>
 *   <li><b>The same event arrives more than once.</b> After a restart, an overlapping scan
 *       or a reorganisation, the chain will hand over deposits already seen. The
 *       transaction hash and log index are the identity, and every write downstream is
 *       keyed on them.</li>
 *   <li><b>A new block is not yet a fact.</b> Crediting a deposit the instant it appears
 *       means crediting money that can still evaporate, so nothing is acted on until it is
 *       buried under {@code confirmations} blocks.</li>
 *   <li><b>The chain can take it back anyway.</b> A block number is a position, not an
 *       identity. If the hash at that height has changed, what was credited is no longer
 *       true and has to be reversed, which here means new entries in the opposite
 *       direction rather than deleted ones.</li>
 * </ul>
 */
public class EscrowWatcher {

    private static final Logger log = LoggerFactory.getLogger(EscrowWatcher.class);

    private final DSLContext dsl;
    private final PostgresChainObservations observations;
    private final InvoiceSettlement settlement;
    private final int confirmations;

    public EscrowWatcher(
            DSLContext dsl,
            PostgresChainObservations observations,
            InvoiceSettlement settlement,
            int confirmations) {
        this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
        this.observations = Objects.requireNonNull(observations, "observations must not be null");
        this.settlement = Objects.requireNonNull(settlement, "settlement must not be null");
        if (confirmations < 1) {
            throw new IllegalArgumentException("confirmations must be at least 1");
        }
        this.confirmations = confirmations;
    }

    /** What one pass over the chain did. */
    public record PollResult(
            long headBlock,
            int recorded,
            int reanchored,
            int confirmed,
            int deferred,
            int abandoned,
            int reversed,
            /** Deposits naming an invoice this platform never issued. */
            int unattributed) {
    }

    /**
     * Reads the chain once and brings the ledger up to date with it.
     *
     * <p>Safe to call repeatedly and safe to interrupt: everything it does is either
     * idempotent or recorded in the same transaction as its effect.
     */
    public PollResult poll(ChainSource chain) {
        long head = chain.headBlockNumber();

        // Deliberately re-reads the last few blocks rather than continuing exactly where it
        // stopped. A reorganisation moves events into different blocks, and an event that
        // came back somewhere else would otherwise be behind the cursor and never seen
        // again. Re-reading is free because recording is idempotent.
        long from = Math.max(0, observations.lastBlockRead() + 1 - confirmations);

        int recorded = 0;
        int reanchored = 0;
        int unattributed = 0;
        for (ChainDeposit deposit : chain.depositsFrom(from)) {
            switch (observations.recordIfNew(deposit)) {
                case RECORDED -> recorded++;
                case UNKNOWN_INVOICE -> {
                    // Somebody paid for an invoice this platform never issued. The contract
                    // accepts any id from anyone, so this is not a fault and must not stop
                    // the pass: the money stays where it is, uncredited, and the reserve
                    // check reports a balance our records cannot explain, which is what it
                    // is for.
                    unattributed++;
                    log.warn("Deposit {}:{} names invoice {}, which does not exist here; "
                                    + "leaving the money uncredited",
                            deposit.txHash(), deposit.logIndex(), deposit.invoiceId());
                }
                case ALREADY_SEEN -> {
                    if (observations.reanchor(deposit)) {
                        reanchored++;
                    }
                }
            }
        }
        observations.rememberBlockRead(head);

        int reversed = reverseWhatTheChainTookBack(chain);
        Promotions promotions = promoteWhatIsBuriedDeepEnough(chain, head);

        return new PollResult(
                head,
                recorded,
                reanchored,
                promotions.confirmed(),
                promotions.deferred(),
                promotions.abandoned(),
                reversed,
                unattributed);
    }

    private int reverseWhatTheChainTookBack(ChainSource chain) {
        int reversed = 0;
        for (ChainObservation observation : observations.withStatus(ObservationStatus.CONFIRMED)) {
            if (stillCanonical(chain, observation)) {
                continue;
            }

            log.warn("Reorg: deposit {}:{} for invoice {} is no longer on chain, reversing",
                    observation.txHash(), observation.logIndex(), observation.invoiceId());

            dsl.transaction(config -> {
                settlement.reverseChainDeposit(
                        config,
                        observation.invoiceId(),
                        observation.txHash(),
                        observation.logIndex(),
                        observation.amount());
                observations.markWithin(config, observation, ObservationStatus.REVERSED);
            });
            reversed++;
        }
        return reversed;
    }

    private Promotions promoteWhatIsBuriedDeepEnough(ChainSource chain, long head) {
        int confirmed = 0;
        int deferred = 0;
        int abandoned = 0;

        for (ChainObservation observation : observations.withStatus(ObservationStatus.PENDING)) {
            if (!stillCanonical(chain, observation)) {
                // Dropped before it was ever credited, so there is nothing to undo.
                observations.mark(observation, ObservationStatus.ABANDONED);
                abandoned++;
                continue;
            }
            if (observation.depth(head) < confirmations) {
                deferred++;
                continue;
            }

            try {
                dsl.transaction(config -> {
                    settlement.fundEscrowFromChain(
                            config,
                            observation.invoiceId(),
                            observation.txHash(),
                            observation.logIndex(),
                            observation.amount());
                    observations.markWithin(config, observation, ObservationStatus.CONFIRMED);
                });
                confirmed++;
            } catch (InvoiceTransitionRejected notReady) {
                // The money is real but the invoice is not ready to be funded, so it is
                // left pending and tried again next pass. Money on chain does not oblige
                // an invoice to skip its own steps.
                log.info("Deposit {}:{} is confirmed but invoice {} cannot accept it yet: {}",
                        observation.txHash(), observation.logIndex(),
                        observation.invoiceId(), notReady.getMessage());
                deferred++;
            }
        }
        return new Promotions(confirmed, deferred, abandoned);
    }

    private static boolean stillCanonical(ChainSource chain, ChainObservation observation) {
        return chain.blockHashAt(observation.blockNumber())
                .map(hash -> hash.equals(observation.blockHash()))
                .orElse(false);
    }

    private record Promotions(int confirmed, int deferred, int abandoned) {
    }
}
