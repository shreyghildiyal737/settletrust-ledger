# SettleTrust Ledger

A double-entry ledger for a cross-border trade settlement platform. Java 21, no framework
in the domain core.

This is the backend half of [SettleTrust](https://github.com/shreyghildiyal737), whose
invoice lifecycle is modelled as an explicit state machine. This service is the part that
moves the money.

## The invariant

Money is never created or destroyed by this system, it only moves. Every movement is a
balanced pair of entries that sum to zero, and every test in `TransferServiceTest` exists
to defend that one sentence.

```
transfer(alice -> bob, 25.00 EUR)

  entry  -2500 EUR  alice   debit
  entry  +2500 EUR  bob     credit
                    -----
                        0
```

## Decisions, and why

**Amounts are `long` minor units, never floating point.** A settlement that is out by a
fraction of a cent is a settlement that fails reconciliation. `Money` carries its currency
with it, because cross-border means two accounts in different currencies is the normal
case, and a transfer refuses to cross them: that is an FX trade with a rate and a spread,
not a transfer.

**Balances are projections, not columns.** An account holds no balance field. The balance
is derived by summing the entries written against it, so there is no stored number that
can quietly disagree with the log. This is O(n) today, which is the right trade in memory
and a deliberate later decision once it is behind Postgres.

**Entries are append-only.** Nothing is updated or deleted. A correction is a new pair in
the opposite direction, because an overwritten entry has destroyed the evidence of why a
balance changed, and a settlement platform cannot destroy that evidence.

**Every transfer carries an idempotency key.** Networks retry, phones retry, users press
the button twice. The key is checked and recorded under the same lock as the write, so two
racing retries of one payment cannot both find it absent and both pay. A replay returns the
original transfer, flagged as a replay, having moved nothing.

**A rejected transfer does not burn its key.** If the transfer never happened, the key is
still available, so a caller can legitimately retry once the account is funded. Recording
it on rejection would strand a payment that never ran.

**Customer accounts cannot go negative; house accounts must.** The house account is the
platform's own side of the books, and it is where money entering from outside comes from.
Without that distinction the first deposit into an empty system has nowhere legitimate to
originate.

**The lock lives in `TransferService`, not `Ledger`.** The unit that has to be atomic is
the whole read-check-write of a transfer, not any single call to the store. When this moves
behind Postgres, the same boundary holds with the database transaction taking the place of
the lock.

## Tests

18 tests, all green. The concurrency tests release every thread from a barrier at the same
instant, one virtual thread per task, so they genuinely contend.

| What is proved | Where |
|---|---|
| A transfer is two entries summing to zero | `TransferServiceTest.TheInvariant` |
| The whole ledger sums to zero after any sequence | same |
| A replayed key returns the original and moves nothing | `TransferServiceTest.Idempotency` |
| Unknown account, non-positive amount, self-transfer, currency mismatch, insufficient funds | `TransferServiceTest.Rejections` |
| 50 racing transfers cannot overdraw an account | `ConcurrentTransferTest` |
| 20 racing retries of one payment move money once | same |
| Money is conserved under mixed concurrent load | same |

```bash
mvn test
```

Java 21 and Maven are the only requirements. Nothing else is needed yet, by design.

## Status

**Shipped:** the domain core above, with tests.

**Next, in order:**

1. Postgres as the event store, state row and event row written in one transaction, with
   Flyway migrations and jOOQ for typed SQL. Testcontainers for the integration tests.
2. Spring Boot at the edge only, so the domain stays framework-free.
3. The invoice state machine ported from the SettleTrust frontend, server-side, as the
   authority rather than a client-side convenience.
4. An escrow rail on-chain: a Solidity contract holding funds against the invoice's escrow
   state, and a watcher that posts its events into this same ledger, using the transaction
   hash as the idempotency key, with a confirmation depth before an entry counts and a
   reversal path when a reorg takes it back.
5. Reconciliation as a scheduled job, proving the two rails agree.

One ledger, two settlement rails.
