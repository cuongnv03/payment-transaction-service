# Payment Transaction Service

A production-grade P2P payment service built end-to-end as a portfolio
demonstration: Java 17 / Spring Boot 3 backend with hexagonal architecture,
React + TypeScript frontend, and a Postgres + Redis + Kafka stack - all
runnable on a developer machine with one command, with Prometheus metrics,
Grafana dashboards, k6 load testing, and GitHub Actions CI.

> Not a real payment network - the gateway is a configurable mock. The point
> is the platform around it: idempotency, distributed locking, circuit
> breaking, dead-letter queues, observable metrics, and load-tested SLAs.

---

## Demo

| Surface | URL |
|---|---|
| Frontend (deployed) | _to be filled in after deployment to Vercel_ |
| Backend (deployed) | _to be filled in after deployment to Railway / Render_ |
| Local frontend | http://localhost:3000 |
| Local backend health | http://localhost:8080/actuator/health |
| Local Grafana | http://localhost:3001 (admin / admin) |
| Local Prometheus | http://localhost:9090 |

Screenshots will land in `docs/screenshots/` after the deployment step.

---

## Local development - three commands

```bash
git clone https://github.com/cuongnv03/payment-transaction-service.git
cd payment-transaction-service
cp .env.example .env && docker compose up --build
```

Wait ~60–90 s for the first build. When everything is healthy:

- Open http://localhost:3000 → register a user, log in, you're on the dashboard.
- (Optional) seed the test sender's balance for write traffic - see the
  comment in [`load-test/transaction-load-test.js`](load-test/transaction-load-test.js).
- Open http://localhost:3001 → the "Payment Service" Grafana dashboard
  loads automatically with throughput, error-rate, P95/P99 latency, and
  circuit-breaker state panels.

To stop the stack and remove its data:

```bash
docker compose down -v
```

---

## Architecture

```
            ┌─────────────────────────┐
            │   Browser (React SPA)   │
            └──────────┬──────────────┘
                       │  HTTPS  (in dev: http://localhost:3000)
                       ▼
            ┌─────────────────────────┐
            │  nginx (frontend image) │
            │  • serves /dist         │
            │  • /api  → backend:8080 │
            │  • /actuator → backend  │
            └──────────┬──────────────┘
                       │
                       ▼
            ┌──────────────────────────────────────────┐
            │     Spring Boot 3 backend (Java 17)      │
            │                                          │
            │  presentation/  (HTTP, JWT, MDC, rate)   │
            │      │                                   │
            │  application/   (use cases, ports)       │
            │      │                                   │
            │  domain/        (pure Java, no Spring)   │
            │      │                                   │
            │  infrastructure/  ◀── adapters for ──┐   │
            └────────────────────┬─────────────────│───┘
                                 │                 │
                  ┌──────────────┼─────────────────┘
                  │              │              │
                  ▼              ▼              ▼
            ┌─────────┐    ┌──────────┐    ┌──────────┐
            │Postgres │    │  Redis   │    │  Kafka   │
            │ (JPA)   │    │  rate    │    │ events + │
            │ Flyway  │    │  lock    │    │ DLQ      │
            │ migr.   │    │  cache   │    │ topics   │
            └─────────┘    └──────────┘    └──────────┘

            ┌─────────────────────────┐
            │ Prometheus (scrape /15s)│ ──▶ Grafana panels
            └─────────────────────────┘
```

### Layers (hexagonal / ports & adapters)

- **`domain/`** - pure Java aggregates, value objects, exceptions. No
  framework imports. The `Transaction` aggregate enforces the state
  machine; the `Account` aggregate guards balance invariants.
- **`application/`** - use case interfaces (`port/in`), outbound
  abstractions (`port/out`), and services that orchestrate them. All
  outbound concerns (DB, Kafka, Redis lock, metrics, cache) are ports.
- **`infrastructure/`** - JPA repositories, Kafka producers/consumers,
  Resilience4j-decorated payment gateway, Redisson lock + cache,
  Micrometer adapter. Implements the ports.
- **`presentation/`** - REST controllers, request/response records,
  global exception handler, JWT + MDC + rate-limit filters.

---

## Tech stack

