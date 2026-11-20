[READMEwalletledger.md](https://github.com/user-attachments/files/35129677/READMEwalletledger.md)
# WalletLedger

A double-entry wallet and payments ledger REST API built with **Java 21**, **Spring Boot** and **PostgreSQL**.

Money moves through deposits and transfers that are atomic, safe under heavy concurrency, and safe to retry. Every balance change is backed by an immutable ledger entry, and the database itself refuses to let history be rewritten.

- **Double-entry bookkeeping:** every money movement writes a balanced DEBIT and CREDIT pair, so the books always sum to zero.
- **Race-free transfers:** one database transaction per movement, with `SELECT ... FOR UPDATE` row locks taken in ascending account-ID order, so opposite transfers can never deadlock.
- **Idempotent writes:** every deposit and transfer needs an `Idempotency-Key`. Retrying returns the original response instead of moving money twice.
- **Append-only ledger:** PostgreSQL triggers reject `UPDATE`, `DELETE` and `TRUNCATE` on ledger entries, for every caller.
- **Exact money:** amounts are `BIGINT` minor units (paise/cents) with `Math.addExact`, never floating point.
- **66 integration tests** against a real PostgreSQL container, including 100-way parallel transfers.

## Tech stack

| Layer | Choice |
|---|---|
| Language / runtime | Java 21 |
| Framework | Spring Boot 4.1 (Web MVC, Data JPA, Validation) |
| Database | PostgreSQL 16 |
| Migrations | Flyway (`V1`–`V4`); Hibernate only validates (`ddl-auto=validate`) |
| Build | Maven (wrapper included) |
| Tests | JUnit 5, Testcontainers (real PostgreSQL), JDK `HttpClient` over a real socket |

## Quick start

**Prerequisites:** JDK 21 and Docker.

**1. Start PostgreSQL**

```bash
docker run -d --name walletledger-db \
  -e POSTGRES_DB=walletledger -e POSTGRES_USER=wallet -e POSTGRES_PASSWORD=wallet \
  -p 5432:5432 postgres:16-alpine
```

**2. Point the app at it and run.** The datasource is not hard-coded, so supply it through environment variables. Flyway creates the schema on startup.

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/walletledger
export SPRING_DATASOURCE_USERNAME=wallet
export SPRING_DATASOURCE_PASSWORD=wallet
./mvnw spring-boot:run
```

On Windows PowerShell:

```powershell
$env:SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/walletledger"
$env:SPRING_DATASOURCE_USERNAME="wallet"
$env:SPRING_DATASOURCE_PASSWORD="wallet"
.\mvnw.cmd spring-boot:run
```

The API is now on `http://localhost:8080`.

## Try it

The examples use `curl` in a bash-style shell (Git Bash or WSL on Windows).

```bash
# 1. Create two USD wallets
curl -X POST localhost:8080/api/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{"ownerName":"Asha","currency":"USD"}'
curl -X POST localhost:8080/api/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{"ownerName":"Ben","currency":"USD"}'

# 2. Deposit 5000 minor units (50.00 USD) into account 1
curl -X POST localhost:8080/api/v1/deposits \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: dep-001' \
  -d '{"accountId":1,"amount":5000,"currency":"USD"}'

# 3. Transfer 1200 from account 1 to account 2
curl -X POST localhost:8080/api/v1/transfers \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: tr-001' \
  -d '{"fromAccountId":1,"toAccountId":2,"amount":1200,"currency":"USD"}'

# 4. Read the statement for account 1 (newest first)
curl 'localhost:8080/api/v1/accounts/1/entries?page=0&size=20'
```

Send the same transfer again with the same `Idempotency-Key` and you get `200 OK` with an `Idempotent-Replayed: true` header and the original body. The money does not move a second time.

## API

Base path: `/api/v1`. Amounts are positive whole numbers of **minor units**.

| Method | Path | Description |
|---|---|---|
| `POST` | `/accounts` | Create a USER account. Body: `ownerName` (max 100 chars), `currency` (3 uppercase letters). |
| `GET` | `/accounts/{id}` | Account with `id, ownerName, currency, balance, type`. |
| `GET` | `/accounts/{id}/entries?page=0&size=20` | Paginated ledger entries, newest first. `size` is 1–100. |
| `POST` | `/deposits` | Credit a USER account from the currency's treasury. Needs `Idempotency-Key`. Body: `accountId, amount, currency`. |
| `POST` | `/transfers` | Move money between two USER accounts. Needs `Idempotency-Key`. Body: `fromAccountId, toAccountId, amount, currency`. |

**Transfer response**

```json
{
  "transactionId": "3f0c2a52-8d57-4e5f-9a37-5d1f0c9c2a11",
  "fromAccountId": 1,
  "toAccountId": 2,
  "amount": 1200,
  "currency": "USD",
  "fromBalanceAfter": 3800,
  "toBalanceAfter": 1200
}
```

**Paginated entries response**

```json
{
  "content": [
    { "id": 3, "transactionId": "…", "direction": "DEBIT", "amount": 1200, "createdAt": "2026-11-20T10:15:30Z" }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 2,
  "totalPages": 1
}
```

### Errors

Every failure uses one JSON shape: `{ "status", "error", "message", "timestamp" }`.

| Status | When |
|---|---|
| `400` | Validation failure, malformed JSON, missing or oversized `Idempotency-Key`, same-account transfer, wrong account type or currency, out-of-range page or size, amount overflow |
| `404` | Unknown account or unknown route |
| `405` / `415` | Method not supported / body is not JSON |
| `409` | Insufficient funds (the same request may succeed later) |
| `422` | `Idempotency-Key` reused for a *different* request (retrying unchanged can never succeed) |
| `500` | Unexpected bug. The real exception is logged server-side; the client gets a generic message and never a stack trace |

## How it works

### Double-entry ledger
A deposit debits the treasury (a `SYSTEM` account, created automatically on the first deposit in each currency) and credits the user. A transfer debits the sender and credits the receiver. Each movement writes exactly two `ledger_entries` rows under one `ledger_transactions` row, so for every currency the user balances plus the treasury always sum to zero.

### Concurrency
Each movement runs in one database transaction. Both account rows are locked with `SELECT ... FOR UPDATE` **in ascending ID order**, which means A→B and B→A queue behind each other instead of crossing. All rules (type, currency, sufficient funds) are checked against the *locked* rows, so what is validated is what gets written. Account entities are never loaded before their row is locked, so a stale balance can't be written back.

### Idempotency
- A SHA-256 hash of a canonical form of the request is stored with the transaction, along with the exact response body (`JSONB`).
- `pg_advisory_xact_lock` on the key makes concurrent requests with the same key queue up, so the second one sees the first one's committed row and replays it.
- A `UNIQUE` constraint on `idempotency_key` stays as a last-resort backstop.
- The replay lookup happens before business validation, so a transfer that succeeded once still replays its original response after the sender has spent the money elsewhere.

### Append-only ledger
`ledger_entries` has a row-level trigger rejecting `UPDATE` and `DELETE`, and a statement-level trigger rejecting `TRUNCATE` (a row trigger can't see it). Because these live in the database, `psql`, a migration script or a future service can't rewrite history either.

### Schema safeguards
`CHECK` constraints keep user balances non-negative, currency codes uppercase and three letters, amounts positive, and exactly one treasury per currency (partial unique index).

## Tests

```bash
./mvnw test
```

Docker must be running: the tests start a real `postgres:16-alpine` container through Testcontainers. Every request goes over a real HTTP socket, so controllers, JSON mapping, validation, transaction boundaries and database constraints are all exercised.

| Area | What is covered |
|---|---|
| Concurrency | 100 parallel transfers draining an account exactly; oversubscribed accounts accepting only what they can afford; 50+50 opposite-direction transfers with no deadlock; mixed traffic across 10 accounts; 100 pairs of simultaneous identical retries moving money once; two currencies in parallel |
| Invariants (checked after every concurrency test) | no negative wallets, money conserved per currency, balances equal their ledger, every transaction balanced, one transaction row per successful request |
| Idempotency | replays, reused keys, missing and oversized keys, replay after funds are spent |
| Deposits, transfers, accounts, entries | happy paths, insufficient funds, wrong currency or account type, unknown accounts, balance overflow, pagination defaults and totals, maximum page size |
| Ledger | `UPDATE`, `DELETE` and `TRUNCATE` are rejected by the database |
| Errors | one consistent error shape for routing, binding, validation and unexpected failures |

## Project structure

```
src/main/java/com/rushi/walletledger/
├── web/          controllers and request/response DTOs (HTTP only, no business rules)
├── service/      transfer, deposit, idempotency, account and entry logic
├── repository/   Spring Data JPA repositories, locking queries, projections
├── domain/       Account, LedgerTransaction, LedgerEntry and their enums
└── error/        exceptions and the single @RestControllerAdvice
src/main/resources/db/migration/   Flyway migrations V1–V4
src/test/                          integration and concurrency tests
```

## Current limitations

- No authentication or authorization.
- No refunds, reversals or multi-currency (FX) transfers; a transfer must be in a single currency.
- A transaction's status is always `POSTED`.
- No Dockerfile or docker-compose yet, so PostgreSQL is started with the `docker run` command above.
