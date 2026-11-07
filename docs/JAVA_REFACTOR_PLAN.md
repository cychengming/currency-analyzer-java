# Currency Analyzer to `currency-analyzer-java` Migration Plan

**Status:** Proposed
**Created:** 2026-11-07
**Source project:** `currency-analyzer` (Flask/Python)
**Target project:** `currency-analyzer-java` (Java 25/Spring Boot)
**Implementation gate:** No application implementation starts until this plan is reviewed and approved.

---

## 1. Objective

Create a Java 25 and Spring Boot replacement for the current Currency Analyzer application while preserving the existing browser experience and HTTP API behavior.

The target application must continue to provide:

- User registration, login, logout, and protected APIs.
- Live FX and commodity quotes.
- Historical close and OHLC data.
- Seven configurable alert conditions and background monitoring.
- Email notifications, alert history, and per-pair preferences.
- Trade journal and daily risk controls.
- Backtesting and related risk statistics.
- Deep-learning forecast reads and job orchestration.
- Prometheus metrics.
- Kafka/Flink gold-price streaming compatibility.

The migration should be done in a new sibling repository rather than by renaming or replacing the current checkout in place. This keeps production rollback, data comparison, and side-by-side testing straightforward.

## 2. Scope and Non-Goals

### In scope

- Reimplement the Flask backend in Java 25 and Spring Boot.
- Preserve existing URLs and response fields until the frontend is deliberately changed.
- Move application persistence from SQLite to PostgreSQL.
- Preserve existing users and application data through a repeatable import.
- Port market-data adapters, alert detection, monitoring, email, trade journal, and backtesting.
- Port the gold Kafka producer to a Java worker or Spring Boot process.
- Replace benchmark monitoring with Spring Boot Actuator and Micrometer.
- Add automated contract, unit, integration, migration, and browser smoke tests.
- Build a parallel Docker Compose deployment and controlled cutover.

### Out of scope for the first cutover

- A visual redesign or migration from vanilla JavaScript/Chart.js to React.
- New financial indicators, trading functionality, or alert rules beyond current behavior.
- Rewriting the existing Flink SQL job.
- Reimplementing PyTorch model training in Java.
- Replacing the public quote providers.
- Multi-region high availability, account-level data isolation, and enterprise identity integration.

## 3. Current-State Baseline

### Application stack

| Area | Current implementation |
|---|---|
| Backend | Python 3.11, Flask 3, Flask-CORS |
| Persistence | SQLite for the main app; PostgreSQL for optional DL/analytics data |
| Frontend | Static HTML, vanilla JavaScript, Chart.js |
| Authentication | Flask session cookie; PBKDF2-HMAC-SHA256 with 100,000 iterations |
| Background work | In-process Python threads |
| Market data | Frankfurter, Yahoo Finance, and Stooq fallback |
| Notifications | SMTP with an optional Gmail fallback |
| Observability | `prometheus-client`, custom `/metrics` route |
| Streaming | Python Kafka producer, Kafka, Flink SQL, PostgreSQL sink |
| Deployment | Docker and Docker Compose |

### Current API surface

The Java application must initially preserve these route groups:

| Group | Endpoints |
|---|---|
| Pages | `GET /`, `/dashboard.html`, `/login.html` |
| Auth | `POST /api/auth/register`, `/api/auth/login`, `/api/auth/logout`; `GET /api/auth/status` |
| Market data | `GET /api/live-rates`; `GET /api/historical/{pair}/{days}`; `GET /api/historical-ohlc/{pair}/{days}` |
| Backtest | `POST /api/backtest` |
| Settings | `GET/POST /api/settings` |
| Trade journal | `GET/POST /api/trade/diary`; `PUT /api/trade/diary/{id}`; `POST /api/trade/diary/{id}/close`; `GET /api/trade/summary` |
| Alerts | `GET /api/alerts`; `GET/POST /api/alerts/preferences`; `GET /api/alerts/preferences/{pair}`; `GET /api/alerts/conditions`; `DELETE /api/alerts/clear` |
| Monitoring | `POST /api/monitoring/start`, `/api/monitoring/stop`; `GET /api/monitoring/status` |
| Email | `POST /api/test-email` |
| Forecasts | `GET /api/dl/runs`, `/api/dl/forecast/latest`, `/api/dl/forecast/{runId}`, `/api/dl/job/{jobId}` |
| DL jobs | `POST /api/dl/ingest`, `/api/dl/train`, `/api/dl/ingest-train` |
| Metrics | `GET /metrics` |