| Layer | Choice | Why |
|---|---|---|
| Language (BE) | Java 17 (LTS) | Type system, mature ecosystem, JIT performance |
| Framework | Spring Boot 3.2 | Curated defaults for security, data, kafka, web, actuator |
| Persistence | PostgreSQL 15 + JPA/Hibernate | ACID, partial indexes, JSONB for audit metadata |
| Migrations | Flyway 9 | Versioned, checksummed, runs on app startup |
| Cache + lock | Redis 7 + Redisson 3.27 | Sliding-window rate limiter, distributed lock, cache |
| Messaging | Apache Kafka 3.6 | Durable async events, consumer groups, DLQ topic |
| Resilience | Resilience4j 2.2 | Circuit breaker + retry; fast-fail when gateway degrades |
| Auth | JJWT 0.12 (HS256) | Stateless JWT - horizontal scaling without shared sessions |
| Metrics | Micrometer + Prometheus + Grafana | Auto-instrumented HTTP/JVM/DB + custom business meters |
| Logging | SLF4J + Logback + LogstashEncoder | JSON in prod (whitelisted MDC keys), human-readable in `local` |
| Tests (BE) | JUnit 5 + Testcontainers + Awaitility + AssertJ | Real Postgres/Kafka/Redis, no mocks for infra |
| Frontend | React 18 + TypeScript 5.6 + Vite 5 | Modern dev loop, strict types, fast HMR |
| FE state | Zustand + persist | Tiny store, localStorage hydration |
| FE charts | Recharts | Declarative, code-split into the admin bundle |
| Tests (FE) | Vitest 2 + React Testing Library + jsdom | Co-located, fast, idiomatic |
| Load test | k6 | Threshold-gated, CI-friendly exit code |
| Containers | Docker + Compose | Single `docker compose up --build` brings the stack up |
| CI | GitHub Actions | Two parallel jobs, JaCoCo upload, npm cache |

---

## Key design decisions

The four most consequential ones; the full list lives in
[`docs/DECISIONS.md`](docs/DECISIONS.md).

### Transaction state machine

`PENDING → PROCESSING → (SUCCESS | FAILED | TIMEOUT) → REFUNDED`. Each
transition is a domain method on the `Transaction` aggregate
(`startProcessing()`, `complete()`, `fail()`, `timeout()`, `refund()`)
that throws `InvalidTransactionStateException` if the current state
isn't a valid source. No string-based status checks scattered across
services - illegal transitions can't compile away because the methods
*are* the contract.

### Idempotency

Every write request must carry an `Idempotency-Key` header. The
application service queries `transactionRepository.findByIdempotencyKey(...)`
first and returns the cached result on replay. The frontend generates the
key once per "logical user intent" (per modal opening, not per submit
click) using `crypto.randomUUID()` in a lazy `useState` initialiser, so
double-clicks and network-blip retries dedupe correctly.

### Circuit breaker around the payment gateway

Resilience4j wraps the gateway call: 50 % failure rate over a 10-call
sliding window opens the breaker; while open, calls fast-fail with
`CallNotPermittedException`. `@Retry` is decorated *inside* the breaker,
so a degraded gateway doesn't burn retry budget when the breaker is
already open. State is exposed at `GET /api/admin/circuit-breaker` and as a
Prometheus gauge - operators see degradation before users complain.

### Dead-letter queue

When a Kafka consumer fails after retries, a custom `PersistingDlqRecoverer`
writes the failed record to the `dead_letter_events` Postgres table
*before* attempting to forward it to the DLQ Kafka topic. Kafka publish
failures are caught and logged but never propagated - Spring Kafka treats
recoverer exceptions as "recovery failed" and would otherwise loop the
record forever. Admins inspect the DLQ at `/api/admin/dlq` and retry via
`POST /api/admin/dlq/{id}/retry`, which loads the original transaction
and re-publishes via the regular `EventPublisher` path.
See [ADR-0001](docs/adr/0001-dlq-db-first-recovery.md).

---

## Project layout

