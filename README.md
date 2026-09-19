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
can quietly disagree with the log. In Postgres that is an indexed `sum` over the account's
entries, which is honest and correct but grows with history. A running balance or periodic
snapshots is the next decision, and it is a deliberate one rather than a default.

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

## Storage

The ledger runs on PostgreSQL. Three things there are worth pointing at.

**Idempotency is a unique constraint, not a check.** The service does look for the key
first, because that is the common case and it is cheap, but the guarantee comes from
`transfer_key_unique`. Two concurrent retries of one payment can both find no row and both
try to insert; exactly one succeeds, the loser catches `23505`, reads the winner's row and
returns it. Both callers get the same answer and money moved once.

**Spending takes a row lock.** The paying account is read `for update` before its balance
is computed, so a concurrent transfer cannot spend the same money between the read and the
write. Only the source is locked, and only one row per transaction, so there is no lock
ordering between transfers and therefore no deadlock to order around.

**Append-only is enforced by the database.** Triggers on `entry` and `transfer` reject
every update and delete, so history cannot be rewritten by a console session, a bad
migration, or a bug in code nobody has written yet. A correction is a reversing pair.

jOOQ's classes are generated from the Flyway migration itself rather than from a running
database, so the schema has exactly one definition and a renamed column breaks compilation
instead of production.

## The invoice lifecycle

Twenty states, ported state for state from `src/lib/state-machine/invoice-transitions.ts`
in the SettleTrust frontend. The client keeps its copy so it can grey out a button without
a round trip, but that copy is now a convenience: **this service decides**, because
anything a browser enforces is a suggestion to whoever is not using the browser.

```
draft -> submitted -> buyer_accepted -> risk_review_* -> escrow_pending
      -> escrow_funded -> shipment_pending -> delivery_confirmed
      -> settlement_pending -> settled

disputed, frozen, expired, failed and cancelled hang off the side,
and only settled and cancelled are final.
```

**The status is not a column.** An invoice's status is the `to_status` of its
highest-sequence transition, exactly as an account balance is the sum of its entries. Same
reason: a stored status can disagree with the history that produced it, and then nobody
can say which is true. The whole history is always answerable, not just where an invoice
is but every step that got it there.

**Two clients cannot both move one invoice.** Each transition claims the next sequence
number, and `(invoice_id, sequence)` is unique, so exactly one insert survives a race. The
loser is told the invoice moved rather than quietly overwriting a decision it never saw.
Twelve racing clients, one applied move, eleven conflicts, and a test that proves it.

**A command may carry the status the caller believes it is acting on.** If the invoice has
moved since, the answer is 409 and the caller re-reads instead of acting on a stale view.
It is optional, because a job acting on a query it just ran does not need it.

## Settlement, where the two halves meet

An invoice reaching `escrow_funded` or `settled` is a claim about money. These are the only
two states that **cannot be asserted**: the generic transition endpoint refuses them, and
they are reachable only through the settlement operations, which write the transition and
the transfer **in one database transaction**.

That is the whole point of putting the ledger and the lifecycle in one service. An invoice
can never be marked paid without the entries that prove it, and money can never move
without the invoice recording why. If the buyer cannot cover the escrow, the transfer is
refused and the invoice does not move either: the rollback takes both.

**Each invoice gets its own escrow account**, created the first time it is funded. Escrow
is the reason the buyer's money leaves their control before the seller has earned it, so
holding it in a named account per invoice means "whose money is this, and against what"
always has an answer.

```
buyer  --250.00-->  escrow:inv-8a31c40e   (escrow_funded)
                            |
                            +--250.00-->  seller   (settled)
```

**Both operations are idempotent.** A client whose connection dropped mid-settlement can
ask again and gets the original transition and transfer back, flagged as a replay, with no
second payment. The transfer's idempotency key is the evidence that the work was done.

## The chain rail

The second way money arrives. An escrow can be funded from a bank account through the API,
or on chain by a buyer paying a Solidity contract, and **both land in the same ledger**.
That is the whole design: one ledger, two rails, and a reconciliation that can compare
them because they are written in the same entries. Reconciliation is below.

