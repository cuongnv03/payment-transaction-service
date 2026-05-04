# API Demo - Live Response Capture

Captured from a running local stack (`docker compose up --build -d`) on 2026-05-04.
Backend: Spring Boot 3.2 / Java 17 · PostgreSQL · Redis · Kafka · Resilience4j.

Two seed accounts: **alice** (ADMIN role, balance ~$1,000,000,000) and **bob**.

---

## 1. Authentication - `POST /api/auth/login`

```
POST /api/auth/login
Content-Type: application/json

{"username":"alice","password":"password123"}
```

```json
HTTP 200
{
  "token": "eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJmOGU1YWY0...",
  "userId": "f8e5af44-c3f9-4acf-b39f-7aee043e43fb",
  "username": "alice",
  "role": "ADMIN"
}
```

Stateless HS512 JWT. The subject claim is the internal user UUID, not the username - stable even if username changes.

---

## 2. Account Info - `GET /api/accounts/me`

```
GET /api/accounts/me
Authorization: Bearer <token>
```

```json
HTTP 200
{
  "id": "1adeab80-e5cc-46cc-b298-e31d4470caec",
  "userId": "f8e5af44-c3f9-4acf-b39f-7aee043e43fb",
  "balance": 999999917.0000,
  "currency": "USD",
  "createdAt": "2026-05-03T09:11:56.996992Z"
}
```

---

## 3. Create Transaction - `POST /api/transactions` → `201 PENDING`

Two-phase design: the POST commits `PENDING` + publishes to Kafka and returns immediately.
The gateway call happens asynchronously in the consumer - no DB connection held open during the gateway round-trip.

```
POST /api/transactions
Authorization: Bearer <alice-token>
Content-Type: application/json
Idempotency-Key: demo-idem-1777861563-001

{"toAccountId":"244969ef-cc76-4b76-863b-41b390a259f6","amount":250.00,"description":"demo: happy path"}
```

```json
HTTP 201
{
  "id": "57424aa8-4d29-4fae-a435-6d9a556a1c30",
  "fromAccountId": "1adeab80-e5cc-46cc-b298-e31d4470caec",
  "toAccountId": "244969ef-cc76-4b76-863b-41b390a259f6",
  "amount": 250.00,
  "currency": "USD",
  "status": "PENDING",
  "description": "demo: happy path",
  "gatewayReference": null,
  "failureReason": null,
  "retryCount": 0,
  "processedAt": null,
  "refundedAt": null,
  "createdAt": "2026-05-04T02:26:04.070320007Z",
  "updatedAt": "2026-05-04T02:26:04.070320007Z"
}
```

---

## 4. Idempotency - Duplicate Request Returns Cached Response

**Same `Idempotency-Key`, sent again.** Balance is not debited a second time.
The server returns the current state of the existing transaction (already `SUCCESS` after async processing).

```
POST /api/transactions
Idempotency-Key: demo-idem-1777861563-001   ← same key
```

```json
HTTP 201
{
  "id": "57424aa8-4d29-4fae-a435-6d9a556a1c30",   ← same ID
  "status": "SUCCESS",
  "gatewayReference": "GW-1EA4403952",
  "processedAt": "2026-05-04T02:26:05.133293Z",
  ...
}
```

The idempotency key is checked server-side before any side-effects.
The frontend generates the key once per modal open in a lazy `useState` initializer - double-clicks and network blips deduplicate; closing and reopening starts a fresh intent.

---

## 5. State Machine - `PENDING → SUCCESS`

```
GET /api/transactions/57424aa8-4d29-4fae-a435-6d9a556a1c30
Authorization: Bearer <alice-token>
```

Initial POST returned `PENDING`. After the Kafka consumer acquired the distributed lock,
called the mock gateway (100–500 ms simulated latency), and committed phase 2:

```json
HTTP 200
{
  "id": "57424aa8-4d29-4fae-a435-6d9a556a1c30",
  "status": "SUCCESS",
  "gatewayReference": "GW-1EA4403952",
  "processedAt": "2026-05-04T02:26:05.133293Z",
  "createdAt": "2026-05-04T02:26:04.070320Z",
  "updatedAt": "2026-05-04T02:26:05.146334Z"
}
```

State machine transitions - `startProcessing()`, `complete()`, `fail()`, `timeout()`, `refund()` - are domain methods that throw `IllegalTransactionStateException` on an illegal source state. No string comparisons scattered across services.

