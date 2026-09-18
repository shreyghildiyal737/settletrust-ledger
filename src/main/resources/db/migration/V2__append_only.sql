-- Append-only, enforced by the database rather than by everyone remembering.
--
-- The Java service never issues an update or a delete against these tables. This trigger
-- is what makes that a property of the system instead of a property of the current code:
-- a migration, a console session or a future bug cannot quietly rewrite history. A
-- correction is a new pair of entries in the opposite direction, which is also how a
-- paper ledger has always worked.

create or replace function reject_history_rewrite() returns trigger as $$
begin
    raise exception '% on % is not permitted: entries are append-only, post a reversing pair instead',
        tg_op, tg_table_name;
end;
$$ language plpgsql;

create trigger entry_is_append_only
    before update or delete on entry
    for each row execute function reject_history_rewrite();

create trigger transfer_is_append_only
    before update or delete on transfer
    for each row execute function reject_history_rewrite();
