// k6 load test for the transfer path.
//
// This script is a deliverable, not a result. Nothing in it claims a number: every figure is
// produced by whatever machine runs it, and the summary is the only place numbers appear.
//
//   k6 run k6/transfer.js
//   k6 run -e VUS=100 -e DURATION=2m -e ACCOUNTS=200 k6/transfer.js
//
// The stack it talks to is the one from docker-compose.yml:
//
//   docker compose up -d --build
//
// Set up: ACCOUNTS accounts in INR, each funded with INITIAL_BALANCE minor units, so the load
// phase starts from a known total. Then the load phase moves TRANSFER_AMOUNT between two random
// distinct accounts per iteration, each request with its own Idempotency-Key so the API performs
// every transfer instead of replaying one. Teardown re-reads the balances and checks that the
// transfers only moved money: the sum of the user balances must still be ACCOUNTS * INITIAL_BALANCE.

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// ---------------------------------------------------------------------------------------------
// Configuration, all overridable with -e NAME=value.
// ---------------------------------------------------------------------------------------------

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const ACCOUNTS = Number(__ENV.ACCOUNTS || 50);
const INITIAL_BALANCE = Number(__ENV.INITIAL_BALANCE || 1000000); // minor units (paise), INR
const TRANSFER_AMOUNT = Number(__ENV.TRANSFER_AMOUNT || 1); // minor units (paise), INR
const VUS = Number(__ENV.VUS || 50);
const DURATION = __ENV.DURATION || '60s';

const CURRENCY = 'INR';

/**
 * Latency of the transfer request itself, measured separately from the built-in http_req_duration
 * so the summary shows the endpoint this test is about rather than every request in the run
 * (setup and teardown calls are included in the built-in metric).
 */
const transferLatency = new Trend('transfer_latency', true);

/** Any transfer that does not answer 201. The API is idempotent, so a 200 here is a real bug. */
const unexpectedStatuses = new Counter('unexpected_statuses');

export const options = {
  scenarios: {
    transfers: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
    },
  },

  // Guardrails only: they exist so an obviously broken run (errors, or a latency blow-up) exits
  // non-zero instead of producing a summary that looks fine. They are not targets, and a passing
  // run says nothing about how the service would behave under a different machine or pool size.
  thresholds: {
    http_req_failed: ['rate<0.01'], // fewer than 1% failed requests
    http_req_duration: ['p(95)<500'], // 95% of requests under 500 ms
  },
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function post(path, body, idempotencyKey) {
  const headers = Object.assign({}, JSON_HEADERS);
  if (idempotencyKey) {
    headers['Idempotency-Key'] = idempotencyKey;
  }
  return http.post(`${BASE_URL}${path}`, JSON.stringify(body), { headers, tags: { name: path } });
}

/**
 * A key that is unique per virtual user, per iteration, plus a random suffix. The counter alone
 * would be enough for one run, but the suffix keeps two concurrent runs against the same database
 * from colliding. It stays far below the API's 255 character limit.
 */
function newIdempotencyKey(prefix) {
  return `${prefix}-${__VU}-${__ITER}-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

// ---------------------------------------------------------------------------------------------
// setup: create and fund the accounts, return their ids for the load phase and teardown.
// ---------------------------------------------------------------------------------------------

export function setup() {
  const accountIds = [];

  for (let i = 0; i < ACCOUNTS; i += 1) {
    // Unique owner names so the accounts are distinguishable in the database and in a failure
    // message; the API does not require uniqueness, the test does.
    const created = post('/api/v1/accounts', {
      ownerName: `k6-account-${i}-${Date.now()}`,
      currency: CURRENCY,
    });

    // setup fails loudly: a load run against half-created accounts would measure the wrong thing.
    if (created.status !== 201) {
      throw new Error(`setup: create account ${i} returned ${created.status}: ${created.body}`);
    }
    const accountId = created.json('id');
    accountIds.push(accountId);

    const deposited = post(
      '/api/v1/deposits',
      { accountId, amount: INITIAL_BALANCE, currency: CURRENCY },
      newIdempotencyKey('setup-deposit'),
    );
    if (deposited.status !== 201) {
      throw new Error(`setup: deposit into account ${accountId} returned ${deposited.status}: ${deposited.body}`);
    }
  }

  console.log(`setup: created and funded ${accountIds.length} ${CURRENCY} accounts`);
  return { accountIds };
}

// ---------------------------------------------------------------------------------------------
// Load phase: one transfer per iteration between two random, distinct accounts.
// ---------------------------------------------------------------------------------------------

export default function (data) {
  const accountIds = data.accountIds;

  // Two distinct accounts: a self-transfer is rejected by the API by design, and counting that
  // rejection as a 4xx would hide the real transfer path behind an expected error.
  const fromIndex = Math.floor(Math.random() * accountIds.length);
  let toIndex = Math.floor(Math.random() * accountIds.length);
  while (toIndex === fromIndex) {
    toIndex = Math.floor(Math.random() * accountIds.length);
  }

  const response = post(
    '/api/v1/transfers',
    {
      fromAccountId: accountIds[fromIndex],
      toAccountId: accountIds[toIndex],
      amount: TRANSFER_AMOUNT,
      currency: CURRENCY,
    },
    // A fresh key per request: a repeated key would be answered from the stored response and the
    // load phase would stop measuring money movement.
    newIdempotencyKey('k6-transfer'),
  );

  transferLatency.add(response.timings.duration);

  const accepted = check(response, {
    'transfer status is 201': (r) => r.status === 201,
  });
  if (!accepted) {
    unexpectedStatuses.add(1, { status: String(response.status) });
  }
}

// ---------------------------------------------------------------------------------------------
// teardown: conservation check. Transfers only move money between the accounts this run created,
// so the sum of their balances must equal what setup deposited. It prints PASS or FAIL and never
// throws: a failed check must still leave the k6 summary visible.
// ---------------------------------------------------------------------------------------------

export function teardown(data) {
  const accountIds = data.accountIds;

  let sum = 0;
  let unreadable = 0;
  for (const accountId of accountIds) {
    const response = http.get(`${BASE_URL}/api/v1/accounts/${accountId}`, {
      tags: { name: '/api/v1/accounts/{id}' },
    });
    if (response.status !== 200) {
      unreadable += 1;
      console.error(`teardown: GET account ${accountId} returned ${response.status}: ${response.body}`);
      continue;
    }
    sum += response.json('balance');
  }

  const expected = accountIds.length * INITIAL_BALANCE;
  const conserved = unreadable === 0 && sum === expected;

  console.log(`teardown: accounts=${accountIds.length} unreadable=${unreadable}`);
  console.log(`teardown: sum of user balances = ${sum} (minor units), expected = ${expected}`);
  console.log(`teardown: conservation check ${conserved ? 'PASS' : 'FAIL'}`);
}
