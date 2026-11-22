# GCS + PostgreSQL (`pgvector`) RAG Implementation Summary & Troubleshooting Log

**Completed:** 2026-11-22

**Branch:** `cursor/knowledge-agent-2fe5`

**Related design doc:** [GCS_RAG_PIPELINE_PLAN.md](./GCS_RAG_PIPELINE_PLAN.md)

---

## 1. What Was Built

### Cloud Storage (`com.currencyanalyzer.storage`)
- `ObjectStorageClient`, `StorageException` — storage interface (`put`, `get`, `delete`, `list`) decoupled from GCS.
- `GcsObjectStorageClient` — lazy Google Cloud Storage client with `GOOGLE_APPLICATION_CREDENTIALS` / ADC support and automatic `HTTPS_PROXY` / `HTTP_PROXY` transport wiring.
- `LocalObjectStorageClient` — local filesystem implementation with path-traversal protection for offline dev and automated tests.

### Database & Migrations
- `src/main/resources/db/migration/V5__rag_documents_schema.sql` — `rag_documents` and `rag_chunks` tables (H2 + PostgreSQL compatible).
- `src/main/resources/db/postgresql-migration/V6__rag_pgvector.sql` — PostgreSQL-only conditional migration enabling `vector` extension, `embedding vector(384)`, and HNSW cosine index.
- `compose.yaml` — upgraded Postgres service to `pgvector/pgvector:pg16` on port `5434` (configurable via `POSTGRES_PORT`) and added GCS environment variables.