### Current data model

Main SQLite tables:

- `settings`
- `alerts`
- `monitoring_state`
- `alert_preferences`
- `users`
- `price_history`
- `trade_journal`

Optional PostgreSQL tables:

- `gold_daily`
- `usd_index_daily`
- `gdp_annual`
- `dl_forecast_runs`
- `gold_forecast_daily`
- `asset_forecast_daily`
- `gold_signal_daily`
- `alert_events`

### Baseline gaps

- Automated coverage is limited to one Kafka producer deduplication test.
- The application has no generated OpenAPI contract.
- The main persistence path is SQLite while analytics already uses PostgreSQL.
- Background monitoring runs as in-process threads and is not safe across multiple replicas.
- Authentication predates Spring Security and lacks CSRF protection at the application layer.
- The container health check calls a protected API instead of a purpose-built health endpoint.
- Timestamps are stored as text and do not consistently express timezone semantics.

### Tooling observed on the development machine

- Java is installed as JDK `25.0.4.1` LTS.
- Maven `3.9.1` is installed but currently runs with JDK 21.
- Gradle is not installed.
- Docker `29.1.2` is available.

Phase 0 must add a Maven Wrapper and configure its toolchain so every build uses Java 25 rather than the JDK currently selected by the machine-wide Maven installation.

## 4. Target Architecture

```text
Browser / Chart.js dashboard
            |
            | Existing HTTP API contract
            v
Spring Boot application (Java 25)
  - Spring MVC
  - Spring Security
  - Spring JDBC/JdbcClient
  - Flyway
  - Validation
  - Spring Mail
  - Scheduling
  - Resilience4j
  - Micrometer/Prometheus
  |
  +--> PostgreSQL (canonical application + forecast data)
  +--> Frankfurter / Yahoo / Stooq HTTP adapters
  +--> SMTP provider
  +--> Kafka topic `gold-prices`
  +--> Python forecast worker (optional ML execution only)

Kafka --> Flink SQL --> PostgreSQL --> Grafana / Java read APIs
```

### Architectural decisions

1. **New repository:** Create `currency-analyzer-java` beside the current repository and keep the Python application available for comparison and rollback.
2. **API compatibility:** Keep routes, JSON field names, HTTP methods, and session-cookie behavior stable during the migration.
3. **PostgreSQL:** Make one PostgreSQL database the canonical store for application, journal, history, forecast, alert history, and job records.
4. **Spring MVC:** Use Spring MVC and `RestClient`; do not add Spring WebFlux for this request/response application.
5. **Explicit SQL:** Use Spring's `JdbcClient` with explicit SQL and row mappers. This preserves existing query behavior better than introducing a new ORM model during a platform migration.
6. **Scheduler safety:** Replace free-running Python threads with Spring scheduling plus a PostgreSQL advisory lock or ShedLock so only one instance processes each monitoring cycle.
7. **Bounded Python sidecar:** Keep the PyTorch trainer in Python. Java owns API contracts, job state, orchestration, and result reads. This avoids a risky numerical-platform rewrite.
8. **Flink remains:** Keep the current Flink SQL pipeline. Java publishes the same Kafka event schema and reads the same PostgreSQL sink tables.
9. **Frontend continuity:** Reuse the current static frontend initially. Make only the small changes required for CSRF and API compatibility.

## 5. Target Stack

