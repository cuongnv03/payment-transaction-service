// =============================================================================
//  Transaction service — k6 load test
// -----------------------------------------------------------------------------
//  Profile: 100 virtual users over 5 minutes (30 s ramp, 4 m steady, 30 s ramp-down).
//  Mix:     ~90 % GET /api/transactions, ~10 % POST /api/transactions.
//  SLA:     http_req_duration p(99) < 2000 ms  (failed threshold = non-zero exit)
//
//  ── Prerequisites ───────────────────────────────────────────────────────────
//   1. Backend stack running locally:
//        docker compose up -d
//        cd backend && ./gradlew.bat bootRun --args='--spring.profiles.active=local'
//   2. The sender account must hold enough balance for write traffic. There is no
//      deposit endpoint in this app — seed once via SQL after the test user is
//      registered (the script registers the sender in setup() if absent):
//
//        psql -h localhost -p 5434 -U payment_user -d payment_db -c \
//          "UPDATE accounts SET balance = 1000000000 \
//             WHERE user_id = (SELECT id FROM users WHERE username='loadtest-sender');"
//
//      Without seeding, all POSTs return 422 INSUFFICIENT_FUNDS — the test still
//      runs, just measures the rejection path's latency instead of the happy path.
//      422 is not counted as an error (see expectedNonErrorStatus()).
//
//  ── Running ─────────────────────────────────────────────────────────────────
//      k6 run load-test/transaction-load-test.js
//      k6 run -e BASE_URL=http://staging.internal:8080 load-test/transaction-load-test.js
//      k6 run -e VUS=200 -e DURATION_STEADY=10m load-test/transaction-load-test.js
//
//  ── Output ──────────────────────────────────────────────────────────────────
//   k6 prints a summary at the end with TPS, latency percentiles, and threshold
//   pass/fail. Process exit code is non-zero on any threshold breach so CI fails
//   the build automatically.
// =============================================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

// ── Configuration ──────────────────────────────────────────────────────────────
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SENDER_USERNAME = __ENV.SENDER_USERNAME || 'alice';
const SENDER_EMAIL = __ENV.SENDER_EMAIL || 'alice@x.com';
const SENDER_PASSWORD = __ENV.SENDER_PASSWORD || 'password123';
const RECEIVER_USERNAME = __ENV.RECEIVER_USERNAME || 'bob';
const RECEIVER_EMAIL = __ENV.RECEIVER_EMAIL || 'bob@x.com';
const RECEIVER_PASSWORD = __ENV.RECEIVER_PASSWORD || 'password123';

const STEADY_VUS = parseInt(__ENV.VUS || '100', 10);
const RAMP_DURATION = __ENV.DURATION_RAMP || '30s';
const STEADY_DURATION = __ENV.DURATION_STEADY || '4m';
const RAMPDOWN_DURATION = __ENV.DURATION_RAMPDOWN || '30s';

// Probability that a single VU iteration submits a write. The rest are reads.
const WRITE_RATIO = parseFloat(__ENV.WRITE_RATIO || '0.1');

// ── Custom metrics ─────────────────────────────────────────────────────────────
const insufficientFunds = new Counter('insufficient_funds_responses');
const rateLimitHits = new Counter('rate_limit_responses');
const writeAttemptRate = new Rate('writes_per_iteration');

// ── Test options & thresholds ──────────────────────────────────────────────────
export const options = {
  // Don't auto-fail on >0 errors during ramp; the thresholds at the bottom decide.
  noConnectionReuse: false,
  insecureSkipTLSVerify: true,

  scenarios: {
    transaction_traffic: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP_DURATION,     target: STEADY_VUS },
        { duration: STEADY_DURATION,   target: STEADY_VUS },
        { duration: RAMPDOWN_DURATION, target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },

  // Thresholds gate the test. A breach exits k6 non-zero so CI fails the build.
  thresholds: {
    // Spec SLA: P99 < 2 s under 100 concurrent users. Also keeping P95 in check
    // so the high-percentile threshold can't be met by a few outliers being slow.
    'http_req_duration': ['p(95)<500', 'p(99)<2000'],

    // Error budget: <1 % of requests counted as failures. 422 (insufficient funds)
    // and 429 (rate limited) are excluded via responseCallback — only 5xx / network
    // failures count as errors here.
    'http_req_failed': ['rate<0.01'],

    // Per-endpoint visibility — also useful for diagnosing which path is slow.
    'http_req_duration{endpoint:list_transactions}': ['p(99)<2000'],
    'http_req_duration{endpoint:create_transaction}': ['p(99)<2000'],

    // Functional checks (status assertions) must mostly pass.
    'checks': ['rate>0.99'],
  },
};