---

## 6. Refund - `POST /api/transactions/{id}/refund`

Only valid from `SUCCESS`. Attempting to refund a `FAILED` or `PENDING` transaction throws `IllegalTransactionStateException`.

```
POST /api/transactions/57424aa8-4d29-4fae-a435-6d9a556a1c30/refund
Authorization: Bearer <alice-token>
```

```json
HTTP 200
{
  "id": "57424aa8-4d29-4fae-a435-6d9a556a1c30",
  "status": "REFUNDED",
  "refundedAt": "2026-05-04T02:27:00.405849677Z",
  "processedAt": "2026-05-04T02:26:05.133293Z",
  "gatewayReference": "GW-1EA4403952",
  ...
}
```

---

## 7. Paginated Transaction List - `GET /api/transactions`

```
GET /api/transactions?page=0&size=3
Authorization: Bearer <alice-token>
```

```json
HTTP 200
{
  "data": [
    {
      "id": "401f8fa3-6767-4edb-89e9-5e05a5e521d8",
      "amount": 1.0000,
      "currency": "USD",
      "status": "PENDING",
      "description": "rate limit test 7",
      "createdAt": "2026-05-04T02:27:19.037498Z"
    },
    ...
  ],
  "page": 0,
  "size": 3,
  "totalElements": 115,
  "totalPages": 39
}
```

---

## 8. Error: Insufficient Funds - `422`

```
POST /api/transactions
Idempotency-Key: demo-insufficient-001

{"toAccountId":"...","amount":9999999999.00}
```

```json
HTTP 422
{
  "code": "INSUFFICIENT_FUNDS",
  "message": "Insufficient funds for user f8e5af44-c3f9-4acf-b39f-7aee043e43fb: balance=999999667.0000, requested=9999999999.00",
  "timestamp": "2026-05-04T02:27:00.087539037Z"
}
```

---

## 9. Error: Missing Idempotency-Key Header - `400`

```
POST /api/transactions
(no Idempotency-Key header)
```

```json
HTTP 400
{
  "code": "MISSING_HEADER",
  "message": "Required request header 'Idempotency-Key' for method parameter type String is not present",
  "timestamp": "2026-05-04T02:27:00.212956238Z"
}
```

---

## 10. Error: Validation - `400`

```
POST /api/transactions
Idempotency-Key: demo-validation-001

{"toAccountId":"...","amount":-5.00}
```

```json
HTTP 400
{
  "code": "VALIDATION_ERROR",
  "message": "amount must be positive",
  "timestamp": "2026-05-04T02:27:52.787361835Z"
}
```

---

## 11. Error: Invalid Token - `401`

```
GET /api/accounts/me
Authorization: Bearer invalid.token.here
```

```json
HTTP 401
{
  "code": "UNAUTHORIZED",
  "message": "Authentication required"
}
```

---

## 12. Error: Not Found - `404`

```
GET /api/transactions/00000000-0000-0000-0000-000000000000
```

```json
HTTP 404
{
  "code": "NOT_FOUND",
  "message": "Transaction not found: 00000000-0000-0000-0000-000000000000",
  "timestamp": "2026-05-04T02:27:52.219685988Z"
}
```

---

## 13. Rate Limiting - `429`

10 requests/min per user. 100 VUs sharing one account will see:

```
Request 1  → HTTP 201
Request 2  → HTTP 201
...
Request 7  → HTTP 201
Request 8  → HTTP 429   ← rate limit window exhausted
Request 9  → HTTP 429
...
```

```json
HTTP 429
{
  "code": "RATE_LIMIT_EXCEEDED",
  "message": "Too many requests. You may send at most 10 transactions per 60 seconds.",
  "timestamp": "2026-05-04T02:27:20.032469655Z"
}
```

Redis sliding-window counter via Redisson. The key is `rate_limit:{userId}` so limits are per user, not per IP.

---

## 14. Admin - Circuit Breaker State - `GET /api/admin/circuit-breaker`

```
GET /api/admin/circuit-breaker
Authorization: Bearer <admin-token>
```

```json
HTTP 200
{
  "name": "payment-gateway",
  "state": "OPEN",
  "failureRate": 50.0,
  "slowCallRate": 0.0,
  "bufferedCalls": 6,
  "failedCalls": 3
}
```

The mock gateway has a 10% timeout rate + 10% failure rate. After 6 calls in the sliding window, 3 failures = 50% failure rate → breaker opened.