`contracts/InvoiceEscrow.sol` holds a stablecoin payment per invoice and emits an event
carrying the invoice id. It deliberately does not model the lifecycle: that already exists
off chain, and duplicating it would create two authorities that can disagree. What it
guarantees is narrower and more useful, that funds can be deposited once and can only
leave to the seller or back to the buyer.

`EscrowWatcher` turns those events into entries. Reading a chain is harder than reading a
queue for three reasons, and each one shapes the code:

**The same event arrives more than once.** A restart, an overlapping scan or a
reorganisation all replay events. Transaction hash and log index are the identity, and
every write downstream is keyed on them, so a deposit can be handed over any number of
times and credited exactly once.

**A new block is not a fact yet.** Nothing is acted on until it is buried under the
configured number of confirmations. Until then the deposit is recorded as pending and the
invoice is untouched.

**The chain can take it back anyway.** A block number is a position; the hash is the
identity. If the hash at that height has changed, what was credited is no longer true. The
credit is reversed with **a new pair of entries in the opposite direction**, never a
deletion, and the invoice is frozen so a person looks at it. If the transaction simply
moved to a different block, it is re-anchored rather than written off.

Two cases the tests pin down because they are the ones that hurt:

- **A deposit for an invoice that is not ready** is held, not lost, and credited on a later
  pass once the invoice catches up. Money arriving on chain does not entitle an invoice to
  skip its own steps.
- **A reversal after the escrow was already paid out** cannot be taken back from the
  seller. It is absorbed by `chain-shortfall:<currency>`, whose negative balance is the
  platform's exposure to reorganisations it settled too early: a number somebody can watch
  rather than a loss nobody can find. The invoice stays settled, because it really was.

Currency codes are three to five letters rather than strictly ISO 4217, because this rail
settles in stablecoins and `USDC` is four.

**Status, honestly:** the watcher and its reversal logic are tested against a fake chain
that reorganises on demand, which is the one thing a real testnet will not do when asked.
The contract is written but **not deployed and not yet exercised against a node**. The
remaining work is a `ChainSource` backed by a real client and a run against a local chain.

## Reconciliation

The two rails are written by the same code in the same transactions, so in theory they
cannot disagree. Reconciliation exists because "in theory" is the part that fails. A bug,
a manual correction, a half-applied migration or a restore from a backup taken mid-flight
all produce books that are internally consistent and wrong, and none of them go through
the code that would have prevented them.

So every check derives its answer from stored rows rather than from anything the service
remembers, and the checks run in both directions. Asking only "was every confirmed deposit
credited" finds money owed to customers and misses money credited that nobody ever sent,
which is the more expensive of the two.

| Check | What it would catch |
|---|---|
| Every entry in a currency nets to zero | Any single-sided write, whatever produced it |
| Every transfer is a pair of entries netting zero | Two faults that cancel out across the book |
| Every confirmed deposit has its transfer, for the right amount, into the right escrow | A credit lost, duplicated or misdirected |
| Every reversed deposit has both the credit and the reversal | Money the chain took back and the ledger still holds |
| Every `chain-deposit:` transfer has a confirmed deposit behind it | Money credited that the chain never sent |
| `chain:<currency>` equals the negative of what the chain confirmed | Anything that touched the chain account outside the watcher |
| No customer account is negative | A row lock that did not serialise what it was supposed to |

The last one is the aggregate check, and it is deliberately computed from the opposite end
of the data to the per-deposit ones. When both fire on the same fault they corroborate
each other; when only the aggregate fires, something reached the chain account by a route
the per-deposit checks do not look at.

**Every check reads one snapshot.** The run is a single `repeatable read` transaction,
and that is the only reason it is a transaction at all, since the checks write nothing
they read. Under `read committed` each statement takes its own snapshot, so a settlement
committing between the two halves of the aggregate check would be counted by one and not
the other, and the run would report a chain account short by exactly one deposit that had
been credited perfectly. A reconciler that cries wolf every few runs is worse than no
reconciler, because it teaches an operator to close the alert without reading it. Not
`serializable`: that protects against write skew, and this writes nothing the checks read.

The cost is an open snapshot for the length of the run, which holds vacuum back on a large
book. That is one of the reasons the next version reconciles from a watermark rather than
scanning everything.

**Nothing here repairs anything.** A reconciler that silently corrects what it finds
destroys the evidence of how the books came to be wrong, and the second occurrence then
looks like the first.

