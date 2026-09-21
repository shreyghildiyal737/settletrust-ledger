package com.settletrust.ledger.chain;

import com.settletrust.ledger.invoice.InvoiceTransitionRejected;
import com.settletrust.ledger.settlement.ChainDepositMismatch;
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
    private final int finalityDepth;

    public EscrowWatcher(
            DSLContext dsl,
            PostgresChainObservations observations,
            InvoiceSettlement settlement,
            int confirmations) {
        this(dsl, observations, settlement, confirmations, confirmations * 4);
    }

    /**
     * @param confirmations how deep a deposit must be buried before it is credited
     * @param finalityDepth how deep a deposit must be buried before the chain stops being
     *     asked about it at all. Past this point an observation is treated as settled
     *     history: a reorganisation that reached further back than this would already have
     *     invalidated the bet that {@code confirmations} was deep enough to credit on, so
     *     re-checking for ever buys nothing and costs one request per deposit per pass,
     *     for every deposit the platform has ever taken.
     */
    public EscrowWatcher(
            DSLContext dsl,
            PostgresChainObservations observations,
            InvoiceSettlement settlement,
            int confirmations,
            int finalityDepth) {
        this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
        this.observations = Objects.requireNonNull(observations, "observations must not be null");
        this.settlement = Objects.requireNonNull(settlement, "settlement must not be null");
        if (confirmations < 1) {
            throw new IllegalArgumentException("confirmations must be at least 1");
        }
        if (finalityDepth < confirmations) {
            throw new IllegalArgumentException(
                    "finalityDepth must be at least confirmations, or a deposit would be "
                            + "treated as final before it was deep enough to credit");
        }
        this.confirmations = confirmations;
        this.finalityDepth = finalityDepth;
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
            int unattributed,
            /** Logs the chain source could not read at all. */
            int undecodable,
            /** Deposits naming a real invoice they cannot be applied to. */
            int mismatched) {
    }

    /**
     * Reads the chain once and brings the ledger up to date with it.
     *
     * <p>Safe to call repeatedly and safe to interrupt: everything it does is either
     * idempotent or recorded in the same transaction as its effect.
     */
    public PollResult poll(ChainSource chain) {
        // Read once, and it is the only head this pass uses. Every range scanned, every
        // depth computed and the cursor finally written all refer to this one number, so
        // there is no second horizon for a block to fall between.
        long head = chain.headBlockNumber();

        // Deliberately re-reads the last stretch rather than continuing exactly where it
        // stopped. A reorganisation moves events into different blocks, and an event that
        // came back somewhere else would otherwise be behind the cursor and never seen
        // again. Re-reading is free because recording is idempotent.
        //
        // The rewind is the finality depth and not the confirmation depth, and that is the
        // load-bearing part. The cursor is only a hint about where to start; what actually
        // stops a deposit being lost is that the next pass begins far enough back to cover
        // any reorganisation this service is willing to believe in. Rewinding by
        // confirmations tied those two together for no reason, and at a confirmation depth
        // of one it left a window one block wide: the chain shortens between reading the
        // head and reading the logs, the blocks that replace it are never scanned, and the
        // deposits in them are lost for good.
        long from = Math.max(0, observations.lastBlockRead() + 1 - finalityDepth);

        int recorded = 0;
        int reanchored = 0;
        int unattributed = 0;
        ChainSource.Scan scan = chain.depositsFrom(from, head);
        if (!scan.undecodable().isEmpty()) {
            // Not an error to stop on: these are events anybody can emit, and they stay in
            // their block for ever. Loud, counted, and stepped over.
            log.warn("{} deposit log(s) could not be read and were skipped: {}",
                    scan.undecodable().size(), scan.undecodable());
        }

        for (ChainDeposit deposit : scan.deposits()) {
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
        // Safe to write only because the scan above was bounded by this same head.
        observations.rememberBlockRead(head);

        int reversed = reverseWhatTheChainTookBack(chain, head);
        Promotions promotions = promoteWhatIsBuriedDeepEnough(chain, head);

        return new PollResult(
                head,
                recorded,
                reanchored,
                promotions.confirmed(),
                promotions.deferred(),
                promotions.abandoned(),
                reversed,
                unattributed,
                scan.undecodable().size(),
                promotions.mismatched());
    }

    private int reverseWhatTheChainTookBack(ChainSource chain, long head) {
        int reversed = 0;
        long oldestWorthAsking = head - finalityDepth;
        for (ChainObservation observation
                : observations.withStatusAtOrAbove(ObservationStatus.CONFIRMED, oldestWorthAsking)) {
            if (stillCanonical(chain, observation, head)) {
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
        int mismatched = 0;

        for (ChainObservation observation : observations.withStatus(ObservationStatus.PENDING)) {
            // Past the finality depth the chain is not asked again, for the same reason the
            // reversal sweep stops there. A pending observation that old is waiting on this
            // platform, not on the chain: its invoice was not ready to be funded.
            boolean settledHistory = observation.depth(head) > finalityDepth;
            if (!settledHistory && !stillCanonical(chain, observation, head)) {
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
            } catch (ChainDepositMismatch wrong) {
                // Nothing retrying can fix: what arrived does not fit the invoice it names.
                // Marked terminal so it is not reconsidered for ever, and left uncredited so
                // the money stays visible to the reserves check as a balance the ledger
                // cannot account for.
                log.warn("Deposit {}:{} names invoice {} and cannot be applied to it: {}",
                        observation.txHash(), observation.logIndex(),
                        observation.invoiceId(), wrong.getMessage());
                observations.mark(observation, ObservationStatus.MISMATCHED);
                mismatched++;
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
        return new Promotions(confirmed, deferred, abandoned, mismatched);
    }

    /**
     * Whether the block this deposit was seen in is still the block at that height.
     *
     * <p>A reversal moves money, so it is made only on positive evidence. There are two
     * ways to have none. If the height is above the head, the chain really has become
     * shorter and the block is genuinely gone: that is a reorganisation and reversing is
     * right. But if the node claims a head at or above this height and still has no block
     * there, it is contradicting itself, most likely mid re-sync. Treating that silence as
     * a reorganisation would reverse confirmed deposits the chain never took back, so it
     * is raised instead and the pass is retried on the next tick.
     */
    private static boolean stillCanonical(
            ChainSource chain, ChainObservation observation, long head) {

        if (observation.blockNumber() > head) {
            return false;
        }
        String canonical = chain.blockHashAt(observation.blockNumber())
                .orElseThrow(() -> new JsonRpc.ChainUnavailable(
                        "the node reports head " + head + " but has no block at "
                                + observation.blockNumber() + ", so it cannot be asked "
                                + "whether deposit " + observation.txHash() + ":"
                                + observation.logIndex() + " was taken back"));
        return canonical.equals(observation.blockHash());
    }

    private record Promotions(int confirmed, int deferred, int abandoned, int mismatched) {
    }
}
