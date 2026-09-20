package com.settletrust.ledger.api;

import com.settletrust.ledger.chain.ChainSource;
import com.settletrust.ledger.chain.EscrowWatcher;
import com.settletrust.ledger.chain.JsonRpc;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
    private final MeterRegistry registry;
    private final Clock clock;

    /** Negative until a pass has succeeded, which is reported as NaN rather than zero. */
    private final AtomicLong headBlock = new AtomicLong(-1);
    private final AtomicReference<Instant> lastSuccessfulPass = new AtomicReference<>();

    EscrowWatchSchedule(
            EscrowWatcher watcher, ChainSource chain, MeterRegistry registry, Clock clock) {
        this.watcher = watcher;
        this.chain = chain;
        this.registry = registry;
        this.clock = clock;

        Gauge.builder("settletrust.chain.head.block", headBlock,
                        holder -> holder.get() < 0 ? Double.NaN : holder.get())
                .description("The newest block this service has read")
                .register(registry);

        // The one that says whether the rail is alive. A watcher that has stopped leaves
        // every other chain number frozen at a plausible value, and only its age gives it
        // away.
        Gauge.builder("settletrust.chain.pass.age", lastSuccessfulPass, this::ageInSeconds)
                .description("Seconds since the chain was last read successfully")
                .baseUnit("seconds")
                .register(registry);
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
            registry.counter("settletrust.chain.passes", "outcome", "unreachable").increment();
            return;
        } catch (RuntimeException failure) {
            // Swallowed deliberately: the scheduler cancels a task that throws, and a
            // watcher that stops after one bad night leaves deposits uncredited in
            // silence, which looks exactly like a chain nobody is paying into.
            log.error("The escrow watcher failed to complete a pass", failure);
            registry.counter("settletrust.chain.passes", "outcome", "failed").increment();
            return;
        }

        headBlock.set(result.headBlock());
        lastSuccessfulPass.set(clock.instant());
        registry.counter("settletrust.chain.passes", "outcome", "read").increment();
        countDeposits(result);

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

    /**
     * One counter with an outcome tag rather than six counters.
     *
     * <p>Reversals and abandonments are the interesting ones: a rate of them that is not
     * near zero means the confirmation depth is set too shallow for this chain, which is
     * a number somebody chose and can change.
     */
    private void countDeposits(EscrowWatcher.PollResult result) {
        count("seen", result.recorded());
        count("credited", result.confirmed());
        count("deferred", result.deferred());
        count("reversed", result.reversed());
        count("reanchored", result.reanchored());
        count("abandoned", result.abandoned());
    }

    private void count(String outcome, int howMany) {
        if (howMany > 0) {
            registry.counter("settletrust.chain.deposits", "outcome", outcome)
                    .increment(howMany);
        }
    }

    private double ageInSeconds(AtomicReference<Instant> at) {
        Instant when = at.get();
        return when == null ? Double.NaN : Duration.between(when, clock.instant()).toSeconds();
    }
}
