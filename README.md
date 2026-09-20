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

### Talking to a node

The watcher's logic is proved against a fake chain that reorganises on demand, because
that is the one thing a real testnet will not do when asked. What a fake cannot prove is
that the node is being asked the right questions, because a fake answers in whatever shape
the code that wrote it expected. `EthereumChainSource` and `EscrowContractReserves` are
the real client, and they are tested against a real node running a real deployment of the
contract.

**There is no chain library in this service, on purpose.** The watcher reads; it never
signs, never sends a transaction and never holds a key. That reduces the entire chain
dependency to four JSON-RPC calls, `eth_blockNumber`, `eth_getBlockByNumber`,
`eth_getLogs` and `eth_call`, over a wire format that has not moved in years. A client
library would add a large dependency and a code generator to the deployment image in
exchange for four requests, and would hide the part worth reading: exactly what is asked
of the node and exactly how the answer is decoded. A component that can only read also
cannot be made to move money by a bug, and the settler key lives where releases are
authorised rather than where the chain is followed.

Three decisions the decoding forces, none of them obvious:

**The invoice id travels as its own UTF-8 bytes, right-padded**, not as a hash of itself.
Hashing looks like the safer choice for trade finance, where who trades with whom is
commercially sensitive, and it buys nothing here: the same event indexes the buyer and
seller addresses, which *is* the relationship, while the id is an opaque token naming no
party. Hashing it would hide a meaningless string, leave the meaningful pair in plain
sight, and cost a mapping that has to be kept for ever before any deposit could be
attributed. Keeping counterparties private is a real problem and a different one, answered
by not reusing addresses. The cost is a hard 32-byte limit, and an id that does not fit is
refused rather than truncated: two invoices agreeing in their first 32 bytes would
otherwise share an escrow.

**The currency code is configured, never read from the token.** An ERC-20 reports its own
symbol and anyone can deploy a contract calling itself USDC. Taking the code from the
token would let whoever deployed it choose which book the money lands in and which
balances it is then netted against.

**A minor unit is the token's own smallest unit**, so nothing is scaled and nothing is
rounded. The chain counts in 256 bits and `amount_minor` is a `bigint`, so the boundary
refuses an amount that will not fit rather than truncating it. In practice that fires when
the contract is pointed at a token with eighteen decimals, which is a misconfiguration
worth stopping on.

The reserve check asks the **token** for `balanceOf(escrow)`, not the escrow for its own
total. A `totalHeld()` getter on `InvoiceEscrow` would have been easier to call and
worthless to trust: it would be the escrow's own bookkeeping, which is the kind of thing
this check exists to verify. The token's ledger is the one that decides whether the money
can actually be paid out.

### Running the contract

The contract is compiled and deployed by hand rather than by `mvn test`, so no unit test
depends on a Solidity toolchain. Everything runs in Docker; nothing is installed on the
machine:

```bash
docker run -d --name settletrust-anvil -p 8545:8545 \
  ghcr.io/foundry-rs/foundry:latest "anvil --host 0.0.0.0"

cd contracts
git clone --depth 1 --branch v5.1.0 \
  https://github.com/OpenZeppelin/openzeppelin-contracts lib/openzeppelin-contracts
docker run --rm -v "$PWD:/work" -w /work ghcr.io/foundry-rs/foundry:latest "forge build"
```

On Windows under git bash, prefix the mounting command with `MSYS_NO_PATHCONV=1` and give
the host path as `C:/...`, or the shell rewrites it into something Docker reads as a
volume name.

`contracts/lib` and `contracts/out` are ignored: vendoring the whole of OpenZeppelin would
bury the twenty lines that are actually ours.

With `LEDGER_TEST_ETH_RPC` set the chain tests deploy a fresh token and escrow per test
and drive them; without it they skip, because a missing toolchain says nothing about the
ledger. Transactions are sent against anvil's own unlocked accounts, which is why no
signing code exists anywhere in this repository, including the tests.

**Status, honestly:** the contract compiles, deploys and runs, and the rail has been
driven end to end against a real node: deposited on chain, credited by the watcher, and
the resulting books checked by the reconciler against the token contract's own balance.
It has **never been deployed to a public network**, and it has not been audited. The
confirmation depth that would be right for one is a configuration question nobody has
answered with real numbers.

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
| The escrow contract holds at least what the ledger credited out of it | A watcher that misread the chain, and every check above agreeing with it |
| The totals a run carries forward are what the entries actually say | A fold that drifted, or history rewritten below the watermark |

