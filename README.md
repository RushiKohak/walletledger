# WalletLedger

Double-entry wallet and payments ledger service in Java 21 and Spring Boot: ACID transfers, idempotency keys, PostgreSQL, Docker

WalletLedger keeps money as signed rows in a ledger instead of overwriting a number. Every movement
(deposit or transfer) writes one `ledger_transactions` row and the `ledger_entries` that belong to it,
inside a single database transaction. Balances are a cached result of that history, never the source
of truth.

## Features

- **Accounts** — create a wallet for an owner in a three-letter currency, read it back by id.
- **Deposits** — add funds to a wallet, balanced against a per-currency SYSTEM treasury account.
- **Transfers** — move funds between two wallets atomically; the sender is debited and the receiver
  is credited by the same amount in one transaction.
- **Idempotency** — every money movement carries an `Idempotency-Key`. A retry with the same key
  returns the original response verbatim instead of moving money again; the same key with a
  different payload is rejected.
- **Append-only ledger** — entries are inserted once and can never be updated or deleted, enforced
  in the database rather than in application code.
- **Statement endpoint** — a paged, newest-first list of an account's ledger entries.
- **One error shape** — every failure, from validation to an unexpected exception, returns the same
  JSON body.

## Design decisions

- **Money is `BIGINT` minor units plus a currency code.** Amounts are paise/cents in a whole number
  column, never `float`, `double` or `BigDecimal` columns. Arithmetic uses `Math.addExact` /
  `Math.subtractExact`, so an amount that would overflow a balance fails the request instead of
  wrapping around into a wrong balance.
- **Double entry.** Each movement writes entries that net to zero: a transfer is one `DEBIT` on the
  sender and one `CREDIT` on the receiver, and a deposit is a `CREDIT` on the wallet plus a `DEBIT`
  on the treasury. Total money per currency is unchanged by a transfer.
- **A SYSTEM treasury account per currency.** Deposits need a counterparty or the books would not
  balance, so the first deposit in a currency creates a guarded `SYSTEM` account. A partial unique
  index allows exactly one treasury per currency, and the balance check lets the treasury go negative
  while user wallets may not.
- **PostgreSQL only.** Tests run against a real PostgreSQL through Testcontainers, so constraints,
  locking and triggers behave the same in tests as in production. No in-memory database stands in.
- **Flyway owns the schema.** Migrations under `src/main/resources/db/migration` are the only way the
  schema changes; `spring.jpa.hibernate.ddl-auto=validate` means Hibernate may check entities against
  the migrated tables but never create or alter them.
- **Append-only is enforced by the database.** A trigger rejects `UPDATE` and `DELETE` on
  `ledger_entries`, and `TRUNCATE` is blocked as well. A bug in the service layer cannot rewrite
  history.
- **Lock ordering instead of optimistic retries.** A money movement takes a transaction-scoped
  advisory lock on its idempotency key, then locks the account rows with `PESSIMISTIC_WRITE` in
  ascending id order. A consistent lock order makes the classic `A → B` / `B → A` deadlock
  impossible: the two requests can only queue behind each other. Account identity is read as a
  projection before locking, so no stale entity can be written back.
- **Every rule is checked against locked rows.** Currency match, account type, self-transfer and
  sufficient funds are all validated after the rows are locked, so the values checked are the values
  written.
- **Idempotency is stored, not remembered.** The key, a SHA-256 hash of the canonical request and the
  exact response body live on the transaction row. A replay returns the stored JSON with
  `Idempotent-Replayed: true`, so a client sees byte-identical data even after a restart. A
  `UNIQUE (idempotency_key)` constraint is the final backstop if two requests ever race past the
  advisory lock.
- **One transaction per movement.** Locks, balance updates, the transaction row and both entries
  commit together or not at all, so a rejected or crashed request leaves no trace and no
  half-applied money.
- **HTTP translation stays in the web layer.** Controllers only turn HTTP into service calls; the
  service layer never sets status codes. `ApiExceptionHandler` maps every exception to the same error
  body, and the catch-all handler logs the real exception server-side while returning a generic
  message, so stack traces and internal details never reach a client.
