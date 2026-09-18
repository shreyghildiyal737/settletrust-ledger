-- An invoice and the moves it has made.
--
-- There is no status column on invoice, deliberately. The status is the to_status of the
-- invoice's highest-sequence transition, exactly as an account balance is the sum of its
-- entries. The same reason applies: a stored status can disagree with the history that
-- produced it, and then nobody can say which is true.

create table invoice (
    id           varchar(64) primary key,
    reference    varchar(64)  not null,
    seller_id    varchar(64)  not null,
    buyer_id     varchar(64)  not null,
    amount_minor bigint       not null,
    currency     char(3)      not null,
    created_at   timestamp    not null,

    constraint invoice_amount_positive check (amount_minor > 0)
);

-- The sequence is per invoice and starts at zero. The unique constraint on it is doing
-- real work: two clients racing to move the same invoice both compute the same next
-- sequence, and only one insert survives. The loser is told the invoice moved under it
-- rather than quietly overwriting a decision it never saw.
create table invoice_transition (
    id          uuid         primary key,
    invoice_id  varchar(64)  not null,
    sequence    integer      not null,
    from_status varchar(32),
    to_status   varchar(32)  not null,
    reason      varchar(256),
    occurred_at timestamp    not null,

    constraint invoice_transition_ordered   unique (invoice_id, sequence),
    constraint invoice_transition_known     foreign key (invoice_id) references invoice (id),
    constraint invoice_transition_sequenced check (sequence >= 0),
    constraint invoice_transition_opening   check (
        (sequence = 0 and from_status is null)
        or (sequence > 0 and from_status is not null)
    )
);

create index invoice_transition_latest on invoice_transition (invoice_id, sequence desc);
