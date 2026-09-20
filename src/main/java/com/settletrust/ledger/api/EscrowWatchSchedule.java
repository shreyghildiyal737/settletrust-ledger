package com.settletrust.ledger.api;

import com.settletrust.ledger.chain.ChainSource;
import com.settletrust.ledger.chain.EscrowWatcher;
import com.settletrust.ledger.chain.JsonRpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reads the chain on a timer, which is what makes the on-chain rail a rail rather than a
 * library.
 *
 * <p>A fixed delay rather than a fixed rate: a slow pass against a busy node must not
 * queue the next one behind itself.
 *
 * <p><b>No lease, unlike the reconciler, and for a reason rather than an oversight.</b>
 * The reconciler takes one because two instances would both scan the book and write two
 * reports of the same facts, cluttering the record an operator reads during an incident.
 * Nothing here writes a record of having looked. Every write the watcher makes is keyed on
 * a transaction hash and log index and guarded by a unique constraint, so a second
 * instance doing the same work reaches the same state and loses the insert harmlessly.
 * The rail is idempotent by construction, and a lease would be protecting it from
 * something that cannot happen.
 *
 * <p>That also means a poll spans several transactions rather than one, so a
 * transaction-scoped advisory lock could not have covered it anyway.
 */
@Component
@ConditionalOnProperty("ledger.chain.rpc-url")
class EscrowWatchSchedule {

    private static final Logger log = LoggerFactory.getLogger(EscrowWatchSchedule.class);

    private final EscrowWatcher watcher;
    private final ChainSource chain;

    EscrowWatchSchedule(EscrowWatcher watcher, ChainSource chain) {
        this.watcher = watcher;
        this.chain = chain;
    }

    @Scheduled(
            initialDelayString = "${ledger.chain.initial-delay:PT10S}",
            fixedDelayString = "${ledger.chain.poll-interval:PT15S}")
    void readTheChain() {
        EscrowWatcher.PollResult result;
        try {
            result = watcher.poll(chain);
        } catch (JsonRpc.ChainUnavailable unreachable) {
            // Expected occasionally and not worth a stack trace: a node restarts, a
            // provider rate-limits, a connection drops. The next tick re-reads from the
            // cursor and loses nothing.
            log.warn("Could not read the chain this pass: {}", unreachable.getMessage());
            return;
        } catch (RuntimeException failure) {
            // Swallowed deliberately: the scheduler cancels a task that throws, and a
            // watcher that stops after one bad night leaves deposits uncredited in
            // silence, which looks exactly like a chain nobody is paying into.
            log.error("The escrow watcher failed to complete a pass", failure);
            return;
        }

        if (result.confirmed() == 0 && result.recorded() == 0 && result.reversed() == 0) {
            // The ordinary state of a quiet chain, so it is not news. The head is logged
            // at debug because "how far has it read" is the first question when somebody
            // says a deposit never arrived.
            log.debug("Chain read to block {}, nothing new", result.headBlock());
            return;
        }

        log.info("Chain read to block {}: {} seen, {} credited, {} deferred, {} reversed, "
                        + "{} re-anchored, {} abandoned",
                result.headBlock(), result.recorded(), result.confirmed(), result.deferred(),
                result.reversed(), result.reanchored(), result.abandoned());
    }
}
