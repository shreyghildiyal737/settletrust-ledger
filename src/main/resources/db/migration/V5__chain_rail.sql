-- The second settlement rail: money arriving on a blockchain rather than through a bank.

-- Currency codes widen because the chain rail settles in stablecoins, whose tickers are
-- four characters (USDC, EURC) and are not ISO 4217 at all. Pretending they are, or
-- squeezing them into three characters, would be a lie told to the type system.
alter table account            alter column currency type varchar(8);
alter table transfer           alter column currency type varchar(8);
alter table entry              alter column currency type varchar(8);
alter table invoice            alter column currency type varchar(8);

-- What the watcher has seen on chain, and what it has done about it.
--
-- Unlike entry and invoice_transition, this table is NOT append-only, and the difference
-- is the point. The ledger is the record of what happened; this is a cursor over a chain
-- that can change its mind. An observation's status moves as the chain confirms or
-- abandons it, while every consequence in the ledger stays permanent: a reversal is a new
-- pair of entries, never an erased one.
create table chain_observation (
    tx_hash      varchar(66)  not null,
    log_index    integer      not null,
    block_number bigint       not null,
    block_hash   varchar(66)  not null,
    invoice_id   varchar(64)  not null,
    amount_minor bigint       not null,
    currency     varchar(8)   not null,
    status       varchar(16)  not null,
    first_seen   timestamp    not null,
    settled_at   timestamp,

    constraint chain_observation_id      primary key (tx_hash, log_index),
    constraint chain_observation_known   foreign key (invoice_id) references invoice (id),
    constraint chain_observation_state   check (
        status in ('PENDING', 'CONFIRMED', 'REVERSED', 'ABANDONED')
    ),
    constraint chain_observation_amount  check (amount_minor > 0)
);

-- The watcher asks two questions repeatedly: what is still waiting for confirmations, and
-- what did I confirm in a block that may since have been reorganised away.
create index chain_observation_by_status on chain_observation (status, block_number);

-- How far the watcher has read. A single row, because there is one chain.
--
-- It cannot be derived from the observations, since blocks containing no deposits still
-- have to be passed over: without a cursor the watcher would rescan from the last block
-- that happened to be interesting, which on a quiet chain is the whole history.
create table chain_cursor (
    id              varchar(16) primary key,
    last_block_read bigint      not null,
    updated_at      timestamp   not null,

    constraint chain_cursor_single check (id = 'escrow')
);