// ── setup(): register sender + receiver once per test run ──────────────────────
export function setup() {
  const senderToken = registerOrLogin(SENDER_USERNAME, SENDER_EMAIL, SENDER_PASSWORD);
  const receiverToken = registerOrLogin(RECEIVER_USERNAME, RECEIVER_EMAIL, RECEIVER_PASSWORD);

  // The receiver's account-id is needed in every write request body.
  const receiverAccountId = getAccountId(receiverToken);

  console.log(`[setup] sender=${SENDER_USERNAME} receiver=${RECEIVER_USERNAME} (account ${receiverAccountId})`);
  console.log('[setup] If POSTs all return 422, seed the sender balance via SQL — see script header.');

  return { senderToken, receiverAccountId };
}

// ── default(): each VU runs this in a loop ─────────────────────────────────────
export default function (data) {
  const { senderToken, receiverAccountId } = data;
  const isWrite = Math.random() < WRITE_RATIO;
  writeAttemptRate.add(isWrite ? 1 : 0);

  if (isWrite) {
    createTransaction(senderToken, receiverAccountId);
  } else {
    listTransactions(senderToken);
  }

  // Small think-time so 100 VUs aren't pegged at 100% of the loop's CPU cost.
  // Adjust via __ENV.SLEEP_MS if you want a different request density.
  sleep(parseFloat(__ENV.SLEEP_SECONDS || '0.1'));
}

// ── HTTP operations ────────────────────────────────────────────────────────────

function listTransactions(token) {
  const res = http.get(
    `${BASE_URL}/api/transactions?page=0&size=20`,
    {
      headers: { Authorization: `Bearer ${token}` },
      tags: { endpoint: 'list_transactions' },
    },
  );
  check(res, {
    'list status is 200': (r) => r.status === 200,
  });
}

function createTransaction(token, receiverAccountId) {
  const idempotencyKey = uuidv4();
  const body = JSON.stringify({
    toAccountId: receiverAccountId,
    amount: 1.00,
    description: 'load-test transfer',
  });

  const res = http.post(`${BASE_URL}/api/transactions`, body, {
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
    // responseCallback marks which status codes are "expected" — k6 excludes
    // them from http_req_failed. 429 (rate limited) is a valid business outcome
    // when 100 VUs share one sender account; it should not count as an error.
    responseCallback: http.expectedStatuses(201, 422, 429),
    tags: { endpoint: 'create_transaction' },
  });

  // Expected statuses: 201 (created), 422 (insufficient funds), 429 (rate limited).
  // Anything else (5xx, network failure) is an unexpected error.
  const ok = check(res, {
    'create status is 201, 422, or 429': (r) => r.status === 201 || r.status === 422 || r.status === 429,
  });

  if (res.status === 422) {
    insufficientFunds.add(1);
  } else if (res.status === 429) {
    rateLimitHits.add(1);
  } else if (!ok) {
    console.warn(`[create] unexpected status=${res.status} body=${res.body}`);
  }
}

// ── Auth helpers ───────────────────────────────────────────────────────────────

/**
 * Registers a user, or logs in if the username already exists (409). Returns the
 * JWT. Idempotent across test runs.
 */
function registerOrLogin(username, email, password) {
  const registerRes = http.post(
    `${BASE_URL}/api/auth/register`,
    JSON.stringify({ username, email, password }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { endpoint: 'auth_register' },
    },
  );

  if (registerRes.status === 201) {
    return registerRes.json('token');
  }
  if (registerRes.status === 409) {
    // Already registered from a previous run — log in instead.
    const loginRes = http.post(
      `${BASE_URL}/api/auth/login`,
      JSON.stringify({ username, password }),
      {
        headers: { 'Content-Type': 'application/json' },
        tags: { endpoint: 'auth_login' },
      },
    );
    if (loginRes.status !== 200) {
      throw new Error(`login failed for ${username}: status=${loginRes.status} body=${loginRes.body}`);
    }
    return loginRes.json('token');
  }
  throw new Error(`register failed for ${username}: status=${registerRes.status} body=${registerRes.body}`);
}

function getAccountId(token) {
  const res = http.get(`${BASE_URL}/api/accounts/me`, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { endpoint: 'account_me' },
  });
  if (res.status !== 200) {
    throw new Error(`account/me failed: status=${res.status} body=${res.body}`);
  }
  return res.json('id');
}

/**
 * RFC 4122 v4 UUID. k6 0.40+ has crypto.randomUUID(), but we polyfill so the
 * script works on slightly older runners and so it's obvious how the value is
 * generated.
 */
function uuidv4() {
  // 16 random bytes
  const bytes = new Uint8Array(16);
  for (let i = 0; i < 16; i++) bytes[i] = Math.floor(Math.random() * 256);
  // Set version (4) and variant (10xx) bits per RFC 4122.
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return (
    hex.slice(0, 8) + '-' +
    hex.slice(8, 12) + '-' +
    hex.slice(12, 16) + '-' +
    hex.slice(16, 20) + '-' +
    hex.slice(20, 32)
  );
}
