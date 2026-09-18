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

```bash
mvn spring-boot:run
```

Reads `LEDGER_JDBC_URL`, `LEDGER_DB_USER` and `LEDGER_DB_PASSWORD`, and migrates on start.

## Tests

31 tests, all green: 18 against the in-memory ledger, 6 against a real PostgreSQL, 7
against the running application context. The
concurrency tests release every thread from a barrier at the same instant, one virtual
thread per task, so they genuinely contend.

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
generated from those migrations, the HTTP API, and the tests above.

**Next, in order:**

1. The invoice state machine ported from the SettleTrust frontend, server-side, as the
   authority rather than a client-side convenience.
2. An escrow rail on-chain: a Solidity contract holding funds against the invoice's escrow
   state, and a watcher that posts its events into this same ledger, using the transaction
   hash as the idempotency key, with a confirmation depth before an entry counts and a
   reversal path when a reorg takes it back.
3. Reconciliation as a scheduled job, proving the two rails agree.

One ledger, two settlement rails.
