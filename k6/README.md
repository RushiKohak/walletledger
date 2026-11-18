# Load test (k6)

A k6 script that drives the transfer endpoint against a running instance of this service and
checks, at the end, that the transfers only moved money around.

It reports whatever the machine that runs it produces. No numbers are stored here.

## Install k6 on Windows

Either of these:

```powershell
winget install k6 --source winget
```

or download the official Windows installer / zip from the k6 documentation
(<https://grafana.com/docs/k6/latest/set-up/install-k6/>) and put `k6.exe` on your `PATH`.

Check it:

```powershell
k6 version
```

## Start the stack

From the project directory (`walletledger/`):

```powershell
docker compose up -d --build
```

Wait until the API answers, for example:

```powershell
curl http://localhost:8080/api/v1/accounts/1
```

A `404` JSON error body means the service is up (there is no account 1 yet); a connection failure
means it is still starting.

The script needs the database pool it will hit, so note the `DB_POOL_SIZE` the app is running with
(compose passes it through, default 10).

## Run

```powershell
k6 run k6/transfer.js
```

Override any setting with `-e`:

```powershell
k6 run -e VUS=100 -e DURATION=2m k6/transfer.js
k6 run -e ACCOUNTS=200 -e INITIAL_BALANCE=5000000 -e TRANSFER_AMOUNT=100 k6/transfer.js
k6 run -e BASE_URL=http://localhost:8080 -e DB_POOL_SIZE=10 k6/transfer.js
```

| Variable | Default | Meaning |
| --- | --- | --- |
| `BASE_URL` | `http://localhost:8080` | Where the API is listening |
| `ACCOUNTS` | `50` | Accounts created and funded during `setup()` |
| `INITIAL_BALANCE` | `1000000` | Minor units (paise) deposited into each account, INR |
| `TRANSFER_AMOUNT` | `1` | Minor units moved per transfer |
| `VUS` | `50` | Constant virtual users for the whole run |
| `DURATION` | `60s` | How long the load phase lasts |

`DB_POOL_SIZE` is read by the application, not by k6 — it is listed above only because you need to
record it next to your results.

## What the run does

1. `setup()` creates `ACCOUNTS` accounts in INR with unique owner names and deposits
   `INITIAL_BALANCE` into each, with a unique `Idempotency-Key` per deposit. If any call does not
   return `201`, setup throws and the run stops — a load test against half-built accounts measures
   the wrong thing.
2. The load phase (`constant-vus` with `VUS` for `DURATION`) picks two different random accounts per
   iteration and posts a transfer of `TRANSFER_AMOUNT` with a fresh `Idempotency-Key` built from
   `__VU`, `__ITER` and a random suffix. Only `201` is accepted.
3. `teardown()` reads every account and prints the sum of the balances next to
   `ACCOUNTS * INITIAL_BALANCE`. The transfers only move money between these accounts, so the two
   must be equal: it prints `conservation check PASS` or `FAIL` and never throws, so the summary is
   still shown.

Thresholds are guardrails only — `http_req_failed` under 1% and `http_req_duration` p(95) under
500 ms — so an obviously broken run exits non-zero. They are not targets.

## Reading the summary

Two lines in the k6 output are the result:

- `http_reqs` — the `rate` column is requests per second (throughput) for the run.
- `http_req_duration` — the `p(95)` column is the 95th percentile response time.

The custom `transfer_latency` trend reports the transfer request alone, and `unexpected_statuses`
counts responses that were not `201` (should be 0; a `200` would mean an idempotency key was
reused).

## Running it fairly — record all of this

A number is only meaningful next to the conditions it was produced under:

- **Machine**: CPU model and core count, and RAM (e.g. from `Get-CimInstance Win32_Processor`).
- **Docker Desktop memory limit**, if you did not leave it at the default: Settings → Resources.
- **`DB_POOL_SIZE`** the app ran with, since it caps concurrent database work.
- **`VUS`** and **`DURATION`** exactly as passed to k6.
- The **k6 version** and **Java version** of the image, if either differs from the defaults.
- Whether the API and the database ran in the same Docker engine as k6 or on another host.

Run the test **3 times** with identical settings and report the **median** of the three runs, not
the best one. First runs after a rebuild are usually the slowest because the JVM is cold, which is
exactly why one run is not a result.

These numbers depend on the hardware and on the database pool size. They are **not comparable
across machines**, and a result from a laptop says nothing about a server.

## Results

Only measured runs go here. Fill in your own numbers; leave cells blank until you have them.

| Run | Date | CPU / RAM | Docker memory limit | DB_POOL_SIZE | VUS | DURATION | http_reqs rate (req/s) | http_req_duration p(95) (ms) | transfer_latency p(95) (ms) | unexpected_statuses | Conservation |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | | | | | | | | | | | |
| 2 | | | | | | | | | | | |
| 3 | | | | | | | | | | | |
| Median | | | | | | | | | | | |
