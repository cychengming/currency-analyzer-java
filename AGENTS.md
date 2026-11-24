# Currency Analyzer (Java) — Agent Rules

## Workspace Boundary
- Operate strictly inside this repository (`currency-analyzer-java`).
- Never inspect, modify, start, or stop files, configs, or Docker containers belonging to sibling projects outside this workspace (such as `Desktop\currency-analyzer`).
- Never read, print, or commit secret key files (GCP service-account JSON keys, API keys, `.env` secrets).

## Security & Authorization Invariants
- Before adding or changing any mutating controller endpoint, inspect `WebSecurityConfig` for `permitAll()` routes (especially `/api/auth/register`).
- Never assume "authenticated user = trusted operator" for shared-corpus, admin, or global state mutations. Enforce explicit authorization (e.g., `ROLE_ADMIN` / configured allowlist) and fail closed by default when no allowlist is configured.

## Concurrency, Storage & RAG Invariants
- **Async write/delete races:** Any background job writing to the database must guard against out-of-order stale completions and concurrent deletes using both per-entity monotonic generation/sequence tracking and transactional optimistic SQL guards (e.g., `WHERE id = ? AND sha256 = ?`).
- **CPU-bound / JNI workloads:** Never run CPU-heavy native inference (e.g., local ONNX embeddings) on unbounded `Executors.newVirtualThreadPerTaskExecutor()`. Use a small bounded platform thread pool so bulk jobs do not saturate CPU or HikariCP.
- **Object-storage prefix scoping:** Never strip a trailing `/` from GCS/S3 listing prefixes (`knowledge` matches `knowledge-other`). Never allow a blank/empty user parameter to escape a configured default prefix and list the entire bucket. Reject `..` segments and confine normalized prefixes strictly inside `<defaultPrefix>/`.
- **Multilingual (CJK) token budgets:** When chunking text or prepending contextual headers for token-limited embedding models (e.g., 256-token SentencePiece MiniLM), weight CJK/fullwidth characters (~1 token/char) separately from Latin (~4 chars/token) so Chinese text is not silently truncated before embedding.
- **External corpus lifecycle:** When replacing static classpath data with an external bucket/store, handle deleted-object pruning during sync and cold-start / startup synchronization so a fresh deployment is immediately usable.

## Database & Flyway Migrations
- Flyway scans `classpath:db/migration` recursively. Never place PostgreSQL-only DDL (`CREATE EXTENSION`, `pgvector`, `pg_trgm`, GIN/HNSW indexes) in any subdirectory under `src/main/resources/db/migration/`.
- Place PostgreSQL-only migrations in the sibling folder `src/main/resources/db/postgresql-migration/` (excluded from H2 tests in `src/test/resources/application.yml`), and guard extension creation with `pg_available_extensions`.

## Java 25, Spring Boot 4 & SonarCloud Guardrails
- Avoid registering multiple `@Component` beans implementing the same interface (triggers Sonar `java:S9352`); prefer a single `@Bean` factory method in a `@Configuration` class.
- For any Java `record` containing array fields (`byte[]`, `float[]`, etc.), explicitly override `equals`, `hashCode`, and `toString` using `Arrays.equals` / `Arrays.hashCode` (`java:S6218`).
- Avoid backtracking-prone regexes such as `(.+?)\s*$` (`java:S8786`); prefer simple string trimming/slicing.
- Use Spring Boot 4 / Spring 7 status constants (e.g., `HttpStatus.CONTENT_TOO_LARGE`, not deprecated `HttpStatus.PAYLOAD_TOO_LARGE`).
- Never return `ResponseEntity.ok(...)` (`2xx`) inside a `catch` block (`java:S6863`); return a matching error status (`400`, `401`, `409`, `502`, etc.) or let `ApiExceptionHandler` map the exception.
- In tests, avoid self-comparisons (`assertEquals(x, x)`, `java:S5863`) and dissimilar-type `assertNotEquals` (`java:S5845`).

## Pull Request & CI Monitoring
- After creating or updating any Pull Request (via `gh pr create` or pushing commits to an open PR branch), always monitor all PR checks (`gh pr checks <pr>`) until every check (`verify`, `SonarCloud Code Analysis`, `qodana`) finishes.
- Do not stop and ask the user whether to check CI — proactively inspect failing workflow logs (`gh run view <run-id> --log-failed`) and SonarCloud issues, fix all CI/test/Sonar/Qodana failures on the PR branch, push the fix, and continue monitoring until every check passes green.

## Runtime & UI Hygiene
- Respect `HTTPS_PROXY` / `HTTP_PROXY` explicitly in Google Cloud SDK HTTP transports so OAuth2/GCS calls work behind local proxies.
- Keep the dashboard UI consistently English: avoid raw browser-locale `<input type="file">` controls and default-locale `toLocaleString()` formatting (use explicit `'en-US'` formatting and custom English upload controls).
