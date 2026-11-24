---
name: pr-preflight-review
description: >-
  Runs an adversarial pre-PR code review and full Java 25 / Maven / JaCoCo / SonarCloud
  verification for currency-analyzer-java. Use before committing, pushing, opening a PR,
  or after fixing CI/review feedback on a branch.
---

# Pre-PR Adversarial Review & Verification

Run this workflow before committing/pushing changes or marking a PR ready for merge.
Do not treat "all unit tests pass" as proof of correctness—actively hunt for the failure
modes below in the actual `git diff`.

## Step 1: Adversarial Diff Audit (Required Before Running Tests)

Inspect `git diff` (against `origin/main` or the PR base branch) and verify each item
that applies to the changed code:

1. **AuthZ vs. Public Registration**
   - List every new or modified HTTP endpoint.
   - Check `WebSecurityConfig` for `permitAll()` routes (including `/api/auth/register`).
   - Confirm that any endpoint mutating shared/global state requires an explicit role or
     allowlist (`ROLE_ADMIN` / configured admin users) and fails closed by default.
   - Verify tests cover anonymous (`401`), non-admin authenticated (`403`), and authorized
     admin (`2xx`) access.

2. **Async & Concurrent State Races**
   - For every executor, `@Async`, or background worker writing to the DB or external
     storage, trace two interleavings:
     - **Stale overwrite:** Job A (older content/hash) finishes *after* Job B (newer
       content/hash). Confirm Job A cannot overwrite Job B's chunks/status or leave stale
       chunks behind a newer hash.
     - **Delete during ingest:** A document is deleted while ingestion is in flight. Confirm
       the worker cannot resurrect chunks or fail inconsistently.
   - Verify CPU-bound native work (ONNX/JNI) uses a bounded platform thread pool, not
     unbounded virtual threads.

3. **Storage Prefix & Path Boundary Escapes**
   - For any GCS/S3/filesystem prefix or path filter:
     - Confirm a prefix like `knowledge` is normalized with a trailing `/` so it cannot
       match sibling prefixes like `knowledge-other`.
     - Confirm blank/empty/whitespace query params cannot escape the configured root prefix
       and enumerate the entire bucket.
     - Confirm `..` traversal segments are rejected.

4. **Multilingual (CJK) & Model Input Limits**
   - For any chunker, prompt builder, or contextual-embedding prefix:
     - Test with dense Chinese/CJK text (where 1 char ≈ 1 SentencePiece token), not just
       English prose.
     - Confirm `prefix + chunk` stays comfortably within the embedding model's token limit
       (e.g., 256 tokens for MiniLM) so body content is not silently truncated.

5. **Lifecycle Completeness (Cold Start & Deletions)**
   - When replacing static/classpath resources with an external store (GCS/DB):
     - Confirm deleted remote objects are pruned (or reconciled) on sync so stale entries do
       not remain searchable.
     - Confirm a fresh deployment recovers or syncs automatically on startup without
       requiring a manual admin click to become usable.

## Step 2: Java 25 Build, Test & JaCoCo Coverage Gate

On Windows in this workspace, Maven requires JDK 25 (`C:\Program Files\Java\latest\jdk-25`),
and Java NIO loopback sockets / Mockito self-attach require running outside the sandbox.

1. Set `JAVA_HOME` and run full verification (use `clean` if any `.java` or test files were
   deleted/renamed to avoid stale `.class` files in `target/test-classes`):

   ```powershell
   $env:JAVA_HOME = 'C:\Program Files\Java\latest\jdk-25'
   .\mvnw.cmd -o clean verify
   ```

   *(If offline cache is missing a newly added dependency, run `.\mvnw.cmd dependency:go-offline`
   once first.)*

2. Check JaCoCo coverage in `target/site/jacoco/jacoco.xml` so SonarCloud's `>= 80%`
   new-code coverage gate passes comfortably (aim for `>= 83%` combined line + branch
   coverage on newly added/modified packages).

## Step 3: SonarCloud & Flyway Pre-Flight Check

Before pushing, scan modified files for the project's recurring static-analysis blockers:

- **Flyway split:** No PostgreSQL-only SQL inside `src/main/resources/db/migration/**`
  (must live in `src/main/resources/db/postgresql-migration/`).
- **DI ambiguity (`java:S9352`):** No competing `@Component` beans for the same interface.
- **Record arrays (`java:S6218`):** Every `record` with `byte[]`/`float[]` overrides
  `equals`, `hashCode`, and `toString`.
- **Regex / test smells (`java:S8786`, `S5863`, `S5845`):** No backtracking regexes,
  self-assertions, or dissimilar-type `assertNotEquals`.

## Step 4: Post-Push CI Verification

After creating or pushing to any PR branch, automatically monitor all GitHub PR check runs (`gh pr checks <pr>` — `verify`, `SonarCloud Code Analysis`, `qodana`) until every check finishes. If any check fails, inspect the logs (`gh run view <run-id> --log-failed`) and SonarCloud issues, fix them on the PR branch, push, and re-verify until every check reaches `completed` + `success`.
