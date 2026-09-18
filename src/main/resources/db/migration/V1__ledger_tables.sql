-- The ledger's tables. Constraints here are not belt-and-braces duplication of the
-- Java checks: they are the last line that still holds when something writes to this
-- database without going through the service.

create table account (
    id        varchar(64) primary key,
    currency  char(3)     not null,
    kind      varchar(16) not null,
    opened_at timestamp   not null,

    constraint account_kind_known check (kind in ('CUSTOMER', 'HOUSE'))
);

-- One row per completed transfer. The unique constraint on idempotency_key is what
-- makes idempotency structural rather than advisory: two concurrent retries of the same
-- payment cannot both insert, whatever the application layer believes.
create table transfer (
    id              uuid        primary key,
    idempotency_key varchar(128) not null,
    from_account    varchar(64) not null,
    to_account      varchar(64) not null,
    amount_minor    bigint      not null,
    currency        char(3)     not null,
    completed_at    timestamp   not null,

    constraint transfer_key_unique      unique (idempotency_key),
    constraint transfer_amount_positive check (amount_minor > 0),
    constraint transfer_distinct_sides  check (from_account <> to_account),
    constraint transfer_from_known      foreign key (from_account) references account (id),
    constraint transfer_to_known        foreign key (to_account)   references account (id)
);

-- The entries themselves. Two per transfer, summing to zero.
create table entry (
    id           uuid        primary key,
    transfer_id  uuid        not null,
    account_id   varchar(64) not null,
    amount_minor bigint      not null,
    currency     char(3)     not null,
    recorded_at  timestamp   not null,

    constraint entry_not_zero      check (amount_minor <> 0),
    constraint entry_transfer_known foreign key (transfer_id) references transfer (id),
    constraint entry_account_known  foreign key (account_id)  references account (id)
);

-- A balance is read by summing an account's entries, so this index is on the read path
-- of every single transfer.
create index entry_by_account on entry (account_id);
