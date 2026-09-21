package com.settletrust.ledger.chain;

import com.settletrust.ledger.Money;
import com.settletrust.ledger.SqlErrors;
import org.jooq.Configuration;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.settletrust.ledger.jooq.Tables.CHAIN_CURSOR;
import static com.settletrust.ledger.jooq.Tables.CHAIN_OBSERVATION;

/**
 * What the watcher has seen, and how far it has read.
 *
 * <p>This is the one mutable table in the service, and the exception is deliberate. The
 * ledger and the invoice history are records of what happened and never change; this is a
 * cursor over a chain that is allowed to change its mind. A deposit's status moves as the
 * chain confirms or abandons it, while every consequence in the ledger stays permanent.
 */
public class PostgresChainObservations {

    private static final String CURSOR_ID = "escrow";

    private final DSLContext dsl;
    private final Clock clock;

    public PostgresChainObservations(DSLContext dsl, Clock clock) {
        this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * What happened when a deposit the chain reported was written down.
     *
     * <p>Three outcomes rather than two, because a deposit can name an invoice this
     * platform never issued. The contract takes any {@code bytes32} from anyone, so that
     * is not an error in the watcher, it is a thing the world does.
     */
    public enum Recording {
        RECORDED,
        ALREADY_SEEN,
        /** The deposit names an invoice that does not exist here. */
        UNKNOWN_INVOICE
    }

    /**
     * Records a deposit as pending if it has not been seen before, and says what happened.
     * Seeing the same event twice is normal rather than exceptional: a restart, an
     * overlapping scan range or a reorganisation all replay events.
     */
    public Recording recordIfNew(ChainDeposit deposit) {
        int inserted;
        try {
            inserted = insertPending(deposit);
        } catch (RuntimeException failure) {
            // The invoice foreign key. Anyone may call deposit() with any invoice id, so a
            // deposit for an invoice we never issued is a thing a stranger can do at will,
            // and it must not be able to stop the rail: before this was caught, one such
            // deposit failed the insert on every pass for ever, and no later deposit for
            // anybody was credited again. The money is still in the contract and still
            // visible, because the reserve check reports a balance nothing on our side
            // explains, which is exactly what this is.
            if (SqlErrors.isForeignKeyViolation(failure)) {
                return Recording.UNKNOWN_INVOICE;
            }
            throw failure;
        }
        return inserted == 1 ? Recording.RECORDED : Recording.ALREADY_SEEN;
    }

    private int insertPending(ChainDeposit deposit) {
        return dsl.insertInto(CHAIN_OBSERVATION)
                .set(CHAIN_OBSERVATION.TX_HASH, deposit.txHash())
                .set(CHAIN_OBSERVATION.LOG_INDEX, deposit.logIndex())
                .set(CHAIN_OBSERVATION.BLOCK_NUMBER, deposit.blockNumber())
                .set(CHAIN_OBSERVATION.BLOCK_HASH, deposit.blockHash())
                .set(CHAIN_OBSERVATION.INVOICE_ID, deposit.invoiceId())
                .set(CHAIN_OBSERVATION.AMOUNT_MINOR, deposit.amount().minorUnits())
                .set(CHAIN_OBSERVATION.CURRENCY, deposit.amount().currency())
                .set(CHAIN_OBSERVATION.STATUS, ObservationStatus.PENDING.name())
                .set(CHAIN_OBSERVATION.FIRST_SEEN, now())
                .onConflictDoNothing()
                .execute();
    }

    /**
     * Points an already-known deposit at the block it now lives in, and revives it if it
     * had been given up on.
     *
     * <p>A reorganisation does not usually destroy a transaction, it re-mines it somewhere
     * else. Without this, an event that came back in a new block would still be carrying
     * the hash of a block that no longer exists, and the watcher would keep concluding
     * that it had vanished.
     *
     * <p>Confirmed observations are left alone here: those have already moved money, and
     * undoing that is the reversal path's job, not a silent update.
     */
    public boolean reanchor(ChainDeposit deposit) {
        int updated = dsl.update(CHAIN_OBSERVATION)
                .set(CHAIN_OBSERVATION.BLOCK_NUMBER, deposit.blockNumber())
                .set(CHAIN_OBSERVATION.BLOCK_HASH, deposit.blockHash())
                .set(CHAIN_OBSERVATION.STATUS, ObservationStatus.PENDING.name())
                .where(CHAIN_OBSERVATION.TX_HASH.eq(deposit.txHash()))
                .and(CHAIN_OBSERVATION.LOG_INDEX.eq(deposit.logIndex()))
                .and(CHAIN_OBSERVATION.BLOCK_HASH.ne(deposit.blockHash()))
                .and(CHAIN_OBSERVATION.STATUS.in(
                        ObservationStatus.PENDING.name(), ObservationStatus.ABANDONED.name()))
                .execute();
        return updated == 1;
    }

    public List<ChainObservation> withStatus(ObservationStatus status) {
        return withStatusAtOrAbove(status, Long.MIN_VALUE);
    }

    /**
     * The observations in a status, from {@code minBlock} upwards.
     *
     * <p>The bound is what keeps the watcher's cost flat. Confirmed deposits accumulate for
     * the life of the platform and the reorganisation sweep asks the node one question per
     * observation, so an unbounded version of this query turns a quiet pass into one
     * request per deposit ever taken, every fifteen seconds.
     */
    public List<ChainObservation> withStatusAtOrAbove(ObservationStatus status, long minBlock) {
        return dsl.selectFrom(CHAIN_OBSERVATION)
                .where(CHAIN_OBSERVATION.STATUS.eq(status.name()))
                .and(CHAIN_OBSERVATION.BLOCK_NUMBER.ge(minBlock))
                .orderBy(CHAIN_OBSERVATION.BLOCK_NUMBER.asc(), CHAIN_OBSERVATION.LOG_INDEX.asc())
                .fetch()
                .map(PostgresChainObservations::toObservation);
    }

    public Optional<ChainObservation> find(String txHash, int logIndex) {
        return dsl.selectFrom(CHAIN_OBSERVATION)
                .where(CHAIN_OBSERVATION.TX_HASH.eq(txHash))
                .and(CHAIN_OBSERVATION.LOG_INDEX.eq(logIndex))
                .fetchOptional()
                .map(PostgresChainObservations::toObservation);
    }

    /**
     * Moves an observation to a new status inside a transaction the caller owns, so that
     * crediting the ledger and marking the deposit confirmed cannot come apart.
     */
    public void markWithin(
            Configuration config, ChainObservation observation, ObservationStatus status) {
        DSL.using(config)
                .update(CHAIN_OBSERVATION)
                .set(CHAIN_OBSERVATION.STATUS, status.name())
                .set(CHAIN_OBSERVATION.SETTLED_AT, now())
                .where(CHAIN_OBSERVATION.TX_HASH.eq(observation.txHash()))
                .and(CHAIN_OBSERVATION.LOG_INDEX.eq(observation.logIndex()))
                .execute();
    }

    /** For a deposit dropped before it was ever credited: no ledger effect, so no transaction. */
    public void mark(ChainObservation observation, ObservationStatus status) {
        dsl.transaction(config -> markWithin(config, observation, status));
    }

    public long lastBlockRead() {
        return dsl.select(CHAIN_CURSOR.LAST_BLOCK_READ)
                .from(CHAIN_CURSOR)
                .where(CHAIN_CURSOR.ID.eq(CURSOR_ID))
                .fetchOptional(CHAIN_CURSOR.LAST_BLOCK_READ)
                .orElse(-1L);
    }

    public void rememberBlockRead(long blockNumber) {
        dsl.insertInto(CHAIN_CURSOR)
                .set(CHAIN_CURSOR.ID, CURSOR_ID)
                .set(CHAIN_CURSOR.LAST_BLOCK_READ, blockNumber)
                .set(CHAIN_CURSOR.UPDATED_AT, now())
                .onConflict(CHAIN_CURSOR.ID)
                .doUpdate()
                .set(CHAIN_CURSOR.LAST_BLOCK_READ, blockNumber)
                .set(CHAIN_CURSOR.UPDATED_AT, now())
                .execute();
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static ChainObservation toObservation(Record row) {
        LocalDateTime settledAt = row.get(CHAIN_OBSERVATION.SETTLED_AT);
        return new ChainObservation(
                row.get(CHAIN_OBSERVATION.TX_HASH),
                row.get(CHAIN_OBSERVATION.LOG_INDEX),
                row.get(CHAIN_OBSERVATION.BLOCK_NUMBER),
                row.get(CHAIN_OBSERVATION.BLOCK_HASH),
                row.get(CHAIN_OBSERVATION.INVOICE_ID),
                Money.of(
                        row.get(CHAIN_OBSERVATION.AMOUNT_MINOR),
                        row.get(CHAIN_OBSERVATION.CURRENCY)),
                ObservationStatus.valueOf(row.get(CHAIN_OBSERVATION.STATUS)),
                row.get(CHAIN_OBSERVATION.FIRST_SEEN).toInstant(ZoneOffset.UTC),
                settledAt == null ? null : settledAt.toInstant(ZoneOffset.UTC));
    }
}
