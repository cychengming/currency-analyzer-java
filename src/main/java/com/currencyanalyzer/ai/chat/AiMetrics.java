package com.currencyanalyzer.ai.chat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

@Component
public class AiMetrics {

    private static final String TAG_MODEL = "model";
    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_STATUS = "status";

    private final MeterRegistry registry;

    @Autowired
    public AiMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this(registryProvider.getIfAvailable(SimpleMeterRegistry::new));
    }

    public AiMetrics(MeterRegistry registry) {
        this.registry = registry == null ? new SimpleMeterRegistry() : registry;
    }

    public static AiMetrics noop() {
        return new AiMetrics(new SimpleMeterRegistry());
    }

    public MeterRegistry registry() {
        return registry;
    }

    public void registerActiveStreamsGauge(ToDoubleFunction<Object> valueFn, Object state) {
        Gauge.builder("currency_analyzer_ai_active_streams", state, valueFn)
            .description("Currently active AI streaming responses")
            .register(registry);
    }

    public void recordTurn(String model, String outcome, long durationNanos) {
        String safeModel = sanitizeTag(model, "unknown");
        String safeOutcome = sanitizeTag(outcome, "unknown");
        Counter.builder("currency_analyzer_ai_requests_total")
            .tag(TAG_MODEL, safeModel)
            .tag(TAG_OUTCOME, safeOutcome)
            .register(registry)
            .increment();
        Timer.builder("currency_analyzer_ai_turn_duration_seconds")
            .tag(TAG_MODEL, safeModel)
            .tag(TAG_OUTCOME, safeOutcome)
            .register(registry)
            .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
    }

    public void recordFirstToken(String model, long durationNanos) {
        Timer.builder("currency_analyzer_ai_first_token_seconds")
            .tag(TAG_MODEL, sanitizeTag(model, "unknown"))
            .register(registry)
            .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
    }

    public void recordTokensAndCost(String model, int inputTokens, int outputTokens, BigDecimal costUsd) {
        String safeModel = sanitizeTag(model, "unknown");
        if (inputTokens > 0) {
            Counter.builder("currency_analyzer_ai_tokens_total")
                .tag(TAG_MODEL, safeModel)
                .tag("direction", "input")
                .register(registry)
                .increment(inputTokens);
        }
        if (outputTokens > 0) {
            Counter.builder("currency_analyzer_ai_tokens_total")
                .tag(TAG_MODEL, safeModel)
                .tag("direction", "output")
                .register(registry)
                .increment(outputTokens);
        }
        if (costUsd != null && costUsd.signum() > 0) {
            Counter.builder("currency_analyzer_ai_estimated_cost_usd_total")
                .tag(TAG_MODEL, safeModel)
                .register(registry)
                .increment(costUsd.doubleValue());
        }
    }

    public void recordToolCall(String toolName, String status, int durationMs) {
        String safeTool = sanitizeTag(toolName, "unknown");
        String safeStatus = sanitizeTag(status, "unknown");
        Counter.builder("currency_analyzer_ai_tool_calls_total")
            .tag("tool", safeTool)
            .tag(TAG_STATUS, safeStatus)
            .register(registry)
            .increment();
        Timer.builder("currency_analyzer_ai_tool_duration_seconds")
            .tag("tool", safeTool)
            .tag(TAG_STATUS, safeStatus)
            .register(registry)
            .record(Math.max(0, durationMs), TimeUnit.MILLISECONDS);
    }

    public void recordDsmlRecovery(String model, int count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("currency_analyzer_ai_dsml_recoveries_total")
            .tag(TAG_MODEL, sanitizeTag(model, "unknown"))
            .register(registry)
            .increment(count);
    }

    public void recordRateLimited(String reason) {
        Counter.builder("currency_analyzer_ai_rate_limited_total")
            .tag("reason", sanitizeTag(reason, "unknown"))
            .register(registry)
            .increment();
    }

    public void recordRagRetrieval(String mode, int hitCount, long durationNanos) {
        String safeMode = sanitizeTag(mode, "unknown");
        Timer.builder("currency_analyzer_rag_retrieval_seconds")
            .tag("mode", safeMode)
            .register(registry)
            .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
        DistributionSummary.builder("currency_analyzer_rag_hits")
            .tag("mode", safeMode)
            .register(registry)
            .record(Math.max(0, hitCount));
        if (hitCount <= 0 && !"skipped".equals(safeMode)) {
            Counter.builder("currency_analyzer_rag_empty_total")
                .tag("mode", safeMode)
                .register(registry)
                .increment();
        }
    }

    public void recordRagEmbedding(String outcome, long durationNanos) {
        String safeOutcome = sanitizeTag(outcome, "unknown");
        Counter.builder("currency_analyzer_rag_embedding_total")
            .tag(TAG_OUTCOME, safeOutcome)
            .register(registry)
            .increment();
        Timer.builder("currency_analyzer_rag_embedding_seconds")
            .tag(TAG_OUTCOME, safeOutcome)
            .register(registry)
            .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
    }

    private static String sanitizeTag(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
