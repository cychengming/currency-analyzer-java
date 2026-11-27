# currency-analyzer-java

Java 25 and Spring Boot implementation of Currency Analyzer.

The application is being migrated from the existing Flask implementation while preserving its browser, API, operational, and data contracts.

## Current foundation

- Java 25 with Spring Boot 4.1.2
- PostgreSQL with Flyway schema management
- Spring Security sessions, versioned PBKDF2 password storage, and legacy Flask PBKDF2 compatibility
- Existing static frontend copied into `src/main/resources/static`
- CSRF-aware shared frontend request helper
- SQLite-to-PostgreSQL importer
- AI assistant conversation storage and owner-scoped API foundation (disabled by default)
- Actuator health and Prometheus metrics
- JaCoCo coverage and SonarQube Cloud analysis
- Maven Wrapper, Docker, Compose, and GitHub Actions CI

## Build and test

```powershell
$env:JAVA_HOME = 'D:\Program Files\Java\jdk-25.0.4.1'
.\mvnw.cmd verify
```

The tests use an H2 PostgreSQL-compatibility database. Docker is required only for the real PostgreSQL deployment and future Testcontainers coverage.

The `verify` phase writes JaCoCo coverage reports to `target/site/jacoco`.

### AI assistant (early access)

The assistant is disabled unless `AI_ASSISTANT_ENABLED=true` is set. To enable DeepSeek-backed streaming chat through Spring AI, configure the server environment with:

```text
AI_ASSISTANT_ENABLED=true
DEEPSEEK_API_KEY=<server-side API key>
DEEPSEEK_MODEL=deepseek-flash
```

The API key is never sent to the browser. Spring AI calls DeepSeek and exposes read-only tools for cached live rates, up to a year of daily history, a comparison of two pairs, recorded alerts, alert rules, monitoring state, non-secret settings, and a stored forecast. It quotes those tool results and does not invent prices. Responses are educational and not investment advice. Chat text, the model's reasoning, and tool-call records are stored in PostgreSQL under the owning account.

Notes are searched by meaning with a local multilingual embedding model (`paraphrase-multilingual-MiniLM-L12-v2`, about 120 MB). The model is downloaded on the first note search into `AI_EMBEDDING_CACHE` (by default `~/.cache/currency-analyzer/embeddings`) and then reused. If that download fails, the assistant falls back to keyword overlap. Set `AI_KNOWLEDGE_RETRIEVAL=keyword` to skip the download.

## Enable SonarQube Cloud

1. Create or import the `cychengming_currency-analyzer-java` project in SonarQube Cloud.
2. Generate a project analysis token.
3. Add it to the GitHub repository as the Actions secret `SONAR_TOKEN`.
4. Run the Java CI workflow.

Until the secret is configured, GitHub Actions emits a warning and skips the Sonar scan without failing the build. Once configured, the scan waits for the Sonar quality gate.

## Run with Docker

```powershell
docker compose up --build
```

The Java application is exposed at `http://localhost:18081`.

## Import legacy SQLite data

Stop writes to the Flask application before taking the final import snapshot. Then run the packaged Java application with the import profile and source path:

```powershell
java -jar target\currency-analyzer-java-0.1.0-SNAPSHOT.jar `
  --spring.main.web-application-type=none `
  --app.legacy-import.enabled=true `
  --source=C:\path\to\currency_monitor.db
```

The importer opens SQLite read-only, copies all application tables in one target transaction, skips existing keys on retry, and resets PostgreSQL identity sequences. Legacy timestamps without an offset are interpreted as UTC, matching the current container runtime.

## Documentation

- [System architecture summary & decision log (ADR)](docs/SYSTEM_ARCHITECTURE_AND_DECISIONS.md)
- [5-Stage trading workstation roadmap](docs/TRADING_WORKSTATION_ROADMAP.md)
- [AI chat & RAG architecture review](docs/AI_RAG_ARCHITECTURE_REVIEW.md)
- [Migration plan](docs/JAVA_REFACTOR_PLAN.md)
- [Implementation status](docs/IMPLEMENTATION_STATUS.md)
- [AI assistant design and estimate](docs/AI_ASSISTANT_MODULE.md)