```
.
├── backend/              Spring Boot service
│   ├── src/main/java/dev/cuong/payment/
│   │   ├── domain/           pure Java aggregates + value objects
│   │   ├── application/      use cases + ports
│   │   ├── infrastructure/   adapters (JPA, Kafka, Redis, metrics, cache)
│   │   └── presentation/     controllers, DTOs, filters, exception handler
│   ├── src/main/resources/
│   │   ├── application.yml             default profile
│   │   ├── application-local.yml       local-dev overrides
│   │   ├── logback-spring.xml          JSON for prod, human-readable for local
│   │   └── db/migration/               Flyway scripts (V1–V4)
│   └── src/test/java/        unit + integration + E2E (Testcontainers)
├── frontend/             React + TypeScript SPA
│   ├── src/
│   │   ├── api/              typed clients per backend surface
│   │   ├── store/            Zustand stores (auth, transactions, account)
│   │   ├── components/       presentational components
│   │   ├── pages/            routed pages (Login, Register, Dashboard, AdminPanel)
│   │   ├── test/             Vitest + Testing Library specs
│   │   └── styles.css        single global stylesheet
│   ├── Dockerfile            multi-stage node:20 → nginx:1.27
│   └── nginx.conf            SPA fallback + /api reverse-proxy
├── monitoring/
│   ├── prometheus.yml        scrape config (15 s)
│   └── grafana/provisioning/
│       ├── datasources/prometheus.yml      Prometheus datasource
│       └── dashboards/
│           ├── dashboard.yml               provider config
│           └── payment-service.json        five-panel dashboard
├── load-test/
│   └── transaction-load-test.js   k6 - 100 VUs × 5 min, P99 < 2 s gate
├── docs/
│   ├── DECISIONS.md                 compiled architectural decisions
│   ├── PERFORMANCE.md               before/after performance log
│   ├── IMPLEMENTATION_NOTES.md      deviations from spec, with rationale
│   ├── TASK_BREAKDOWN.md            28-task implementation plan + status table
│   └── adr/                         multi-page architecture decision records
├── .github/workflows/ci.yml    backend test + frontend build (parallel jobs)
├── docker-compose.yml          full stack (Postgres + Redis + Kafka + Prom + Grafana + backend + frontend)
└── .env.example                copy to .env before docker compose up
```

---

## Testing

| Suite | How to run | What it covers |
|---|---|---|
| Backend unit | `cd backend && ./gradlew test` | Domain logic, no Spring |
| Backend integration | `cd backend && ./gradlew test` (same task) | Real Postgres / Kafka / Redis via Testcontainers - see `*IntegrationTest.java` |
| Backend E2E | _included in test_ | Full stack: register → seed → transfer → SUCCESS → refund; concurrent overdraft prevention. See [`EndToEndIntegrationTest.java`](backend/src/test/java/dev/cuong/payment/e2e/EndToEndIntegrationTest.java) |
| Frontend | `cd frontend && npm test` | Vitest + React Testing Library; ~30 specs |
| Load test | `k6 run load-test/transaction-load-test.js` | 100 VUs × 5 min, threshold-gated P99 < 2 s |

CI runs the backend test suite + the frontend type-check + tests + build on
every push and PR to `main`. Coverage (JaCoCo HTML) is uploaded as an
artifact - green or red - so reviewers can read it without re-running.

---

## Performance

The k6 load profile (100 VUs over 5 minutes, 90 % read / 10 % write mix,
422 INSUFFICIENT_FUNDS tagged as expected) gates on:

- `http_req_duration p(99) < 2000 ms` (the spec SLA)
- `http_req_duration p(95) < 500 ms`
- `http_req_failed rate < 0.01` (1 % error budget; 422 excluded)

Threshold breaches cause `k6 run` to exit non-zero - CI-failable.

Recorded before/after deltas (cache, gzip, indexes) live in
[`docs/PERFORMANCE.md`](docs/PERFORMANCE.md). The post-run table is
filled in after running k6 against pre- and post-change builds.

---

## Documentation map

| Document | Purpose |
|---|---|
| [`README.md`](README.md) | This file - high-level overview and run instructions |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | Compiled architectural decisions (Context / Options / Decision / Consequences) |
| [`docs/IMPLEMENTATION_NOTES.md`](docs/IMPLEMENTATION_NOTES.md) | Deviations from the original technical spec, with rationale |
| [`docs/PERFORMANCE.md`](docs/PERFORMANCE.md) | Performance methodology + before/after measurements |
| [`docs/TASK_BREAKDOWN.md`](docs/TASK_BREAKDOWN.md) | The 28-task implementation plan and status table |
| [`docs/adr/`](docs/adr/) | Multi-page architecture decision records (ADRs 0001–0004) |

---

## License

This project exists as a portfolio piece. No license declared yet - feel
free to read, learn from, and reference; ask before reusing.
