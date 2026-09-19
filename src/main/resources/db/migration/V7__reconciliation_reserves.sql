-- Whether a run asked the chain what it actually holds, or only compared our own records
-- against each other.
--
-- Stored for the same reason the counts are: a clean verdict is only worth what the run
-- behind it examined. Without this column a report from a deployment with no node
-- configured is indistinguishable from one that checked the reserves and found them
-- sound, which is the more dangerous of the two to read at a glance.
--
-- Defaulted rather than backfilled by hand. Runs recorded before this column existed did
-- not check reserves, and false is the honest answer for them.
alter table reconciliation_run
    add column reserves_checked boolean not null default false;
