-- An account has one currency, and every entry written against it is supposed to be in
-- that currency. Until now that was true only because TransferRules checked it on the way
-- in, which is exactly the kind of guarantee V1 said the database should not have to take
-- on trust.
--
-- It matters more than a tidiness constraint, because a balance is summed without
-- filtering on currency: there is no need to filter when an account can only hold one.
-- Mix two currencies into an account and the sum stops being a balance and starts being
-- an addition of unlike things, and it is that number the transfer rules check funds
-- against. The reconciler's overdraft check sums the same way, so the one thing that might
-- have noticed inherits the same assumption.
--
-- The fix is to make the pair the thing referenced, so the currency cannot disagree.

alter table account
    add constraint account_id_currency unique (id, currency);

-- The composite references subsume the single-column ones: a row that matches
-- (account_id, currency) has necessarily matched account_id. Replacing rather than adding
-- keeps one statement of the rule instead of two that could later be changed apart.
alter table entry drop constraint entry_account_known;
alter table entry
    add constraint entry_account_and_currency
        foreign key (account_id, currency) references account (id, currency);

alter table transfer drop constraint transfer_from_known;
alter table transfer
    add constraint transfer_from_and_currency
        foreign key (from_account, currency) references account (id, currency);

alter table transfer drop constraint transfer_to_known;
alter table transfer
    add constraint transfer_to_and_currency
        foreign key (to_account, currency) references account (id, currency);
