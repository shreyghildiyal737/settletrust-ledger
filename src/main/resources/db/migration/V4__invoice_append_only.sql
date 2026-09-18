-- Transitions are history, so they get the same protection the ledger's entries have.
-- Reusing the function created in V2.

-- [jooq ignore start]
create trigger invoice_transition_is_append_only
    before update or delete on invoice_transition
    for each row execute function reject_history_rewrite();

-- [jooq ignore stop]