The sixth is the aggregate check, deliberately computed from the opposite end of the data
to the per-deposit ones. When both fire on the same fault they corroborate each other;
when only the aggregate fires, something reached the chain account by a route the
per-deposit checks do not look at.

**The last one is the only check the chain is the authority for, and it exists because of
a hole in all the others.** Every check above it compares the ledger against
`chain_observation`, and the watcher wrote both. A watcher that misread the chain,
credited an event twice or invented one produces two records that agree perfectly and are
both wrong. Nothing that cross-checks them can tell. So the reconciler asks the contract
what it is actually holding, through a one-method `EscrowReserves` port kept separate
from `ChainSource`: a reconciler that needs a balance should not be handed the ability to
replay history.

A shortfall is the alarm, and it is computed against the `chain:<currency>` ledger
balance rather than the observations, so neither side of that comparison shares a source
with the other. A surplus is a question rather than an alarm, because deposits still
waiting for confirmations are legitimately in the contract already; only what those fail
to explain is reported, and that remainder is either tokens sent straight to the contract
address or an event the watcher never saw.

The reads are ordered so the alarm cannot be wrong. The database snapshot is taken first
and the chain asked last, so the chain's answer is never older than the ledger it is
compared against. A deposit confirmed mid-run therefore shows up on chain and not in the
ledger, which inflates the surplus and can never manufacture a shortfall. The timing
artefact lands on the finding that tolerates one.

**And the report says whether it asked.** The `EscrowReserves` bean exists only when
`ledger.chain.rpc-url` is configured, so a deployment without a node produces reports
carrying `reservesChecked: false`. Without that flag a report from a deployment with no
node would be indistinguishable from one that had verified the money was really there,
and the second is what an operator would assume.

**Every check reads one snapshot.** The run is a single `repeatable read` transaction,
and that is the only reason it is a transaction at all, since the checks write nothing
they read. Under `read committed` each statement takes its own snapshot, so a settlement
committing between the two halves of the aggregate check would be counted by one and not
the other, and the run would report a chain account short by exactly one deposit that had
been credited perfectly. A reconciler that cries wolf every few runs is worse than no
reconciler, because it teaches an operator to close the alert without reading it. Not
`serializable`: that protects against write skew, and this writes nothing the checks read.

The cost is an open snapshot for the length of the run, which holds vacuum back on a large
book, which is why most runs no longer read the whole book at all.

### Reconciling from a watermark

Scanning everything every fifteen minutes is correct and does not scale, so a run normally
looks only at the transactions committed since the last one. The entire difficulty is in
saying what *since* means.

**It cannot mean an id or a timestamp.** Both are chosen by the row while its transaction
is still open, and transactions do not commit in the order they started: a transfer that
takes sequence value 100 can commit after one that took 101. A run that marks itself at
101 has already passed 100 by the time it lands, and the gap is permanent and silent.
That is the worst failure a reconciler can have, because every symptom of it is a clean
report.

So the mark is on visibility instead. Every append-only row records the id of the
transaction that wrote it, and a run's new mark is `pg_snapshot_xmin`: the oldest
transaction still in flight when the run took its snapshot. Nothing below that can still
arrive, because a transaction is given its id when it first writes and one that has not
written yet will be given a higher one. Consecutive windows therefore tile the history
with no row seen twice and none skipped.

The failure mode is the right way round, too. A long-running transaction anywhere in the
database pins `xmin`, the mark stops advancing, and the run does *less work* rather than
missing work. `xid8` rather than `xid`: 64 bits, so it does not wrap and two of them can
be compared without knowing where the counter currently sits.

**Which checks may use the window is decided by the data.** The rule is that only what
cannot change may be skipped:

| | Treatment | Why |
|---|---|---|
| Sums over `entry` | Folded: window sum plus the previous run's total | An entry is written once and never revised, so a window sum is a true delta |
| Checks that name one subject | The window picks which subjects to look at; each is then read in full | An account nothing was posted to cannot newly be overdrawn; a transfer nothing was posted to cannot newly be unbalanced |
| Aggregates over `chain_observation` | Not windowed at all | That table is a cursor over a chain that changes its mind, and leaving a mutated row out of a sum does not remove what it used to contribute |

An observation that *does* change is restamped by a trigger and falls back inside the next
window, which is what keeps the per-deposit checks honest across a reorg: a deposit
reversed long after it was last examined comes back under its new status.

