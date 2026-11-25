package com.currencyanalyzer.ai.chat;

import com.currencyanalyzer.ai.knowledge.KnowledgeRetriever;
import com.currencyanalyzer.ai.provider.AiProvider;
import com.currencyanalyzer.ai.provider.AiProviderException;
import com.currencyanalyzer.ai.springai.SpringAiChatRunner;
import com.currencyanalyzer.ai.tools.AssistantTools;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiUsageAndMetricsTests {

    @Test
    void enforcesUserAndGlobalCostBudgetsAndInFlightQuotaGuards() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
            "jdbc:h2:mem:ai_usage_finops_" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;DB_CLOSE_DELAY=-1",
            "sa",
            "",
            true
        );
        try {
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            AiUsageRepository repo = new AiUsageRepository(jdbc);
            Clock fixedClock = Clock.fixed(Instant.parse("2026-11-25T10:00:00Z"), ZoneOffset.UTC);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            AiMetrics metrics = new AiMetrics(registry);

            AiAssistantProperties props = new AiAssistantProperties();
            props.getUsage().setDailyRequestLimit(2);
            props.getUsage().setDailyTokenLimit(10000);
            props.getUsage().setDailyUserCostLimitUsd(new BigDecimal("0.010000"));
            props.getUsage().setDailyGlobalCostLimitUsd(new BigDecimal("0.015000"));
            props.getUsage().setInputCostPerMillionUsd(new BigDecimal("0.27"));
            props.getUsage().setOutputCostPerMillionUsd(new BigDecimal("1.10"));

            AiUsageService service = new AiUsageService(repo, fixedClock, metrics, props);
            BigDecimal estimated = service.estimateCostUsd(100_000, 10_000);
            assertEquals(new BigDecimal("0.038000"), estimated);

            // In-flight guard: 1 recorded + 1 in-flight reaches request limit 2
            service.record("alice", null, "deepseek-flash", 1000, 500, 1);
            service.ensureWithinDailyLimit("alice", 0);
            AiUsageLimitException inFlightLimit = assertThrows(
                AiUsageLimitException.class,
                () -> service.ensureWithinDailyLimit("alice", 1)
            );
            assertEquals("Daily AI request limit reached", inFlightLimit.getMessage());

            // User cost limit reached ($0.01)
            AiAssistantProperties userCostProps = new AiAssistantProperties();
            userCostProps.getUsage().setDailyUserCostLimitUsd(new BigDecimal("0.010000"));
            AiUsageService userCostService = new AiUsageService(repo, fixedClock, metrics, userCostProps);
            repo.record("alice", null, "deepseek-flash", 1000, 1000, 1, new BigDecimal("0.010000"), fixedClock.instant());
            AiUsageLimitException userCost = assertThrows(
                AiUsageLimitException.class,
                () -> userCostService.ensureWithinDailyLimit("alice", 0)
            );
            assertEquals("Daily AI cost limit reached", userCost.getMessage());

            // Global cost limit reached ($0.015) blocks even a fresh user bob
            AiAssistantProperties globalCostProps = new AiAssistantProperties();
            globalCostProps.getUsage().setDailyGlobalCostLimitUsd(new BigDecimal("0.015000"));
            AiUsageService globalCostService = new AiUsageService(repo, fixedClock, metrics, globalCostProps);
            repo.record("charlie", null, "deepseek-flash", 1000, 1000, 1, new BigDecimal("0.006000"), fixedClock.instant());
            AiUsageLimitException globalCost = assertThrows(
                AiUsageLimitException.class,
                () -> globalCostService.ensureWithinDailyLimit("bob", 0)
            );
            assertEquals("Global daily AI cost budget reached", globalCost.getMessage());

            assertEquals(1.0, registry.get("currency_analyzer_ai_rate_limited_total").tag("reason", "user_requests").counter().count());
            assertEquals(1.0, registry.get("currency_analyzer_ai_rate_limited_total").tag("reason", "user_cost").counter().count());
            assertEquals(1.0, registry.get("currency_analyzer_ai_rate_limited_total").tag("reason", "global_cost").counter().count());
            assertTrue(registry.get("currency_analyzer_ai_tokens_total").tag("direction", "input").counter().count() >= 1000);
            assertTrue(registry.get("currency_analyzer_ai_estimated_cost_usd_total").counter().count() > 0.0);
        } finally {
            dataSource.destroy();
        }
    }

    @Test
    void enforcesPerUserAndGlobalConcurrentStreamLimitsAndReleasesSlotsOnFinish() throws Exception {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
            "jdbc:h2:mem:ai_concurrency_" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;DB_CLOSE_DELAY=-1",
            "sa",
            "",
            true
        );
        try {
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            AiConversationRepository convRepo = new AiConversationRepository(jdbc);
            AiUsageRepository usageRepo = new AiUsageRepository(jdbc);
            Clock fixedClock = Clock.fixed(Instant.parse("2026-11-25T10:00:00Z"), ZoneOffset.UTC);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            AiMetrics metrics = new AiMetrics(registry);

            AiAssistantProperties props = new AiAssistantProperties();
            props.setMaxConcurrentPerUser(1);
            props.setMaxConcurrentGlobal(2);

            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AiProvider blockingProvider = new AiProvider() {
                @Override
                public void stream(List<Message> messages, java.util.function.Consumer<String> onText,
                                   java.util.function.Consumer<String> onReasoning,
                                   java.util.function.BooleanSupplier isCancelled) {
                    // unused
                }

                @Override
                public Completion complete(List<Turn> messages, List<ToolDefinition> tools,
                                           java.util.function.BooleanSupplier isCancelled) {
                    entered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    return new Completion("Done.", null, List.of(), new ModelUsage(10, 5), "resp-conc-1");
                }
            };

            SpringAiChatRunner disabledRunner = mock(SpringAiChatRunner.class);
            when(disabledRunner.available()).thenReturn(false);
            AiUsageService usageService = new AiUsageService(usageRepo, fixedClock, metrics, props);
            AiConversationService service = new AiConversationService(
                convRepo,
                blockingProvider,
                mock(AssistantTools.class),
                disabledRunner,
                mock(KnowledgeRetriever.class),
                usageService,
                new AiRuntimeSupport(fixedClock, metrics, props)
            );

            UUID aliceConv1 = service.create("alice").id();
            UUID aliceConv2 = service.create("alice").id();
            UUID bobConv = service.create("bob").id();
            UUID carolConv = service.create("carol").id();

            assertTrue(service.streamMessage("alice", aliceConv1, "Hello 1", new SseEmitter(10_000L)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            // Second concurrent stream for alice rejected (maxConcurrentPerUser = 1)
            AiResponseInProgressException perUser = assertThrows(
                AiResponseInProgressException.class,
                () -> service.streamMessage("alice", aliceConv2, "Hello 2", new SseEmitter(10_000L))
            );
            assertTrue(perUser.getMessage().contains("Too many concurrent"));

            // Bob takes the 2nd global slot; Carol exceeds maxConcurrentGlobal = 2
            assertTrue(service.streamMessage("bob", bobConv, "Hello bob", new SseEmitter(10_000L)));
            AiUsageLimitException globalCap = assertThrows(
                AiUsageLimitException.class,
                () -> service.streamMessage("carol", carolConv, "Hello carol", new SseEmitter(10_000L))
            );
            assertTrue(globalCap.getMessage().contains("temporarily at capacity"));

            release.countDown();
            // Poll briefly until background virtual threads finish and release slots
            long deadline = System.currentTimeMillis() + 5000;
            while (registry.get("currency_analyzer_ai_active_streams").gauge().value() > 0
                && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }
            assertEquals(0.0, registry.get("currency_analyzer_ai_active_streams").gauge().value());
            assertTrue(service.streamMessage("alice", aliceConv2, "Hello after release", new SseEmitter(10_000L)));
        } finally {
            dataSource.destroy();
        }
    }

    @Test
    void recordsPrometheusToolTurnAndDsmlMetricsAndClampsProperties() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiMetrics metrics = new AiMetrics(registry);
        metrics.recordFirstToken("deepseek-flash", 15_000_000L);
        metrics.recordTurn("deepseek-flash", "completed", 120_000_000L);
        metrics.recordToolCall("analyze_pair_technicals", "ok", 42);
        metrics.recordDsmlRecovery("deepseek-flash", 2);
        metrics.recordDsmlRecovery("deepseek-flash", 0);
        metrics.recordRateLimited("");

        assertEquals(1.0, registry.get("currency_analyzer_ai_requests_total").tag("outcome", "completed").counter().count());
        assertEquals(1L, registry.get("currency_analyzer_ai_first_token_seconds").timer().count());
        assertEquals(1.0, registry.get("currency_analyzer_ai_tool_calls_total").tag("tool", "analyze_pair_technicals").counter().count());
        assertEquals(2.0, registry.get("currency_analyzer_ai_dsml_recoveries_total").counter().count());
        assertEquals(1.0, registry.get("currency_analyzer_ai_rate_limited_total").tag("reason", "unknown").counter().count());

        AiAssistantProperties props = new AiAssistantProperties();
        props.setProvider(" ");
        props.setModel(null);
        props.setMaxInputChars(0);
        props.setMaxConcurrentPerUser(0);
        props.setMaxConcurrentGlobal(-5);
        props.setKnowledge(null);
        props.setUsage(null);
        props.getKnowledge().setEnabled(false);
        props.getKnowledge().setMaxNotes(0);
        props.getKnowledge().setMaxChars(10);
        props.getUsage().setDailyRequestLimit(-1);
        props.getUsage().setDailyTokenLimit(-1);
        props.getUsage().setDailyUserCostLimitUsd(new BigDecimal("-1"));
        props.getUsage().setDailyGlobalCostLimitUsd(null);

        assertEquals("deepseek", props.getProvider());
        assertEquals("deepseek-flash", props.getModel());
        assertEquals(1, props.getMaxInputChars());
        assertEquals(1, props.getMaxConcurrentPerUser());
        assertEquals(1, props.getMaxConcurrentGlobal());
        assertFalse(props.getKnowledge().isEnabled());
        assertEquals(1, props.getKnowledge().getMaxNotes());
        assertEquals(80, props.getKnowledge().getMaxChars());
        assertEquals(BigDecimal.ZERO, props.getUsage().getDailyUserCostLimitUsd());
        assertEquals(BigDecimal.ZERO, props.getUsage().getDailyGlobalCostLimitUsd());

        AiUsageRepository.UsageTotals legacyTotals = new AiUsageRepository.UsageTotals(3, 50);
        assertEquals(BigDecimal.ZERO, legacyTotals.costUsd());
        assertTrue(new AiResponseInProgressException().getMessage().contains("already in progress"));
    }

    @Test
    void wiresSpringAiStreamingCallbacksAuditMetadataAndErrorMetrics() throws Exception {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
            "jdbc:h2:mem:ai_springai_service_" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;DB_CLOSE_DELAY=-1",
            "sa",
            "",
            true
        );
        try {
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            AiConversationRepository convRepo = new AiConversationRepository(jdbc);
            AiUsageRepository usageRepo = new AiUsageRepository(jdbc);
            Clock fixedClock = Clock.fixed(Instant.parse("2026-11-25T10:00:00Z"), ZoneOffset.UTC);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            AiMetrics metrics = new AiMetrics(registry);
            AiAssistantProperties props = new AiAssistantProperties();
            props.getUsage().setDailyRequestLimit(2);

            SpringAiChatRunner springAiRunner = mock(SpringAiChatRunner.class);
            when(springAiRunner.available()).thenReturn(true);
            doAnswer(invocation -> {
                SpringAiChatRunner.StreamListener listener = invocation.getArgument(2);
                listener.onReasoning("Thinking...");
                listener.onToolStarted("get_live_rates");
                listener.onToolFinished("get_live_rates", "{}", "{\"as_of\":\"2026-11-25T10:00:00Z\",\"rates\":{\"EUR/USD\":{\"rate\":1.1}}}", 12);
                listener.onUsage(50, 20);
                listener.onResponseId("resp-spring-1");
                listener.onText("EUR/USD is 1.1.");
                return null;
            }).doAnswer(invocation -> {
                SpringAiChatRunner.StreamListener listener = invocation.getArgument(2);
                listener.onUsage(10, 0);
                throw new AiProviderException("Provider timeout");
            }).when(springAiRunner).answer(anyString(), anyList(), any(SpringAiChatRunner.StreamListener.class), any(BooleanSupplier.class));

            AiUsageService usageService = new AiUsageService(usageRepo, fixedClock, metrics, props);
            AiConversationService service = new AiConversationService(
                convRepo,
                mock(AiProvider.class),
                mock(AssistantTools.class),
                springAiRunner,
                mock(KnowledgeRetriever.class),
                usageService,
                new AiRuntimeSupport(fixedClock, metrics, props)
            );

            UUID convId = service.create("alice").id();
            assertTrue(service.streamMessage("alice", convId, "Check EUR/USD", new SseEmitter(10_000L)));
            waitUntilIdle(registry);

            assertEquals(1.0, registry.get("currency_analyzer_ai_requests_total").tag("outcome", "completed").counter().count());
            assertEquals(1L, registry.get("currency_analyzer_ai_first_token_seconds").timer().count());
            assertEquals(1.0, registry.get("currency_analyzer_ai_tool_calls_total").tag("tool", "get_live_rates").counter().count());
            String storedRespId = jdbc.queryForObject(
                "SELECT provider_response_id FROM ai_messages WHERE conversation_id = CAST(? AS UUID) AND role = 'assistant'",
                String.class,
                convId.toString()
            );
            assertEquals("resp-spring-1", storedRespId);

            // Second call triggers provider failure after reporting prompt usage -> recorded under outcome=error and releases slots
            assertTrue(service.streamMessage("alice", convId, "Trigger error", new SseEmitter(10_000L)));
            waitUntilIdle(registry);
            assertEquals(1.0, registry.get("currency_analyzer_ai_requests_total").tag("outcome", "error").counter().count());

            // Third call hits daily request limit (2) inside try-block and must still release concurrency slots
            assertThrows(
                AiUsageLimitException.class,
                () -> service.streamMessage("alice", convId, "Over quota", new SseEmitter(10_000L))
            );
            assertEquals(0.0, registry.get("currency_analyzer_ai_active_streams").gauge().value());
        } finally {
            dataSource.destroy();
        }
    }

    private static void waitUntilIdle(SimpleMeterRegistry registry) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (registry.get("currency_analyzer_ai_active_streams").gauge().value() > 0
            && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(30);
    }
}
