# AI Assistant Module: Design and Delivery Estimate

**Status:** Proposed

**Created:** 2026-11-07

**Target application:** Currency Analyzer Java

**Recommended first release:** Read-only, tool-grounded market assistant

## 1. Executive Recommendation

Add an authenticated **“Ask Currency Analyzer”** assistant to the existing dashboard. The assistant should explain application data, alerts, historical movements, forecasts, and backtest results. It should not place trades, execute arbitrary code, or provide personalized investment advice.

The recommended implementation uses the OpenAI Responses API through a server-side provider abstraction. Official OpenAI documentation describes the Responses API as supporting stateful interactions, built-in tools, and function calling with external systems and data:

- [Responses API overview](https://developers.openai.com/api/reference/responses/overview)

The recommended first release is a read-only assistant with:

- A streaming chat drawer integrated into the current dashboard.
- Server-owned API credentials.
- A limited set of typed, read-only tools connected to Currency Analyzer services.
- Per-user rate, token, and cost limits.
- Conversation and tool-call auditing.
- Clear timestamps and source attribution for market data.
- No autonomous trading, alert modification, or risk-setting changes.

## 2. Goals

The assistant should help users understand and navigate the application:

- Explain why an alert condition did or did not trigger.
- Summarize recent alerts for a currency pair.
- Compare historical price series.
- Explain moving averages, volatility, and other computed indicators.
- Describe assumptions and outcomes from an existing backtest.
- Interpret the latest forecast with its date, horizon, and limitations.
- Suggest which existing dashboard feature can answer a user’s question.

## 3. Non-Goals

The first release will not:

- Recommend buying, selling, or holding a financial instrument.
- Place trades or connect to a brokerage.
- Execute arbitrary SQL, shell commands, Java code, or user-provided scripts.
- Modify alert preferences or monitoring settings.
- Read private trade-journal entries unless explicitly authorized in a later phase.
- Treat generated text as authoritative market data.
- Replace deterministic calculations with model-generated numbers.

## 4. User Experience

Add a persistent **Ask** button or right-side drawer to the existing dashboard. A separate page is unnecessary for the first release.

Suggested starter prompts:

- “Explain the latest EUR/USD alert.”
- “Compare EUR/USD and GBP/USD over the last 90 days.”
- “Summarize the alerts from this week.”
- “Explain the assumptions used by my latest backtest.”
- “What does the current forecast say, and how uncertain is it?”
- “Which alert conditions are enabled for gold?”

The chat drawer should provide:

- Streaming responses.
- Stop/cancel control.
- Markdown rendering with safe sanitization.
- Pair and timeframe chips.
- Links to relevant dashboard pages.
- Visible data timestamps.
- An error state when the AI provider is unavailable.
- A clear notice that responses are educational and not investment advice.

## 5. Target Architecture

```text
Existing static dashboard
        |
        | Authenticated streaming request
        v
Spring MVC AI API
  - user and session ownership
  - request quotas and kill switch
  - conversation orchestration
  - SSE event stream
        |
        v
AI Orchestrator
  - system prompt and prompt version
  - tool schema registry
  - response validation
  - usage accounting
        |
        +----> OpenAI Responses API
        |
        +----> Currency Analyzer tool adapters
                 - live market data
                 - historical prices
                 - alerts
                 - backtests
                 - forecasts
        |
        v
PostgreSQL
  - conversations
  - messages
  - tool invocations
  - usage records
```

### Technology choices

- Keep Spring MVC; use a streaming response rather than introducing Spring WebFlux for this feature.
- Define an internal `AiProvider` interface so the OpenAI implementation is replaceable.
- Use the current official OpenAI Java SDK or a small `RestClient` adapter, pinned to an exact version.
- Prefer typed tool definitions and structured JSON results over free-form context dumps.
- Keep the OpenAI API key only in server-side environment configuration.

## 6. Proposed Java Module

```text
com.currencyanalyzer.ai/
├── config/
│   └── AiConfiguration.java
├── chat/
│   ├── AiChatController.java
│   ├── AiChatService.java
│   ├── AiConversationRepository.java
│   └── AiMessageRepository.java
├── provider/
│   ├── AiProvider.java
│   └── OpenAiResponsesProvider.java
├── tools/
│   ├── AiTool.java
│   ├── AiToolRegistry.java
│   └── adapters/
├── prompt/
│   └── PromptCatalog.java
├── safety/
│   ├── AiRateLimiter.java
│   └── AiResponseGuard.java
└── usage/
    └── AiUsageService.java
```

Prompt files should be versioned resources, for example:

```text
src/main/resources/prompts/market-assistant-v1.md
```

## 7. API Design

### Create a conversation

```http
POST /api/ai/conversations
```

Response:

```json
{
  "id": "9d750fd9-16a5-4d82-89ca-6764f607a3d5",
  "created_at": "2026-11-07T03:30:00Z"
}
```

### List conversations

```http
GET /api/ai/conversations?limit=20
```

Only conversations owned by the authenticated user are returned.

### Send a message and stream the response

```http
POST /api/ai/conversations/{conversationId}/messages
Accept: text/event-stream
Content-Type: application/json
```

Request:

```json
{
  "message": "Explain why my EUR/USD alert triggered."
}
```

Suggested event types:

- `message_started`
- `text_delta`
- `tool_started`
- `tool_completed`
- `message_completed`
- `usage`
- `error`

Example events:

```text
event: text_delta
data: {"text":"The alert triggered because "}

event: tool_completed
data: {"tool":"get_alert_history","row_count":1,"as_of":"2026-11-07T03:29:00Z"}

event: message_completed
data: {"message_id":"...","finish_reason":"completed"}
```

This is a one-way streaming interaction, so SSE-style streaming over an authenticated request is sufficient. WebSockets are not required for the first release.

### Cancel a response

```http
POST /api/ai/conversations/{conversationId}/cancel
```

### Delete a conversation

```http
DELETE /api/ai/conversations/{conversationId}
```

## 8. Tool Boundary

The model may invoke only an allow-listed set of application tools.

| Tool | Purpose | Required data |
|---|---|---|
| `getLiveRates` | Current tracked rates | Snapshot timestamp |
| `getHistoricalSeries` | Historical closes or OHLC | Pair, days, date range |
| `comparePairs` | Normalized pair comparison | Pairs, range |
| `getAlertHistory` | Triggered alerts | Pair, limit, time window |
| `getAlertPreferences` | Enabled conditions | Pair |
| `getBacktestSummary` | Explain existing backtest result | Stored result or deterministic rerun |
| `getLatestForecast` | Read latest forecast | Asset, run ID |

Tool requirements:

- Tools must be read-only.
- Inputs must be validated and bounded.
- Results must include units, source, and data timestamp.
- Tool failures must be returned as structured errors, not invented data.
- The model must never provide raw SQL or tool names that are not registered.
- Tool calls must be persisted for auditability.

Do not enable web search, file browsing, code execution, or action-taking tools in the first release.

## 9. Data Model

Add Flyway-managed tables:

### `ai_conversations`

- `id UUID PRIMARY KEY`
- `username VARCHAR(255) NOT NULL`
- `title VARCHAR(255)`
- `provider VARCHAR(32) NOT NULL`
- `model VARCHAR(64) NOT NULL`
- `created_at TIMESTAMPTZ NOT NULL`
- `updated_at TIMESTAMPTZ NOT NULL`
- `deleted_at TIMESTAMPTZ`

### `ai_messages`

- `id UUID PRIMARY KEY`
- `conversation_id UUID NOT NULL`
- `role VARCHAR(16) NOT NULL`
- `content TEXT NOT NULL`
- `provider_response_id VARCHAR(128)`
- `prompt_version VARCHAR(32)`
- `created_at TIMESTAMPTZ NOT NULL`

### `ai_tool_invocations`

- `id UUID PRIMARY KEY`
- `message_id UUID NOT NULL`
- `tool_name VARCHAR(64) NOT NULL`
- `arguments_json JSONB`
- `result_summary JSONB`
- `status VARCHAR(16) NOT NULL`
- `duration_ms INTEGER`
- `created_at TIMESTAMPTZ NOT NULL`

### `ai_usage`

- `id UUID PRIMARY KEY`
- `username VARCHAR(255) NOT NULL`
- `conversation_id UUID`
- `model VARCHAR(64) NOT NULL`
- `input_tokens INTEGER`
- `output_tokens INTEGER`
- `estimated_cost_usd NUMERIC(12, 6)`
- `request_count INTEGER NOT NULL DEFAULT 1`
- `created_at TIMESTAMPTZ NOT NULL`

Retention and deletion behavior must be configurable. Deleting a conversation should remove its messages and tool records or mark them for asynchronous erasure according to the chosen retention policy.

## 10. Configuration

Proposed environment variables:

```text
AI_ASSISTANT_ENABLED=false
OPENAI_API_KEY=
OPENAI_MODEL=<configured-model-id>
AI_DAILY_REQUEST_LIMIT=50
AI_DAILY_TOKEN_LIMIT=50000
AI_MAX_INPUT_CHARS=8000
AI_MAX_OUTPUT_TOKENS=1200
AI_REQUEST_TIMEOUT_SECONDS=60
AI_CONVERSATION_RETENTION_DAYS=90
```

The model identifier should be configuration, not hard-coded in controllers. Select and benchmark the model after the tool contracts and evaluation set are complete.

## 11. Security and Financial-Safety Controls

- Authenticate every AI endpoint.
- Enforce conversation ownership on every read and write.
- Never expose `OPENAI_API_KEY` to the frontend.
- Rate-limit chat requests per username and IP address.
- Enforce a global request/token budget and emergency kill switch.
- Reject oversized messages and tool arguments.
- Sanitize rendered Markdown and links.
- Redact secrets and sensitive account data from logs.
- Treat tool output and external text as untrusted input.
- Ignore instructions embedded in market data or documents that attempt to redefine system behavior.
- Include a disclaimer that the assistant is educational and not investment advice.
- Refuse requests for personalized trading decisions; offer data summaries or scenario analysis instead.
- Do not allow the model to call trading, alert-modification, or risk-control operations.
- Record provider, model, prompt version, latency, and token usage.

## 12. Observability and Cost Control

Add Micrometer metrics:

- `currency_analyzer_ai_requests_total`
- `currency_analyzer_ai_request_duration_seconds`
- `currency_analyzer_ai_tokens_total`
- `currency_analyzer_ai_tool_calls_total`
- `currency_analyzer_ai_failures_total`
- `currency_analyzer_ai_rate_limited_total`

Operational requirements:

- Track token usage by user, conversation, and model.
- Enforce daily and monthly budgets.
- Alert when provider error or rate-limit rates increase.
- Support immediate disablement through `AI_ASSISTANT_ENABLED=false`.
- Display request limits and usage to users.

Exact monetary cost depends on the selected provider, model, and current pricing. It should be calculated through a configurable price table and reviewed against current official pricing before enabling production access.

## 13. Testing and Evaluation

### Unit tests

- Tool argument validation.
- Conversation ownership.
- Rate limiting and budget enforcement.
- Prompt assembly and response sanitization.
- SSE event framing.

### Integration tests

- PostgreSQL persistence with Testcontainers.
- Mock upstream AI provider responses.
- Tool-call and multi-step response flows.
- Cancellation, timeout, malformed provider response, and provider outage.
- Conversation deletion and retention.

### AI quality evaluation

Maintain a versioned evaluation set containing:

- Correct alert explanations.
- Historical comparison questions.
- Questions requiring a tool call.
- Questions that should refuse personalized advice.
- Prompt-injection attempts.
- Requests for nonexistent pairs or unsupported time ranges.
- Provider responses containing malformed or misleading content.

Every release should measure:

- Tool selection accuracy.
- Factual consistency with application data.
- Unsupported-claim rate.
- Financial-advice refusal correctness.
- Average latency and token usage.
- Cost per successful conversation.

## 14. Delivery Plan and Estimate

### Phase 0: Contracts and safety baseline

**Estimate:** 1–2 days

- Define conversation, message, tool, and usage contracts.
- Select provider client and pin versions.
- Draft system prompt and refusal policy.
- Create evaluation questions.

### Phase 1: Backend and persistence

**Estimate:** 2–3 days

- Add Flyway tables and repositories.
- Add server-side provider configuration.
- Implement conversation ownership and quotas.
- Add provider interface and mocked implementation.

### Phase 2: Streaming chat

**Estimate:** 2–3 days

- Add SSE streaming endpoint and cancellation.
- Add safe Markdown chat drawer to the frontend.
- Add loading, retry, error, and provider-unavailable states.

### Phase 3: Application tools

**Estimate:** 4–6 days

- Implement read-only live-rate, history, comparison, alert, backtest, and forecast tools.
- Connect tool results to the orchestration layer.
- Persist tool invocations and usage.

### Phase 4: Production hardening

**Estimate:** 3–5 days

- Add rate limits, token budgets, and metrics.
- Complete security and prompt-injection tests.
- Build the AI quality evaluation set and CI reporting.
- Validate retention and deletion behavior.
- Measure latency and cost with representative conversations.

### Total estimates

| Deliverable | Estimate |
|---|---:|
| Minimal technology demonstration | 3–5 developer-days |
| Recommended grounded, read-only MVP | **10–15 developer-days** |
| Production hardening | Additional 3–5 developer-days |

These estimates assume the market-data, alert, backtest, and forecast APIs already exist in the Java service. Those APIs are still pending in the migration plan. If they are added within the same scope, allow another **7–10 developer-days**.

## 15. Alternative AI Modules

| Module | User value | Estimate | Risk |
|---|---|---:|---|
| Alert explanation assistant | Explains triggered alerts | 3–5 days | Low |
| Daily AI market briefing | Summarizes tracked pairs and alerts | 5–7 days | Medium |
| Natural-language backtest builder | Converts validated rules into existing backtest requests | 8–12 days | Medium/High |
| Document-grounded knowledge assistant | Answers questions about project documentation | 8–12 days | Medium |
| Local-model assistant using Ollama | Avoids external API calls and may reduce privacy concerns | 8–12 days | High operations/model-quality cost |
| Action-taking assistant | Modifies alerts or risk settings | 3–5 weeks | High |

## 16. Recommended Scope

Build the read-only grounded assistant in two releases:

1. Streaming chat with authentication, persistence, quotas, and safe rendering.
2. Typed market, alert, backtest, and forecast tools with evaluation coverage.

Keep all AI tools read-only until authorization, auditing, confirmation, and rollback behavior are designed and tested. The model must not become a bypass around the application’s existing services or security controls.

## 17. Acceptance Criteria

The first production release is complete when:

- Only authenticated users can access AI endpoints.
- Users can access only their own conversations.
- Responses stream and can be cancelled.
- Every factual market value comes from a registered tool.
- Tool results show source and timestamp.
- The assistant refuses personalized investment advice.
- Prompt-injection tests and unsupported-pair tests pass.
- Daily per-user request and token limits are enforced.
- Provider failures do not affect non-AI application features.
- Conversations and tool calls are auditable.
- Metrics, alerting, deletion, and retention policies are operational.
- Prompt, model, and tool versions are recorded with each response.

## 18. Default Decisions

Unless changed during review:

1. Use OpenAI as the first provider behind an internal `AiProvider` abstraction.
2. Keep the model identifier and API key in environment configuration.
3. Use Spring MVC streaming responses rather than WebFlux or WebSockets.
4. Store application conversation history in PostgreSQL.
5. Use only read-only tools in the first release.
6. Do not enable generic web search or arbitrary code execution.
7. Keep the assistant feature disabled until the provider secret and cost budget are configured.
8. Reuse the existing login session instead of introducing a separate AI authentication system.

## 19. References

- [OpenAI Responses API overview](https://developers.openai.com/api/reference/responses/overview)
- [OpenAI function calling guide](https://developers.openai.com/docs/guides/function-calling?api-mode=responses)
- [OpenAI conversation state guide](https://developers.openai.com/docs/guides/conversation-state?api-mode=responses)
- [OpenAI tools guide](https://developers.openai.com/docs/guides/tools?api-mode=responses)