Because the net is folded rather than re-queried, an incremental run still answers for the
whole book. A window with nothing in it does not report a clean net; it reports the net
the book has.

**The fold is the one thing here that trusts a number this service wrote, and it is
trusted on one condition.** Every other check in this file goes out of its way to compare
two independent sources, and a carried total is neither. A figure that went wrong once
would be carried forward by every run after it, each agreeing with the last and none
looking at the entries again. So a full run comes round on a timer, re-derives the same
totals from the entries at exactly the mark the earlier run stopped at, and raises
`CHECKPOINT_DRIFT` when they part company. That catches both a bug in the arithmetic and a
rewrite of history below the watermark, which the append-only triggers are supposed to
make impossible and this is how anyone would find out they had not. The entry count is
folded alongside the net, because two errors in opposite directions leave a net untouched
and both of them change the count behind it.

The mode and the window are stored with every report, because they change what the counts
mean. Two deposits checked by a full run is a two-deposit book; two checked by an
incremental one is two deposits since the last tick, and says nothing about the rest. The
one thing an incremental run cannot do is notice a fault in history it has already passed,
so "the last run was clean" is a statement about a window. Only the last full run is a
statement about the ledger.

**Nothing here repairs anything.** A reconciler that silently corrects what it finds
destroys the evidence of how the books came to be wrong, and the second occurrence then
looks like the first.

Runs are stored, findings and all, in `reconciliation_run` and `reconciliation_finding`,
both append-only like everything else that is a record of what happened. The counts and
the `reserves_checked` flag are stored with the verdict on purpose: "no discrepancies"
means nothing on its own, because a run that checked nothing because the watcher had
silently stopped reports exactly the same thing as a healthy one.

A fixed delay drives it, not a fixed rate, so a slow pass on a large book cannot queue
runs behind itself.

**Several instances can run it.** Each takes a lease before it starts, so one does the
work and the others find it taken and go back to sleep. The lease is a Postgres advisory
lock scoped to the run's transaction, chosen over a row or a Redis key because it needs
no cleanup and cannot go stale: an instance that dies mid-run loses its connection,
Postgres ends the transaction, and the lock goes with it. A lease held in a table needs
an expiry, and choosing one means guessing how long a run takes on a book you have not
seen yet.

It is tried without waiting rather than blocked on. The runs are on a timer, so the next
one is minutes away, and a queue of instances waiting on a lock is a queue of open
snapshots holding vacuum back.

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
| `POST /api/v1/reconciliation/runs` | Reconciles now. 201 whatever it finds: the run happened, and the verdict is in the body, with `mode` and `reservesChecked` saying what it covered and whether the chain was asked. `?deep=true` re-derives the whole book instead of the window since the last run, which is what to reach for in an incident. 409 if another instance holds the lease, which is a different thing from a run that found problems and worth retrying. |
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

## Running it in a cluster

```bash
docker build -t settletrust-ledger:local .
kubectl apply -f k8s/
```

Two stages in the `Dockerfile`, because the image that runs a ledger should not contain a
compiler, a Maven cache or the source. The final image is a JRE, a jar and a user that is
not root. `MaxRAMPercentage` is set because a JVM that sizes its heap against the node
rather than the cgroup gets killed by the kernel, which tells nobody why.

**Two replicas in the Deployment, on purpose.** The reconciler takes its lease before it
runs, so one replica does the work and the other finds it taken. A single replica would
leave that lease untested by the thing that is supposed to need it.

**The probes are not the same check.** This is the part worth reading:

| Probe | Includes the database | Why |
|---|---|---|
| `startup` | yes | Flyway runs on boot and the JVM is not quick. Without it, liveness starts counting against a pod that is migrating perfectly well |
| `liveness` | **no** | A pod that cannot reach Postgres is not broken, the database is. Restarting every replica in a loop while it recovers makes the outage worse |
| `readiness` | yes | A replica that cannot reach Postgres cannot answer, and should leave the load balancer until it can |

Memory is limited and CPU is not. Exceeding a memory limit is a kill rather than a
slowdown, so the limit is real protection; throttling a JVM mid-request to enforce a CPU
ceiling nobody is contending for adds latency and saves nothing, and the request is what
the scheduler actually needs.

There is no Postgres in `k8s/`, deliberately. A StatefulSet running the database that
holds the ledger is a worse answer than a managed instance, and shipping one here would
suggest otherwise. `secret.example.yaml` is an example for the same reason: a base64
string in a repository is an unencrypted password with an extra step.

