package com.currencyanalyzer.ai.chat;

import com.currencyanalyzer.CurrencyAnalyzerApplication;
import com.currencyanalyzer.ai.provider.AiProvider;
import com.currencyanalyzer.ai.provider.AiProvider.Completion;
import com.currencyanalyzer.ai.provider.AiProvider.Message;
import com.currencyanalyzer.ai.provider.AiProvider.ToolCall;
import com.currencyanalyzer.ai.provider.AiProvider.ToolDefinition;
import com.currencyanalyzer.ai.provider.AiProvider.Turn;
import com.currencyanalyzer.ai.tools.AssistantTools;
import com.currencyanalyzer.marketdata.LiveRate;
import com.currencyanalyzer.marketdata.MarketDataService;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    classes = CurrencyAnalyzerApplication.class,
    properties = "app.ai.enabled=true"
)
@AutoConfigureMockMvc
@Import(AiConversationControllerTests.FakeProviderConfiguration.class)
class AiConversationControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AssistantTools assistantTools;

    @MockitoBean
    private MarketDataService marketDataService;

    @Test
    void createsListsAndDeletesOnlyOwnedConversations() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/ai/conversations")
                .with(user("alice"))
                .with(csrf()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.created_at").isNotEmpty())
            .andReturn();

        String id = JsonMapper.builder().build()
            .readTree(created.getResponse().getContentAsString())
            .get("id").asText();

        mockMvc.perform(get("/api/ai/conversations").with(user("bob")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(delete("/api/ai/conversations/{id}", id)
                .with(user("bob"))
                .with(csrf()))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/ai/conversations").with(user("alice")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(id));

        mockMvc.perform(delete("/api/ai/conversations/{id}", id)
                .with(user("alice"))
                .with(csrf()))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/ai/conversations").with(user("alice")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void rejectsConversationListLimitsOutsideBounds() throws Exception {
        mockMvc.perform(get("/api/ai/conversations")
                .param("limit", "101")
                .with(user("alice")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("limit must be between 1 and 100"));
    }

    @Test
    void rejectsOversizedMessagesBeforeStartingProviderCall() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/ai/conversations")
                .with(user("limit-user"))
                .with(csrf()))
            .andExpect(status().isCreated())
            .andReturn();
        String id = JsonMapper.builder().build()
            .readTree(created.getResponse().getContentAsString())
            .get("id").asText();
        String oversizedMessage = "x".repeat(8001);

        mockMvc.perform(post("/api/ai/conversations/{id}/messages", id)
                .with(user("limit-user"))
                .with(csrf())
                .contentType("application/json")
                .content(JsonMapper.builder().build().createObjectNode()
                    .put("message", oversizedMessage).toString()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("message exceeds the maximum length of 8000"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/ai/conversations"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void streamsAndPersistsAssistantResponse() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/ai/conversations")
                .with(user("stream-user"))
                .with(csrf()))
            .andExpect(status().isCreated())
            .andReturn();
        String id = JsonMapper.builder().build()
            .readTree(created.getResponse().getContentAsString())
            .get("id").asText();

        MvcResult pendingResponse = mockMvc.perform(post("/api/ai/conversations/{id}/messages", id)
                .with(user("stream-user"))
                .with(csrf())
                .contentType("application/json")
                .accept("text/event-stream")
                .content("{\"message\":\"Explain a moving average\"}"))
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(pendingResponse))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("event:text_delta")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Reply from test provider.")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("event:message_completed")));

        mockMvc.perform(get("/api/ai/conversations/{id}/messages", id).with(user("stream-user")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].role").value("user"))
            .andExpect(jsonPath("$[1].role").value("assistant"))
            .andExpect(jsonPath("$[1].content").value("Reply from test provider."));
    }

    @Test
    void attachesMatchingKnowledgeNotesToTheProviderPrompt() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/ai/conversations")
                .with(user("knowledge-user"))
                .with(csrf()))
            .andExpect(status().isCreated())
            .andReturn();
        String id = JsonMapper.builder().build()
            .readTree(created.getResponse().getContentAsString())
            .get("id").asText();

        MvcResult pendingResponse = mockMvc.perform(post("/api/ai/conversations/{id}/messages", id)
                .with(user("knowledge-user"))
                .with(csrf())
                .contentType("application/json")
                .accept("text/event-stream")
                .content("{\"message\":\"请用凯利公式解释仓位\"}"))
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(pendingResponse))
            .andExpect(status().isOk());

        List<AiProvider.Message> prompt = FakeProviderConfiguration.lastPrompt.get();
        assertEquals("system", prompt.getFirst().role());
        assertTrue(prompt.getFirst().content().contains("## 凯利公式"));
        assertEquals("user", prompt.get(1).role());
        assertEquals("请用凯利公式解释仓位", prompt.get(1).content());
    }

    @Test
    void quotesTheLiveRateReturnedByTheTool() throws Exception {
        when(marketDataService.fetchLiveRates()).thenReturn(Map.of(
            "EUR/USD", new LiveRate(1.0832, 0.0012, 0.11)
        ));
        MvcResult created = mockMvc.perform(post("/api/ai/conversations")
                .with(user("rate-user"))
                .with(csrf()))
            .andExpect(status().isCreated())
            .andReturn();
        String id = JsonMapper.builder().build()
            .readTree(created.getResponse().getContentAsString())
            .get("id").asText();

        MvcResult pendingResponse = mockMvc.perform(post("/api/ai/conversations/{id}/messages", id)
                .with(user("rate-user"))
                .with(csrf())
                .contentType("application/json")
                .accept("text/event-stream")
                .content("{\"message\":\"What is the current EUR/USD rate?\"}"))
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(pendingResponse))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("event:tool_started")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("1.0832")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("event:message_completed")));

        Integer toolCalls = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ai_tool_invocations WHERE tool_name = 'get_live_rates' AND status = 'ok'",
            Integer.class
        );
        assertEquals(1, toolCalls);
    }

    @Test
    void readsRecordedAlertsAndStoredForecasts() {
        jdbcTemplate.update("""
                INSERT INTO alerts (
                    pair, percent_change, old_rate, new_rate, timestamp, email_sent, alert_type
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
            "EUR/USD", 2.5, 1.05, 1.076, OffsetDateTime.of(2026, 11, 20, 8, 0, 0, 0, ZoneOffset.UTC),
            false, "percentage_change"
        );
        jdbcTemplate.update("""
                INSERT INTO dl_forecast_runs (
                    created_at, model_type, lookback_days, horizon_days, asset, notes
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
            "2026-11-20T08:00:00Z", "lstm", 60, 5, "EUR/USD", "test run"
        );
        Long runId = jdbcTemplate.queryForObject("SELECT id FROM dl_forecast_runs WHERE asset = 'EUR/USD'", Long.class);
        jdbcTemplate.update(
            "INSERT INTO asset_forecast_daily (run_id, dt, predicted_close) VALUES (?, ?, ?)",
            runId, "2026-11-21", 1.09
        );

        String alerts = assistantTools.execute("get_alert_history", "{\"pair\":\"EUR/USD\",\"limit\":5}");
        String forecast = assistantTools.execute("get_latest_forecast", "{\"asset\":\"EUR/USD\"}");
        String missing = assistantTools.execute("get_latest_forecast", "{\"asset\":\"GBP/USD\"}");

        assertTrue(alerts.contains("percentage_change"));
        assertTrue(alerts.contains("EUR/USD"));
        assertTrue(forecast.contains("\"available\":true"));
        assertTrue(forecast.contains("1.09"));
        assertTrue(missing.contains("No forecast has been stored"));
    }

    @TestConfiguration
    static class FakeProviderConfiguration {

        static final AtomicReference<List<AiProvider.Message>> lastPrompt = new AtomicReference<>(List.of());

        @Bean
        @Primary
        AiProvider fakeAiProvider() {
            return new AiProvider() {
                @Override
                public void stream(List<Message> messages, java.util.function.Consumer<String> onText,
                                   java.util.function.BooleanSupplier isCancelled) {
                    lastPrompt.set(List.copyOf(messages));
                    if (!isCancelled.getAsBoolean()) {
                        onText.accept("Reply from test provider.");
                    }
                }

                @Override
                public Completion complete(List<Turn> messages, List<ToolDefinition> tools,
                                           java.util.function.BooleanSupplier isCancelled) {
                    String latestUser = "";
                    String toolResult = "";
                    for (Turn message : messages) {
                        if ("user".equals(message.role())) {
                            latestUser = message.content();
                        }
                        if ("tool".equals(message.role())) {
                            toolResult = message.content();
                        }
                    }
                    if (latestUser.contains("current EUR/USD rate") && toolResult.isEmpty()) {
                        return new Completion(null, List.of(new ToolCall("call-1", "get_live_rates", "{}")));
                    }
                    if (!toolResult.isEmpty()) {
                        return new Completion("From the live feed: " + toolResult, List.of());
                    }
                    return Completion.NONE;
                }
            };
        }
    }
}
