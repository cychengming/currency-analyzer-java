# currency-analyzer-java

Java 25 and Spring Boot implementation of Currency Analyzer.

The application is being migrated from the existing Flask implementation while preserving its browser, API, operational, and data contracts.

## Current foundation

- Java 25 with Spring Boot 4.0.1
- PostgreSQL with Flyway schema management
- Spring Security sessions, versioned PBKDF2 password storage, and legacy Flask PBKDF2 compatibility
- Existing static frontend copied into `src/main/resources/static`
- CSRF-aware shared frontend request helper
- SQLite-to-PostgreSQL importer
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

- [Migration plan](docs/JAVA_REFACTOR_PLAN.md)
- [Implementation status](docs/IMPLEMENTATION_STATUS.md)