`.github/workflows/build.yml` runs the suite on Testcontainers rather than a service
container, so CI exercises the same default path a fresh clone does, and builds the image
and validates the manifests alongside it.

**Verified how far.** The image builds and runs as uid 1000 on a read-only root
filesystem. The manifests pass `kubeconform -strict` against the real Kubernetes
schemas. The probe split was tested rather than asserted: with the service running
against a Postgres in its own container, stopping that Postgres leaves liveness at
`200 UP` and takes readiness to `503 DOWN`, and starting it again brings readiness back
with the restart count still at zero. That is the whole argument for the split, and it
holds: the pod rides out a database outage instead of being killed through one.

**Nothing here has been applied to a running cluster.** Kubernetes is the one thing in
this repository that has not been operated, and no amount of valid YAML changes that.

## Tests

185 tests, all green: the domain rules in microseconds with no database, the storage layer
against a real PostgreSQL, and the HTTP contract against the running application context.
The
concurrency tests release every thread from a barrier at the same instant, one virtual
thread per task, so they genuinely contend.

**JUnit and Spock, and the split is not arbitrary.** JUnit keeps the cases that are one
scenario each. Spock has the parts where the subject genuinely is a table: which refusals
carry which reason, and what an invoice is waiting for at each status. Those were a list
of near-identical methods, and a `where:` block says the same thing in the shape the
thing actually has. Writing the spec also turned up two properties no amount of per-case
testing would have caught, both of which now hold: a status is final exactly when it has
nowhere left to go, and every status is reachable from a draft. A status nobody can get
to is dead code with a name.

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
| A contract holding what the ledger credited reconciles, and the run says it asked | same |
| A contract short of what the ledger credited is caught when nothing else can be | same |
| A deposit still awaiting confirmations explains a surplus rather than raising one | same |
| Money in the contract that nothing explains is raised as a question | same |
| With no chain to ask, the report says so rather than reading as verified | same |
| A second instance finds the lease taken and writes no duplicate report | same |
| The first run is deep, and the next one continues where it stopped | `ReconcilerTest.FromAWatermark` |
| A deep run comes round again once the last one has aged out | same |
| A book that was already wrong stays wrong in the eyes of an empty window | same |
| An entry added after a clean run pulls its whole transfer back into view | same |
| An account driven negative after a clean run is caught by the window | same |
| A deposit reversed after it was checked is examined again | same |
| A carried total the entries do not support is itself a finding | same |
| A transaction that commits out of order is not passed over | same |
| A deposit is read back from a real node as the chain reported it | `EthereumChainSourceTest` |
| The block hash on a deposit is the hash of the block it landed in | same |
| The right log is picked out of a transaction that emitted several | same |
| Only deposits into this escrow are ours | same |
| The reserve check reads the token's balance, not the escrow's own opinion | same |
| Money sent straight to the contract counts, and is what a surplus is made of | same |
| An invoice id too long for the contract is refused rather than truncated | same |
| Money paid on chain funds a real invoice, once it is deep enough | `OnChainRailTest` |
| A second pass over the same chain credits nothing twice | same |
| The reconciler verifies the books against the real token contract | same |
| A contract short of what the ledger credited is caught against a real node | same |
| A status is final exactly when it has nowhere left to go | `InvoiceLifecycleSpec` |
| Every status is reachable from a draft, so none is stranded | same |
| Each illegal move carries the reason it deserves, across nine cases | same |
| Each status reports exactly what it is waiting on, across eleven | same |
| A run is 201 whatever it found, and is the one `latest` returns | `ReconciliationApiTest` |
| `deep=true` re-derives the book rather than the window | same |

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
settlement, the on-chain deposit rail with its reorg handling, reconciliation from a
watermark with its periodic full sweep, the schedule and lease, the Ethereum client and
the escrow contract it reads, and the tests above. The contract has been compiled,
deployed and driven end to end against a local node; it has never been on a public
network and has not been audited.

**Next, in order:**

1. Forge tests for `InvoiceEscrow` itself. It is exercised end to end now, which proves
   the happy path and nothing about a release by somebody who is not the settler, a
   double refund, or a token that takes a fee.
2. A findings feed an operator can subscribe to. Runs and findings are stored and
   readable one at a time; what is missing is "what is open right now", which an
   incremental run cannot answer on its own because a fault it reported once and nobody
   fixed does not reappear in later windows.

One ledger, two settlement rails.
