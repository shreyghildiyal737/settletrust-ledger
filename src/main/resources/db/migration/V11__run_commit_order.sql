-- The open findings are anchored on the newest full run, and "newest" was decided by
-- ran_at, a clock the application supplies. Two replicas contend for the reconciliation
-- lease, so the run that wrote a row is not always the same machine, and a clock a few
-- seconds behind is enough to anchor the answer on an older, dirtier run and go on
-- reporting findings a later clean run had closed. The id tiebreak does not help: these
-- ids are random, so ordering by them is arbitrary rather than chronological.
--
-- The same argument the watermark already makes about timestamps, applied to the other
-- place this service asks which of two rows came later. Commit order is the only order
-- the database can actually vouch for.

alter table reconciliation_run
    add column xid bigint not null default pg_current_xact_id()::text::bigint;

create index reconciliation_run_by_xid on reconciliation_run (xid);