**Config:** `CircuitBreaker { Retry { charge() } }` - the retry decorator is _inside_ the breaker. When the breaker is OPEN, `@Retry` does not fire - no burning retry budget on a known-bad downstream.

The breaker waits 30 s, then allows 3 probe calls (`HALF_OPEN`) before deciding to close or stay open.

---

## 15. Admin - Dead Letter Queue - `GET /api/admin/dlq`

```
GET /api/admin/dlq?page=0&size=5
Authorization: Bearer <admin-token>
```

```json
HTTP 200
{
  "data": [],
  "page": 0,
  "size": 5,
  "totalElements": 0,
  "totalPages": 0
}
```

Empty - no messages have been poison-pilled.

The `PersistingDlqRecoverer` writes failed events to `dead_letter_events` (Postgres) first, then forwards to the Kafka DLQ topic. If Kafka itself is down, the DB record still exists and admins can retry via `POST /api/admin/dlq/{id}/retry` - which re-publishes through the normal `EventPublisher`, not a second `KafkaTemplate`.

---

## 16. Health Check - `GET /actuator/health`

```
GET /actuator/health
```

```json
HTTP 200
{"status":"UP"}
```

---

## 17. Prometheus Metrics - `GET /actuator/prometheus`

Custom business metrics (not just Spring defaults):

```
# HELP transactions_created_total Total transactions created via the API
# TYPE transactions_created_total counter

# HELP transactions_processed_total Total transactions processed, tagged by terminal status
# TYPE transactions_processed_total counter
transactions_processed_total{status="SUCCESS"} 3.0
transactions_processed_total{status="FAILED"}  5.0

# HELP transactions_processing_duration_seconds End-to-end processing duration: PENDING → terminal status
# TYPE transactions_processing_duration_seconds histogram
transactions_processing_duration_seconds{quantile="0.5"}  0.142
transactions_processing_duration_seconds{quantile="0.95"} 2.012
transactions_processing_duration_seconds{quantile="0.99"} 2.012
```

HTTP request breakdown by status code (from Spring's auto-instrumentation):

```
http_server_requests_seconds_count{method="POST",status="201",uri="/api/transactions"}  9.0
http_server_requests_seconds_count{method="POST",status="422",uri="/api/transactions"}  1.0
http_server_requests_seconds_count{method="POST",status="429",uri="/api/transactions"}  5.0
http_server_requests_seconds_count{method="POST",status="400",uri="/api/transactions"}  1.0
http_server_requests_seconds_count{method="GET", status="200",uri="/api/transactions"}  1.0
http_server_requests_seconds_count{method="POST",status="200",uri="/api/transactions/{transactionId}/refund"} 1.0
```

Resilience4j circuit breaker:

```
resilience4j_circuitbreaker_calls_seconds_count{kind="successful",name="payment-gateway"} 3.0
resilience4j_circuitbreaker_calls_seconds_count{kind="failed",    name="payment-gateway"} 3.0
```

`TransactionMetricsPort` lives in `application/` as an outbound port.
The Micrometer adapter in `infrastructure/` implements it.
The application layer never imports Micrometer - tests inject a recording fake.

---

## 18. k6 Load Test Results

100 VUs × 5 minutes (30 s ramp · 4 m steady · 30 s ramp-down). 90 % reads / 10 % writes.

```
█ THRESHOLDS

  checks              ✓ rate=100%
  http_req_duration   ✓ p(95)<500   p(95)=58ms
                      ✓ p(99)<2000  p(99)=112ms
  http_req_failed     ✓ rate<0.01   rate≈0%

  {endpoint:create_transaction}  ✓ p(99)<2000  p(99)=100ms
  {endpoint:list_transactions}   ✓ p(99)<2000  p(99)=113ms

█ TOTAL RESULTS

  http_reqs       223,828   745/s
  iterations      223,823   745/s

  CUSTOM
  rate_limit_responses.......: ~22,000   (rate limiter working - not an error)
  insufficient_funds_responses: 0
```

P99 for `POST /api/transactions` is ~100 ms because the endpoint only commits `PENDING` + publishes to Kafka - the gateway call is asynchronous.

The rate limiter correctly rejected ~22,000 of ~22,500 write attempts because 100 VUs share one sender account (10 writes/min limit). This is expected behavior, not an infrastructure error - tracked in a dedicated `rate_limit_responses` counter rather than failing thresholds.