Runs are stored, findings and all, in `reconciliation_run` and `reconciliation_finding`,
both append-only like everything else that is a record of what happened. The counts are
stored with the verdict on purpose: "no discrepancies" means nothing on its own, because a
run that checked nothing because the watcher had silently stopped reports exactly the same
thing as a healthy one.

A fixed delay drives it, not a fixed rate, so a slow pass on a large book cannot queue
runs behind itself. There is one honest limitation: two instances against one database
would both reconcile and both write a report of the same facts. That is wasteful rather
than dangerous, since reconciliation only reads the ledger, and the fix is a lease this
does not have yet.

## The API

Spring Boot, and only at the edge. No class in the domain carries a Spring annotation, so
the rules stay testable in milliseconds and the edge could be replaced without touching
them. Flyway and the connection pool are Boot's to manage, because that is what it is good
at.

| | |
|---|---|
| `POST /api/v1/accounts` | Opens an account. 201, or 409 if the id is taken. |
| `GET /api/v1/accounts/{id}` | The account and its balance. |
| `GET /api/v1/accounts/{id}/entries` | Every entry against it, oldest first. |
| `POST /api/v1/transfers` | Moves money. Requires an `Idempotency-Key` header. |
| `POST /api/v1/invoices` | Opens an invoice in `draft`. |
| `GET /api/v1/invoices/{id}` | Where it is, what blocks settlement, and what it may become next. |
| `GET /api/v1/invoices/{id}/transitions` | Every step it has taken. |
| `POST /api/v1/invoices/{id}/transitions` | Moves it, optionally guarded by `expected`. |
| `POST /api/v1/invoices/{id}/escrow-funding` | Funds the escrow from the buyer and marks it funded, atomically. |
| `POST /api/v1/invoices/{id}/settlement` | Releases the escrow to the seller and marks it settled, atomically. |
| `POST /api/v1/reconciliation/runs` | Reconciles now. 201 whatever it finds: the run happened, and the verdict is in the body. |
| `GET /api/v1/reconciliation/runs/latest` | The last run and its findings. |

```http
POST /api/v1/transfers
Idempotency-Key: 8f14e45f-ceea-467a-9f7c-3b2a0d5e1c94

{"from": "alice", "to": "bob", "amountMinor": 2500, "currency": "EUR"}
```

The key is a header, not a body field, following the convention payments APIs have settled
on: it describes the request rather than the money, so a client retrying blindly does not
have to rebuild the payload to reuse it.

**A new transfer is 201. A replay is 200**, carrying the original transfer with
`"replayed": true`, so a client can tell its retry was recognised without the answer
changing.

Refusals carry a `reason` a client can branch on, rather than a parsed message:

| Reason | Status | Why that one |
|---|---|---|
| `UNKNOWN_ACCOUNT` | 404 | The resource named is not there to act on |
| `AMOUNT_NOT_POSITIVE`, `SAME_ACCOUNT` | 400 | Malformed in the plain sense |
| `CURRENCY_MISMATCH`, `INSUFFICIENT_FUNDS` | 422 | Understood, and refused by the ledger's rules; retrying unchanged fails identically |
| `UNKNOWN_INVOICE` | 404 | Same |
| `ILLEGAL_TRANSITION`, `TERMINAL_STATE` | 422 | The invoice cannot make that move from where it stands |
| `STATE_CHANGED` | 409 | It moved under the caller; re-read and decide again, the same command may be valid next time |
| `MONEY_MOVEMENT_REQUIRED` | 422 | The move is legal but money has to change hands with it, so it belongs to a settlement endpoint |

```bash
mvn spring-boot:run
```

Reads `LEDGER_JDBC_URL`, `LEDGER_DB_USER` and `LEDGER_DB_PASSWORD`, and migrates on start.

## Tests

141 tests, all green: the domain rules in microseconds with no database, the storage layer
against a real PostgreSQL, and the HTTP contract against the running application context.
The
concurrency tests release every thread from a barrier at the same instant, one virtual
thread per task, so they genuinely contend.