| Concern | Proposed technology |
|---|---|
| Language | Java 25 LTS |
| Framework | Current supported Spring Boot 4.x; pin an exact patch in Phase 0 |
| Build | Maven Wrapper 3.9.x with Java 25 toolchain |
| HTTP | Spring MVC, `RestClient`, HTTP interfaces |
| Security | Spring Security, session authentication, CSRF, password encoders |
| Persistence | Spring JDBC, `JdbcClient`, HikariCP |
| Schema | Flyway versioned migrations |
| Database | PostgreSQL 16 |
| Validation | Jakarta Bean Validation |
| Email | Spring Mail |
| Resilience | Resilience4j timeouts, retry, and circuit breakers |
| Scheduling | Spring `@Scheduled` plus distributed lock |
| Messaging | Spring Kafka |
| Metrics | Actuator, Micrometer Prometheus registry |
| Tests | JUnit 5, AssertJ, Spring Boot Test, Testcontainers, WireMock |
| Containers | Multi-stage JDK 25 build, JRE 25 runtime |
| CI | Maven verification, contract tests, CodeQL Java analysis, container smoke test |

No Lombok will be required. Java records, explicit mappers, and standard constructors keep the code compatible with current Java tooling and easier to inspect.

## 6. Proposed Source Layout

```text
currency-analyzer-java/
├── pom.xml
├── mvnw
├── docker-compose.yml
├── Dockerfile
├── docs/
│   ├── api-contract.md
│   ├── architecture.md
│   ├── migration.md
│   └── operations.md
├── src/main/java/com/currencyanalyzer/
│   ├── CurrencyAnalyzerApplication.java
│   ├── auth/
│   ├── config/
│   ├── marketdata/
│   ├── analysis/
│   ├── alerts/
│   ├── monitoring/
│   ├── email/
│   ├── journal/
│   ├── backtest/
│   ├── forecast/
│   ├── streaming/
│   └── web/
├── src/main/resources/
│   ├── application.yml
│   ├── db/migration/
│   ├── static/
│   └── email-templates/
└── src/test/
    ├── java/
    └── resources/contracts/
```

Package-by-feature is preferred over package-by-layer so each feature owns its API, service, repository, configuration, and tests.

## 7. Compatibility Contract

### HTTP behavior

- Preserve existing URLs and methods.
- Preserve JSON field names, including snake_case fields used by current JavaScript.
- Preserve current success/error response shapes and status codes unless a mismatch is documented and approved.
- Keep `credentials: "include"` working for the current frontend.
- Keep `/metrics` as a compatibility route even if Actuator is also exposed.
- Serve existing static assets under the same paths.

### Currency-pair routing

Pairs contain `/`, and clients currently send values such as `EUR%2FUSD`. Spring Boot must be explicitly tested for encoded slashes. The implementation may use a carefully configured connector and route matching, or a compatibility controller that decodes the pair from the request path.

Acceptance tests must cover at least:

- `EUR%2FUSD`
- `GOLD%2FUSD`
- Lowercase input
- Unknown pair
- Empty or malformed pair
- Invalid day counts

### Response compatibility

Before implementation of each API slice:

1. Capture representative Python responses from a deterministic seed database.
2. Store the request/response pair under `src/test/resources/contracts/`.
3. Implement the Java slice.
4. Run the captured contract and compare status, headers that affect client behavior, JSON structure, and numeric values.

Economic calculations should be compared with a relative error tolerance of `1e-9` where inputs and order of operations are identical. A documented tolerance may be used for algorithms where floating-point operation order necessarily changes.

## 8. Data Migration

### Canonical database

PostgreSQL becomes the canonical application data store. Reuse the existing PostgreSQL service in Docker Compose and retain current table names during the first Java release to avoid unnecessary coupling to Flink and analytics queries.

Flyway owns all schema changes. The Python services must stop creating application tables once the Java application becomes the owner of the schema.

### SQLite-to-PostgreSQL import

Create a one-off Java import command using `sqlite-jdbc`. It must:

1. Open a read-only copy of `currency_monitor.db`.
2. Validate the expected tables and columns.
3. Load settings, users, alert preferences, monitoring state, alerts, price history, and trade journal entries in dependency-safe order.
4. Preserve primary keys and unique pair/date values.
5. Use explicit transactions and fail with a nonzero exit code on validation errors.
6. Emit row counts and checksums for every source and destination table.
7. Be restartable without duplicating rows.

The importer is a migration tool, not a runtime SQLite dependency of the Spring Boot application.

