# Implementation Status

## Current phase

Phase 2 - PostgreSQL, authentication, and legacy import

## Completed

- Created the Java 25 Maven project.
- Pinned Spring Boot 4.0.1.
- Added Spring MVC, Spring Security, validation, Actuator, and Prometheus dependencies.
- Added anonymous access for the current pages, authentication bootstrap routes, health endpoints, and `/metrics`.
- Added the initial compatibility error envelope and auth-status endpoint.
- Added a foundation context test and Docker/CI scaffolding.
- Added the PostgreSQL/Flyway baseline schema.
- Added Spring Security session authentication and legacy Flask PBKDF2 verification.
- Added password upgrade-on-login.
- Added the CSRF token endpoint and updated the shared frontend request helpers.
- Copied the existing static frontend and preserved `/static/...` paths.
- Added a transactional, read-only SQLite importer for all current application tables.
- Added six automated tests covering context loading, password compatibility, authentication, CSRF/static access, and SQLite import.
- Added JaCoCo line and branch coverage generation.
- Added SonarQube Cloud project configuration and a guarded GitHub Actions scan.

## Next

- Run the importer against a production-like PostgreSQL instance.
- Implement settings and alert-preference APIs.
- Implement market-data providers and historical price storage.

## Compatibility notes

- The target application initially listens on port `5000`.
- The health check uses `/actuator/health/readiness`.
- `/metrics` remains available for the existing Prometheus configuration.
- Existing `/static/...` asset URLs are retained for frontend compatibility.
- The current login/register endpoints remain CSRF-exempt; protected mutations use `X-XSRF-TOKEN`.