- **Errors carry meaning through status codes.** `404` for a missing account, `409` when a request
  conflicts with current state (insufficient funds, where a retry could succeed later), `422` when a
  key is reused with a different payload (retrying unchanged can never succeed), `400` for anything
  malformed.
- **Reads are separate from money paths.** The statement endpoint runs in a read-only transaction, uses
  projections rather than entities, and has an explicit `order by e.id desc` with an explicit count
  query, so paging is stable and does not wander as rows are added.

## API

All endpoints are under `/api/v1`. Monetary fields are whole minor units (paise/cents).

| Method | Path | Body / query | Success | Errors |
| --- | --- | --- | --- | --- |
| `POST` | `/accounts` | `{"ownerName": "...", "currency": "INR"}` | `201` `{id, ownerName, currency, balance, type}` | `400` |
| `GET` | `/accounts/{id}` | — | `200` `{id, ownerName, currency, balance, type}` | `404`, `400` |
| `GET` | `/accounts/{id}/entries` | `?page=0&size=20` | `200` `{content, page, size, totalElements, totalPages}` | `400`, `404` |
| `POST` | `/deposits` | `Idempotency-Key` header, `{"accountId": 1, "amount": 1000, "currency": "INR"}` | `201` `{transactionId, accountId, amount, currency, balanceAfter}`; `200` + `Idempotent-Replayed: true` on a replay | `400`, `404`, `422` |
| `POST` | `/transfers` | `Idempotency-Key` header, `{"fromAccountId": 1, "toAccountId": 2, "amount": 500, "currency": "INR"}` | `201` `{transactionId, fromAccountId, toAccountId, amount, currency, fromBalanceAfter, toBalanceAfter}`; `200` + `Idempotent-Replayed: true` on a replay | `400`, `404`, `409`, `422` |

Notes:

- The two money endpoints differ by header: `Idempotency-Key` is required, and a missing one is a
  `400` with the standard error body.
- `size` must be between `1` and `100`; `page` starts at `0`. Entries come back newest first.
- Each entry is `{id, transactionId, direction, amount, createdAt}`, where `direction` is `DEBIT` or
  `CREDIT` and `amount` is always positive.

Every error, whatever caused it, has the same shape:

```json
{
  "status": 409,
  "error": "Conflict",
  "message": "account 1 has insufficient funds ...",
  "timestamp": "2026-01-01T00:00:00Z"
}
```

## Running

### With Docker Compose

From this directory:

```bash
docker compose up -d --build
```

The app listens on `http://localhost:8080`, waits for the database to pass its `pg_isready`
healthcheck before starting, and keeps data in the `db-data` named volume. The database port is not
published; uncomment it in `docker-compose.yml` if you want to connect with `psql` from the host.

```bash
curl -i -X POST http://localhost:8080/api/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{"ownerName":"Rushi","currency":"INR"}'
```

### Locally

Requires a running PostgreSQL and JDK 21. Connection settings come from the environment, with
defaults that match `docker-compose.yml`:

| Variable | Default |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/walletledger` |
| `DB_USER` | `walletledger` |
| `DB_PASSWORD` | `walletledger` |
| `DB_POOL_SIZE` | `10` |

```bash
docker compose up -d db   # database only
./mvnw spring-boot:run
```

Flyway applies the migrations at startup. To build the jar instead:

```bash
./mvnw clean package
java -jar target/*.jar
```

On Windows, `mvnw.cmd` is the wrapper to use.

## Tests

The tests are integration tests: each one starts a real PostgreSQL container through Testcontainers
and drives the HTTP API, so the database constraints, locking and append-only trigger are exercised
for real. Docker must be running; no external database is needed.

```bash
./mvnw clean test
```

On Windows:

```powershell
.\mvnw.cmd clean test
```

CI runs the same command on every push and pull request to `main`
(`.github/workflows/ci.yml`), on a GitHub-hosted Ubuntu runner that already provides Docker.

## Load test

`k6/transfer.js` drives the transfer endpoint against a running instance and checks conservation at
the end. See `k6/README.md` for configuration, how to run it, and what to record next to a result.

## Results