### Table mapping

| SQLite table | PostgreSQL target | Migration rule |
|---|---|---|
| `settings` | `settings` | Preserve key/value data |
| `users` | `users` | Preserve IDs and password hashes |
| `alert_preferences` | `alert_preferences` | Preserve all condition fields |
| `monitoring_state` | `monitoring_state` | Convert epoch seconds explicitly |
| `alerts` | `alerts` | Preserve order and email-sent state |
| `price_history` | `price_history` | Preserve unique `(pair, date)` key |
| `trade_journal` | `trade_journal` | Preserve IDs, ownership, and timestamps |

### Timestamp rules

- New timestamps use UTC `Instant` and PostgreSQL `timestamptz`.
- Date-only market observations remain PostgreSQL `date`.
- API timestamps use ISO-8601 values that the existing `new Date(...)` UI calls can parse.
- The migration must document how legacy naive timestamps are interpreted and convert them once.

### Schema migrations

Initial Flyway migrations should create the current logical model without silently "improving" business constraints in the same release. Add indexes for:

- `price_history(pair, date)` unique.
- Alerts by descending timestamp.
- Trade journal by `(username, opened_at)`.
- Forecast runs by `(asset, id)`.
- Forecast rows by `(run_id, dt)`.

Foreign keys and tightened constraints can follow after legacy-data quality checks.

## 9. Authentication Migration

Spring Security will protect the same APIs and return the existing JSON error shape for unauthenticated requests.

Compatibility requirements:

- Continue accepting the current `salt$hexPbkdf2Hash` password format during migration.
- Use constant-time comparison.
- On successful login, optionally rehash the password to a modern Spring Security encoder and update the stored value.
- Use new BCrypt or delegated password encoding for newly registered users.
- Store the session secret outside the repository and rotate it through deployment configuration.
- Use `HttpOnly`, `SameSite=Lax`, secure-in-production cookies.
- Add CSRF protection and update the shared frontend fetch helper to include the CSRF token.
- Add login throttling after functional parity is complete.

Password compatibility must be verified against existing database samples in a safe test fixture without using real user credentials.

## 10. Backend Module Mapping

| Current Python module | Java responsibility |
|---|---|
| `app.py` | Spring Boot application and startup configuration |
| `modules/routes.py` | Feature controllers and exception handlers |
| `modules/auth.py` | Spring Security configuration, user service, password service |
| `modules/database.py` | Feature repositories backed by `JdbcClient` |
| `modules/currency.py` | Market-data adapters, normalization, indicator calculations |
| `modules/monitoring.py` | Scheduled monitoring coordinator and alert dispatcher |
| `modules/email_alert.py` | Email service and HTML template |
| `modules/prometheus.py` | Micrometer metrics and `/metrics` compatibility endpoint |
| `modules/backtest.py` | Backtest engine and statistics |
| `modules/dl_api.py` | Forecast read service |
| `modules/dl_pipeline.py` | Python forecast-worker orchestration and durable job state |
| `modules/dl_schema.py` / `dl_postgres.py` | Flyway-managed Postgres schema and Java repositories |
| `scripts/gold_kafka_producer.py` | Java Kafka publisher |

### Market-data adapters

Define stable internal ports such as:

- `LiveQuoteProvider`
- `HistoricalCloseProvider`
- `HistoricalOhlcProvider`

Implement provider adapters for Frankfurter, Yahoo, and Stooq. Keep the current provider order and fallback semantics. Use bounded timeouts, response-size limits, retry with jitter only for transient failures, and circuit breakers per provider.

External HTTP failures must never return fake or stale prices unless the contract explicitly marks their source and age.

### Alert and backtest parity

Port the current algorithms without changing their definitions:

- Percentage-change trend and five-observation consistency check.
- Historical high and low.
- Price-level crossing.
- Volatility spike.
- Moving-average crossover.
- Long-term trend.
- Backtest entries, exits, stop loss, take profit, holding period, drawdown, CAGR, profit factor, and payoff ratio.

Create a Python-generated fixture corpus containing deterministic OHLC series and expected alert/backtest outputs. Use it as an oracle for Java unit tests.

