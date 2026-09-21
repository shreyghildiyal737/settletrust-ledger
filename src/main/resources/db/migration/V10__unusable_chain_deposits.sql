-- A deposit can name a real invoice and still be unusable against it: the contract takes
-- an amount and a bytes32 from anyone, and neither is checked against the invoice it
-- claims to pay. A deposit in the wrong currency cannot be credited into that invoice's
-- escrow at all, because an escrow account holds one currency and V9 now enforces it.
--
-- MISMATCHED is where those land. It is terminal on purpose: retrying cannot help, since
-- the fault is in what arrived rather than in when it arrived, and leaving such a deposit
-- PENDING would have the watcher reconsider it on every pass for ever. The money stays
-- uncredited and therefore stays visible, as a contract balance the ledger cannot account
-- for, which is exactly what the reserves check reports.

alter table chain_observation drop constraint chain_observation_state;
alter table chain_observation
    add constraint chain_observation_state check (
        status in ('PENDING', 'CONFIRMED', 'REVERSED', 'ABANDONED', 'MISMATCHED')
    );