Most test classes isolate themselves by generating their own account ids and leaving
everyone else's rows alone. The reconciliation tests cannot: a finding is a statement
about the whole database, so a leftover from another class would be part of the answer.
Each of those gets a freshly migrated schema of its own, which is what lets them assert
not "at least one finding" but "this finding, and nothing else wrong".

| What is proved | Where |
|---|---|
| A transfer is two entries summing to zero | `TransferServiceTest.TheInvariant` |
| The whole ledger sums to zero after any sequence | same |
| A replayed key returns the original and moves nothing | `TransferServiceTest.Idempotency` |
| Unknown account, non-positive amount, self-transfer, currency mismatch, insufficient funds | `TransferServiceTest.Rejections` |
| 50 racing transfers cannot overdraw an account | `ConcurrentTransferTest` |
| 20 racing retries of one payment move money once | same |
| Money is conserved under mixed concurrent load | same |
| Migrations apply and the rules hold against real SQL | `PostgresTransferServiceTest` |
| A row lock stops 30 racing transfers from overdrawing | same |
| The unique constraint settles a real race on one key | same |
| The database refuses an update or delete on `entry` | same |
| A new transfer is 201, a replay is 200 and the same transfer | `LedgerApiTest` |
| Every refusal reaches the status code it deserves, with a machine-readable reason | same |
| A missing idempotency key is refused outright | same |
| Every status has a transition row, and only `settled` and `cancelled` are final | `InvoiceStateMachineTest` |
| The happy path is walkable step by step; a draft cannot jump to settled | same |
| Only `delivery_confirmed` and `settlement_pending` are ready to settle | same |
| An invoice's status really is its latest transition | `PostgresInvoicesTest` |
| An illegal move leaves no trace, and history cannot be rewritten | same |
| 12 racing clients produce one move and eleven conflicts | same |
| Each invoice refusal reaches 404, 422 or 409 as it should | `InvoiceApiTest` |
| A deposit waits for its confirmations before anything moves | `EscrowWatcherTest` |
| Polling repeatedly credits the same deposit exactly once | same |
| A reorg reverses the credit with new entries and freezes the invoice | same |
| A deposit dropped before it was credited costs nothing | same |
| A transaction re-mined elsewhere is followed, not written off | same |
| Money for an invoice that is not ready waits, then lands | same |
| A reversal after payout is recorded as the platform's loss | same |
| An invoice walked end to end moves the money exactly once | `InvoiceSettlementTest` |
| An invoice cannot be declared funded or settled | same |
| A buyer who cannot pay leaves the invoice exactly where it was | same |
| Settling or funding twice returns the first result and pays once | same |
| An invoice cannot be settled before delivery is confirmed | same |
| A credited deposit, and a reversed one, both reconcile clean | `ReconcilerTest` |
| A confirmed deposit with no transfer behind it is caught | same |
| A deposit marked reversed with nothing reversing it is caught twice over | same |
| Money credited under a deposit key the chain never sent is caught | same |
| An entry added behind the service's back unbalances its transfer and its book | same |
| A customer account driven negative by a write that skipped the rules is caught | same |
| Money leaving the chain account by any other route breaks the aggregate check | same |
| A run and its findings are stored, and read back as they were found | same |
| A settlement committing mid-run cannot make the reconciler cry wolf | same |
| A run is 201 whatever it found, and is the one `latest` returns | `ReconciliationApiTest` |

```bash
mvn test
```

Java 21 and Maven. The Postgres tests start a container through Testcontainers, so Docker
needs to be running. If Testcontainers cannot reach your Docker daemon, point the suite at
any Postgres instead:

```bash
LEDGER_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/ledger_test \
LEDGER_TEST_DB_USER=postgres \
LEDGER_TEST_DB_PASSWORD=postgres \
mvn test
```

## Status

**Shipped:** the domain core, the Postgres storage layer, Flyway migrations, jOOQ
generated from those migrations, the HTTP API, the invoice lifecycle, escrow funding and
settlement, the on-chain deposit rail with its reorg handling, reconciliation and its
schedule, and the tests above. The escrow contract is written but not yet deployed or run
against a node.

**Next, in order:**

1. A `ChainSource` backed by a real Ethereum client, and the contract deployed to a local
   chain so the rail is exercised end to end rather than against a fake.
2. A lease on the reconciliation schedule, so more than one instance can run safely.

One ledger, two settlement rails.