### Monitoring behavior

- Replace the global Python thread flag with a persisted `monitoring_enabled` setting plus scheduler health.
- Keep the one-hour per-pair alert cooldown.
- Prevent duplicate alerts when more than one application instance is running.
- Use the stored check interval, with a safe minimum and validation.
- Keep the current `active` and `thread_running` response fields during compatibility mode; derive `thread_running` from scheduler health.
- Record alert attempts, email outcomes, and cycle duration in metrics.

### Forecast and deep-learning boundary

The Java application will:

- Read forecast runs and forecast rows from PostgreSQL.
- Expose the existing `/api/dl/*` read endpoints.
- Persist asynchronous job state in PostgreSQL instead of an in-memory map.
- Delegate ingest/train operations to a Python worker over an authenticated internal HTTP interface.

The Python worker will:

- Retain PyTorch ingestion and training code.
- Stop owning table creation after Flyway becomes authoritative.
- Expose health and job execution endpoints on the internal Docker network only.
- Report structured progress and terminal errors.

## 11. Implementation Phases

Estimates assume one experienced developer and include tests and documentation.

### Phase 0: Freeze and characterize the baseline

**Estimate:** 1-2 days

- Commit or otherwise preserve the current working state before migration work.
- Create a tagged baseline release.
- Add characterization tests for routes, response bodies, password verification, alert detection, and backtesting.
- Capture deterministic historical market fixtures.
- Document current configuration variables and expected external dependencies.
- Pin Java 25 in the build and verify Maven runs under JDK 25.
- Decide and record the exact Spring Boot patch version.

**Exit gate:** Python behavior is reproducible and every current API has a documented or captured contract.

### Phase 1: Create the Java repository foundation

**Estimate:** 1-2 days

- Create `currency-analyzer-java`.
- Add Maven Wrapper, Java 25 toolchain, and Spring Boot skeleton.
- Add common error envelope, JSON configuration, static resource serving, and authentication stub.
- Add Actuator and Micrometer basics.
- Add CI for compile, unit tests, integration tests, and CodeQL Java analysis.
- Add initial Dockerfile and a local Compose profile.

**Exit gate:** The application starts under Java 25, returns a health response, exposes metrics, and passes an empty test suite in CI.

### Phase 2: PostgreSQL schema, import, and authentication

**Estimate:** 3-5 days

- Add Flyway migrations for current app tables.
- Build and test the SQLite import command.
- Implement repositories and row mappers.
- Implement Spring Security sessions, registration, login, logout, and status.
- Support existing PBKDF2 hashes and password-upgrade-on-login.
- Add CSRF support and update the shared frontend fetch helper.

**Exit gate:** A copied production-like database imports without count or checksum mismatches, and existing users can authenticate against the Java service.

### Phase 3: Market data and APIs

**Estimate:** 4-6 days

- Implement Frankfurter, Yahoo, and Stooq adapters.
- Implement live quote aggregation and provider fallback.
- Implement historical close and OHLC normalization.
- Implement pair parsing and encoded-slash handling.
- Add price-history persistence and scheduled synchronization.
- Add `GET /api/live-rates` and both historical endpoints.

**Exit gate:** All contract tests pass for the supported pair set; provider fallback, timeout, malformed response, and empty-data cases are covered.

### Phase 4: Analysis and backtesting

**Estimate:** 5-7 days

- Port all seven alert detectors.
- Port the backtest engine and statistics.
- Load Python-generated golden fixtures in Java tests.
- Implement `POST /api/backtest`.
- Add bounds checks for periods, lookback windows, prices, capital, and holding time.

**Exit gate:** Trigger booleans match exactly and numerical outputs are within the documented tolerance across the full fixture corpus.

### Phase 5: Settings, alerts, monitoring, email, and trade journal

**Estimate:** 4-6 days

- Implement settings and alert preference APIs.
- Implement alert history and clear behavior.
- Implement scheduler, distributed lock, cooldown, and monitoring status.
- Port all seven condition types into the monitoring dispatcher.
- Implement SMTP/TLS/SSL and Gmail fallback.
- Implement trade journal CRUD, ownership rules, realized PnL, and daily risk limits.
- Add `/api/test-email`.

