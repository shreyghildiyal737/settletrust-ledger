-- Reconciliation: the record of every time the two rails were asked whether they agree.
--
-- The checks themselves derive everything from the ledger and the chain observations, so
-- in principle a run could be recomputed whenever anyone wanted one. These tables exist
-- because "we checked, and here is what we found, at this time" is evidence, and evidence
-- that is recomputed on demand cannot be produced for a date when nobody was looking.

create table reconciliation_run (
    id                   uuid      primary key,
    ran_at               timestamp not null,
    observations_checked integer   not null,
    transfers_checked    integer   not null,
    discrepancy_count    integer   not null,

    constraint reconciliation_run_counts_sane check (
        observations_checked >= 0 and transfers_checked >= 0 and discrepancy_count >= 0
    )
);

-- One row per thing that did not add up. A clean run stores no findings and is still
-- worth storing: the absence of findings on a named date is the useful part.
create table reconciliation_finding (
    id             uuid         primary key,
    run_id         uuid         not null,
    kind           varchar(48)  not null,
    subject        varchar(128) not null,
    detail         varchar(512) not null,
    -- Null together, for the findings that are not about a quantity: a transfer with the
    -- wrong number of entries is wrong in a way no pair of amounts describes.
    expected_minor bigint,
    found_minor    bigint,
    currency       varchar(8),

    constraint reconciliation_finding_run   foreign key (run_id) references reconciliation_run (id),
    constraint reconciliation_finding_pairs check (
        (expected_minor is null) = (found_minor is null)
    )
);

create index reconciliation_finding_by_run on reconciliation_finding (run_id);

-- A run is history like every other record here: a report that can be edited after the
-- fact is not a report, it is a draft.

-- [jooq ignore start]
create trigger reconciliation_run_is_append_only
    before update or delete on reconciliation_run
    for each row execute function reject_history_rewrite();

create trigger reconciliation_finding_is_append_only
    before update or delete on reconciliation_finding
    for each row execute function reject_history_rewrite();

-- [jooq ignore stop]