### Unified RAG Pipeline (`com.currencyanalyzer.ai.rag`)
- `DocumentParser` & `TextDocumentParser` — strict UTF-8 validation and format-aware chunking for `.md`, `.txt`, and `.csv` (~600-char chunks with 100-char overlap tuned for `MiniLM-L12-v2`'s 256-token window; CSV header repetition on every chunk; merges tiny `<45`-char / `> 来源：` header stubs; skips `## 标签` footers).
- `RagDocumentRepository` — `JdbcTemplate` persistence with runtime `pgvector` detection and cosine similarity search (`<=>`).
- `RagEmbeddingConfiguration` — standalone `EmbeddingModel` bean (`LazyTransformersEmbeddingModel`) decoupled from `app.ai.enabled`.
- `RagIngestionService` — bounded 2-thread daemon ingestion pool producing contextual embeddings (document title + lead intro + chunk heading + content).
- `RagDocumentService` & `BuiltinGuideSeeder` — upload with storage rollback on DB error, incremental `syncFromStorage`, download, re-index, delete (protecting built-in guides), and idempotent startup seeding of `src/main/resources/knowledge/guides/user-guide.md`.
- `RagChunkRetriever` — Hybrid Search fusing `pgvector` cosine similarity with exact-phrase & CJK/Latin keyword scoring, parent-document core-section expansion, changelog/stub down-ranking, and dynamic score-gap filtering.
- `RagDocumentController` — authenticated REST API under `/api/rag/*` with safe download headers and `413` / `502` handling in `ApiExceptionHandler`.

### Knowledge Repository Cleanup & AI Integration
- Replaced in-memory `SemanticKnowledgeIndex` with unified PostgreSQL retrieval via `KnowledgeRetriever` + `RagChunkRetriever`.
- Added `src/main/resources/knowledge/guides/user-guide.md`; removed migrated `concepts/`, `entities/`, and `journal/` directories from Git; added deterministic unit test fixtures under `src/test/resources/knowledge/fixtures/`.

### Dashboard Knowledge UI
- Updated `dashboard.html`, `dashboard.js`, `styles.css`, and added `src/main/resources/static/pages/knowledge.js`:
  - Side-by-side **Upload Document** (custom English file picker) and **Sync Bucket & Refresh** panels.
  - Searchable, folder-filterable, paginated **Indexed Documents** table (15 rows/page) with `en-US` timestamps, live status polling, and Download / Re-index / Delete actions.
  - **Test RAG Retrieval** panel with visible `.rag-chunk-preview` cards and similarity scores.

---

## 2. Problems Encountered & How They Were Solved

### 2.1 GCP & Infrastructure

| Problem | Root Cause | Fix |
|---|---|---|
| **Service account key creation disabled** | GCP org policy `iam.disableServiceAccountKeyCreation` was enforced on the organization. | Supported both keyless ADC (`gcloud auth application-default login`) and project-scoped override with `GOOGLE_APPLICATION_CREDENTIALS` in `GcsObjectStorageClient`. |
| **PostgreSQL port `5433` conflict** | A separate desktop project's `postgres:16-alpine` container already bound port `5433` and lacked `pgvector`. | Left the other project untouched and ran this repository's `pgvector/pgvector:pg16` container on port **`5434`** (`compose.yaml` + `application.yml`). |
| **GCS `Connect timed out` during OAuth2 token exchange** | Google's Java `NetHttpTransport` ignored the Windows `HTTPS_PROXY=http://127.0.0.1:7890` environment variable. | Auto-configured HTTP proxy from `HTTPS_PROXY` / `HTTP_PROXY` in both `GcsObjectStorageClient` (for Google Auth + Storage transport) and `CurrencyAnalyzerApplication` (JVM proxy properties with `localhost` bypassed). |

### 2.2 Build, Database & Concurrency

| Problem | Root Cause | Fix |
|---|---|---|
| **Flyway `V6` `pgvector` SQL failed on H2 tests** | Flyway recursively scanned `classpath:db/migration`, executing `db/migration/postgresql/V6__rag_pgvector.sql` on H2. | Moved `V6__rag_pgvector.sql` to a sibling folder (`db/postgresql-migration`), included only in main `application.yml`, and wrapped DDL in a conditional `pg_available_extensions` check. |
| **UI became unresponsive after syncing 101 GCS files** | `Executors.newVirtualThreadPerTaskExecutor()` spawned 101 simultaneous CPU-heavy native ONNX embedding jobs, saturating CPU and HikariCP's 10 DB connections. | Replaced with a bounded **2-thread daemon pool** in `RagIngestionService` so ingestion runs smoothly without starving HTTP requests or SSE chat streams. |
| **Spring Boot 4 deprecation warning** | `HttpStatus.PAYLOAD_TOO_LARGE` is deprecated in Spring Framework 7 / Spring Boot 4. | Updated `ApiExceptionHandler` to return `HttpStatus.CONTENT_TOO_LARGE`. |

### 2.3 Frontend UI

| Problem | Root Cause | Fix |
|---|---|---|
| **Mixed Chinese browser chrome, cluttered cards, hidden search previews** | Native `<input type="file">` and `toLocaleString()` used Windows `zh-CN` locale; 102 tall cards overflowed a single scrollbox; `.code-panel` had `display: none`. | Built an English file picker, `en-US` date formatting, separate Upload/Sync panels, a filterable/paginated table (15/page), Refresh button feedback, and visible `.rag-chunk-preview` cards. |

### 2.4 RAG Chunking & Retrieval Quality

| Problem | Root Cause | Fix |
|---|---|---|
| **Top-level Markdown `#` titles missed** | H1 headings followed immediately by `##` had empty bodies and were dropped before title extraction. | Added `firstMarkdownHeading()` in `TextDocumentParser`. |
| **Tiny `> 来源：` metadata stubs & `## 标签` outscored real content** | 20-character chunks had embeddings ~90% dominated by document titles, beating 500-character body chunks. | Merged `<45`-char / `> 来源：` quote stubs into the next section and skipped `## 标签` footers (while preserving concise 50+ char Chinese sections). |
| **Pure vector search missed exact names & bilingual sibling sections** | Small 384-dim `MiniLM-L12-v2` clustered `"Howard Marks"` with generic famous investors (`巴菲特`, `索罗斯`, `伯里`) and didn't link English queries to Chinese-only body chunks (`第二层次思维`, `钟摆理论`). | Upgraded `RagChunkRetriever` to **Hybrid Search** (`pgvector` cosine + exact-phrase/CJK+Latin keyword fusion), parent-document core-section expansion, changelog/stub down-ranking, and dynamic score-gap filtering; added **contextual embeddings** (title + document lead intro) in `RagIngestionService`. |

---

## 3. Verification Results

- **Automated tests (`.\mvnw.cmd -o test` on Java 25):**
  - `TextDocumentParserTests`: 5/5 passing
  - `RagPipelineIntegrationTests`: 4/4 passing
  - `RagDocumentControllerTests`: 3/3 passing
  - Full suite (60 tests total across all packages): **0 failures, 0 errors**.
- **Live PostgreSQL (`pgvector 0.8.7`) & GCS (`currency-analyze`):**
  - Confirmed `V1`–`V6` applied cleanly on `localhost:5434`.
  - Confirmed `user-guide.md` seeded automatically (`9` chunks, `READY`).
  - Confirmed `POST /api/rag/sync` discovered and indexed **101** GCS objects into PostgreSQL `pgvector` via the `127.0.0.1:7890` proxy.

---

## 4. Quick Runbook

1. **Start `pgvector` PostgreSQL:**
   ```powershell
   docker compose up -d postgres
   ```
2. **Start the application:**
   ```powershell
   $env:JAVA_HOME = 'C:\Program Files\Java\latest\jdk-25'
   $env:GOOGLE_APPLICATION_CREDENTIALS = 'C:\path\to\your-key.json'
   $env:AI_ASSISTANT_ENABLED = 'true'
   .\mvnw.cmd spring-boot:run
   ```
3. **Sync or upload documents:**
   - Open `http://localhost:5000/dashboard.html` → **Knowledge** tab.
   - Click **Sync from GCS** (prefix `knowledge`) or upload `.md` / `.txt` / `.csv` files directly.
   - Use **Test RAG Retrieval** or **Ask Currency Analyzer** to query indexed content.