**Exit gate:** Full alert and trade workflows pass through the existing browser UI without endpoint changes.

### Phase 6: Observability and production packaging

**Estimate:** 2-3 days

- Preserve `/metrics` and existing Prometheus metric names.
- Add request, provider, database, scheduler, email, login, backtest, and alert metrics.
- Add structured logging with request correlation IDs and secret redaction.
- Add readiness/liveness checks and update container health checks.
- Build a small multi-stage Java container image.
- Verify Prometheus recording and alert rules.

**Exit gate:** Prometheus successfully scrapes Java metrics, health checks do not require login, and dashboards no longer depend only on Python process internals.

### Phase 7: DL jobs and Kafka/Flink compatibility

**Estimate:** 3-5 days

- Add Java forecast read endpoints.
- Add durable DL job records and worker orchestration.
- Keep the Python trainer as an internal sidecar with health checks.
- Port gold producer deduplication and publishing behavior to Java.
- Validate the Kafka event schema and Flink/PostgreSQL sink flow.

**Exit gate:** Forecast reads match existing payloads, DL job status survives a Java restart, and the Java producer yields the same downstream gold signal shape.

### Phase 8: Shadow testing, cutover, and retirement

**Estimate:** 2-3 days

- Run Python and Java applications against equivalent database snapshots.
- Replay read-only requests and compare responses.
- Run a final data import during a short write freeze.
- Switch the container/image and host port or reverse proxy to Java.
- Observe errors, latency, alert delivery, and scheduler behavior.
- Keep Python deployment available until the rollback window closes.
- Archive the Python implementation after data and operational acceptance.

**Exit gate:** All acceptance criteria are met and rollback has been tested.

**Indicative total:** 4-6 weeks for one developer, excluding major upstream-provider changes.

## 12. Test Strategy

### Characterization tests

Run against the current Python implementation before behavior changes:

- Every endpoint's basic success and failure responses.
- Password verification for known test fixtures.
- Alert detector outputs for a broad set of deterministic price series.
- Backtest results and statistics.
- Alert and trade-journal persistence behavior.

### Java unit tests

- Indicator calculations and edge cases.
- Risk calculations.
- Pair normalization.
- Password compatibility adapters.
- Alert dispatch and cooldown decisions.
- Kafka deduplication.

### Integration tests

- Flyway against a real PostgreSQL Testcontainer.
- Repository transactions, uniqueness, ordering, and ownership checks.
- Spring Security session and CSRF behavior.
- WireMock tests for every external provider response path.
- Email delivery using a local fake SMTP server.
- Kafka publication using a Testcontainer.

### Contract and browser tests

- Compare Java responses with captured Python goldens.
- Run login, live rates, history, alerts, monitoring, settings, trade journal, and backtest flows against the static frontend.
- Verify `EUR%2FUSD` and `GOLD%2FUSD` route behavior.
- Verify chart rendering with empty, partial, and malformed series.

### Operational tests

- Startup with an empty database.
- Startup after every supported Flyway migration.
- Import idempotency and checksum validation.
- Scheduler duplicate prevention with two app instances.
- Provider timeout and outage fallback.
- Prometheus scrape and alert-rule evaluation.

## 13. Security and Configuration

- Never commit SMTP, database, session, or provider credentials.
- Use environment variables and Docker secrets where available.
- Keep production cookies `Secure`.
- Enable CSRF before production cutover.
- Add rate limiting for login and expensive backtests or DL jobs.
- Validate and cap all request sizes and page limits.
- Use allow-listed enum values for alert types, trade sides, provider names, and job types.
- Redact credentials and user PII from logs.
- Treat provider data as untrusted and enforce response-size and timeout limits.
- Run dependency and container vulnerability scanning in CI.

Configuration will use typed `@ConfigurationProperties` groups rather than scattered environment lookups:

- `app.market`
- `app.scheduler`
- `app.auth`
- `app.email`
- `app.forecast`
- `app.kafka`
- `spring.datasource`

## 14. Cutover and Rollback

### Pre-cutover

