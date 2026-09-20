-- Reconciliation from a watermark, so a run costs what changed rather than what exists.
--
-- The scanning reconciler was correct and does not scale: it reads every entry, every
-- transfer and every observation on a fifteen minute timer, and holds a repeatable read
-- snapshot open for the length of the pass, which holds back vacuum on exactly the tables
-- that churn most. The fix is to look only at what has arrived since the last run, and
-- the whole difficulty is in saying what "since" means.
--
-- It cannot mean an id or a timestamp. Both are chosen by the row while its transaction
-- is still open, and transactions do not commit in the order they started: a transfer
-- that takes sequence value 100 can commit after one that took 101. A run that watermarks
-- at 101 has already passed 100 by the time it lands, and the gap is permanent and
-- silent, which is the worst failure a reconciler can have.
--
-- So the watermark is on visibility instead. Each append-only row records the id of the
-- transaction that wrote it, and a run's new mark is pg_snapshot_xmin, the oldest
-- transaction still in flight when the run took its snapshot. Nothing below that can
-- still arrive: a transaction is given its id when it first writes, and a transaction
-- that has not written yet will be given one above the counter's current value. The
-- window [previous mark, new mark) therefore contains every row exactly once.
--
-- The failure mode is the right way round, too. A long-running transaction pins xmin, the
-- mark stops advancing, and the run does less work rather than missing work.
--
-- xid8 rather than xid: 64 bits, so it does not wrap, and comparing two of them is
-- meaningful without knowing where the counter currently sits. It is stored as bigint
-- because jOOQ has a type for that and none for xid8, and no real deployment will reach
-- 2^63 transactions.

-- The default is volatile, so each of these rewrites its table and takes an exclusive
-- lock for the length of the rewrite. On a book of any size this would be done as three
-- steps (add nullable, backfill in batches, then set the default) and the single
-- statement is only honest here because the tables are small.
alter table entry
    add column xid bigint not null default pg_current_xact_id()::text::bigint;
alter table transfer
    add column xid bigint not null default pg_current_xact_id()::text::bigint;
alter table chain_observation
    add column xid bigint not null default pg_current_xact_id()::text::bigint;

create index entry_by_xid             on entry (xid);
create index transfer_by_xid          on transfer (xid);
create index chain_observation_by_xid on chain_observation (xid);

-- Entries and transfers cannot change, so a row checked once stays checked. Observations
-- can: the chain confirms them, and reorganises them away again. A row that changed after
-- it was examined has to be examined again, so it is restamped with the transaction that
-- changed it and falls back inside the next window.
--
-- This is why the watermark filters only the append-only tables, and why the aggregate
-- checks over chain_observation still scan. Excluding a mutable row from a sum does not
-- restore the value it used to contribute, so a fold over it would drift; excluding an
-- entry does, because an entry never contributed anything else.

-- [jooq ignore start]
create or replace function restamp_changed_row() returns trigger as $$
begin
    new.xid := pg_current_xact_id()::text::bigint;
    return new;
end;
$$ language plpgsql;

create trigger chain_observation_is_restamped
    before update on chain_observation
    for each row execute function restamp_changed_row();
-- [jooq ignore stop]

-- What a run covered, so a reader can tell a windowed clean verdict from a deep one.
--
-- Null on runs recorded before this migration: they scanned everything and had no mark to
-- report, and inventing one for them would misrepresent what they did.
alter table reconciliation_run
    add column mode         varchar(16) not null default 'FULL',
    add column checked_from bigint,
    add column checked_to   bigint;

alter table reconciliation_run
    add constraint reconciliation_run_mode_known check (mode in ('FULL', 'INCREMENTAL')),
    add constraint reconciliation_run_range_sane check (
        (checked_from is null) = (checked_to is null)
        and (checked_from is null or checked_from <= checked_to)
    );

-- The running total an incremental run adds to, and the deep run's second source.
--
-- A fold is only as good as what it started from, and this is a number the service itself
-- wrote, the one kind of evidence every other check here deliberately refuses to trust.
-- It is trusted anyway, on one condition: the periodic full run re-derives the same total
-- from the entries and reports a CHECKPOINT_DRIFT when the two disagree. That catches
-- both a bug in the arithmetic and a rewrite of history below the watermark, which is the
-- fault the append-only triggers exist to prevent and this exists to catch when they fail.
--
-- The entry count is folded alongside the net because two compensating errors can leave a
-- net untouched, and a count they both changed is what gives them away.
create table reconciliation_checkpoint (
    run_id          uuid       not null,
    currency        varchar(8) not null,
    net_minor       bigint     not null,
    entries_counted bigint     not null,

    constraint reconciliation_checkpoint_id    primary key (run_id, currency),
    constraint reconciliation_checkpoint_run   foreign key (run_id)
        references reconciliation_run (id),
    constraint reconciliation_checkpoint_count check (entries_counted >= 0)
);

-- History, like the run it belongs to: a carried total that can be edited after the fact
-- would let somebody make the drift check agree with the damage.

-- [jooq ignore start]
create trigger reconciliation_checkpoint_is_append_only
    before update or delete on reconciliation_checkpoint
    for each row execute function reject_history_rewrite();
-- [jooq ignore stop]