- Freeze writes briefly for the final SQLite snapshot.
- Import into PostgreSQL and validate counts, hashes, constraints, and sample records.
- Start Java against the cloned database on an alternate port.
- Run automated smoke tests and manual browser checks.
- Confirm one-hour alert cooldown and send a test email.
- Verify forecast and gold-streaming reads.

### Cutover

- Point the public port or reverse proxy to the Java container.
- Keep the Python app running on an internal port with its database snapshot intact.
- Monitor HTTP errors, provider failures, database latency, duplicate alerts, scheduler cycles, and email failures.

### Rollback

- Repoint the proxy or port to Python.
- Restore any writes made by Java into a compatibility form, or explicitly freeze writes during rollback.
- Keep the previous Compose definition and image tags documented.

Rollback must be rehearsed before production cutover; it is not enough to assume it will work.

## 15. Main Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Encoded slash handling changes pair routes | Historical and alert APIs fail | Build explicit compatibility tests first |
| Floating-point differences change backtests | Decision outputs differ | Python-generated golden fixtures and defined tolerance |
| Provider response changes break adapters | Missing live or historical data | WireMock contract tests, circuit breakers, current fallback rules |
| Existing PBKDF2 hashes cannot authenticate | Users locked out | Compatibility encoder and migration tests |
| SQLite import loses or duplicates rows | Data corruption | Read-only source, transactional import, counts, checksums, dry runs |
| Multiple app replicas send duplicate alerts | User spam | Database owner lock per monitoring cycle and per-pair cooldown |
| Python DL worker becomes an uncontrolled runtime dependency | Incomplete app startup | Optional profile, readiness isolation, durable job records |
| Flink schema and Kafka event drift | Broken streaming dashboard | Schema fixture tests and end-to-end Compose smoke test |
| Frontend fails because JSON fields changed | UI regression | Response goldens plus browser smoke tests |
| Migration work mixes with feature additions | Larger rollback surface | Freeze feature scope until parity is accepted |

## 16. Acceptance Criteria

The migration is complete when:

- The application builds and runs with Java 25 LTS.
- The Maven build resolves and tests the exact pinned Spring Boot version.
- Every existing page and protected API is available from the Java application.
- Existing users can authenticate with their current passwords.
- Existing application data imports with matching row counts and unique keys.
- All supported pairs return live and historical data with current provider fallbacks.
- All seven alert detectors and all backtest entry/exit modes pass parity tests.
- Monitoring start/stop, cooldown, email delivery, alert history, and metric names work.
- Trade journal and risk-limit workflows behave equivalently.
- Existing static frontend runs without route or response-shape changes.
- Forecast reads and durable job status operate with the Python sidecar.
- Kafka producer and Flink/PostgreSQL pipeline compatibility is verified.
- Prometheus scrapes the Java application and the recording/alert rules are valid.
- CI runs Java tests, PostgreSQL integration tests, CodeQL, and container build checks.
- A documented and rehearsed rollback path exists.

## 17. Default Decisions Requiring Approval

Unless changed during review, implementation will use these defaults:

1. Build `currency-analyzer-java` as a separate sibling repository.
2. Target Java 25 and the latest supported Spring Boot 4.x patch, pinned before coding.
3. Use PostgreSQL as the canonical database.
4. Preserve the current frontend and API contract.
5. Use Spring JDBC rather than introducing JPA.
6. Keep PyTorch training and Flink SQL as existing sidecars.
7. Port the gold Kafka producer to Java.
8. Enable CSRF and update the frontend fetch helper.
9. Keep a one-hour per-pair alert cooldown.
10. Preserve existing Prometheus metric names and `/metrics`.

## 18. Immediate Work After Approval

1. Preserve and tag the current Python baseline.
2. Generate the API and numerical behavior fixtures.
3. Create `currency-analyzer-java` with Maven Wrapper and Java 25 toolchain.
4. Add the first Flyway schema and PostgreSQL Testcontainers build.
5. Implement authentication and the first contract-tested endpoint vertical slice.

No Java application implementation should begin before the baseline, Spring Boot version, persistence target, and API-compatibility assumptions above are approved.
