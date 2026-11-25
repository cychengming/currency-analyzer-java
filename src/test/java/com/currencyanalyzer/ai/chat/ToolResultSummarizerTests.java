package com.currencyanalyzer.ai.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultSummarizerTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void summarizesLiveRatesAlertRulesComparisonAndPositionSizingWithoutRawPayloads() throws Exception {
        String liveJson = ToolResultSummarizer.summarize(
            "get_live_rates",
            "{\"as_of\":\"2026-11-25T10:00:00Z\",\"rates\":{\"EUR/USD\":{\"rate\":1.10},\"GOLD/USD\":{\"rate\":2650.0}}}"
        );
        JsonNode live = objectMapper.readTree(liveJson);
        assertEquals("ok", live.path("status").asText());
        assertEquals("get_live_rates", live.path("tool").asText());
        assertEquals("2026-11-25T10:00:00Z", live.path("as_of").asText());
        assertEquals(2, live.path("pair_count").asInt());
        assertTrue(live.path("rates").isMissingNode());

        String createdJson = ToolResultSummarizer.summarize(
            "create_alert_rule",
            "{\"created\":true,\"rule\":{\"id\":\"11111111-1111-1111-1111-111111111111\",\"pair\":\"GOLD/USD\",\"condition_type\":\"cross_above\",\"status\":\"ARMED\"}}"
        );
        JsonNode created = objectMapper.readTree(createdJson);
        assertEquals("ok", created.path("status").asText());
        assertTrue(created.path("created").asBoolean());
        assertEquals("GOLD/USD", created.path("pair").asText());
        assertEquals("cross_above", created.path("condition_type").asText());
        assertEquals("ARMED", created.path("rule_status").asText());

        String compareJson = ToolResultSummarizer.summarize(
            "compare_pairs",
            "{\"days\":60,\"comparison\":{\"overlapping_dates\":58,\"pearson_correlation\":0.8421}}"
        );
        JsonNode compare = objectMapper.readTree(compareJson);
        assertEquals(60, compare.path("days").asInt());
        assertEquals(58, compare.path("overlapping_dates").asInt());
        assertEquals(0.8421, compare.path("pearson_correlation").asDouble(), 1e-6);

        String sizingJson = ToolResultSummarizer.summarize(
            "calculate_position_size",
            "{\"pair\":\"EUR/USD\",\"direction\":\"long\",\"entry_source\":\"live_spot\",\"exceeds_daily_risk_limit\":false}"
        );
        JsonNode sizing = objectMapper.readTree(sizingJson);
        assertEquals("long", sizing.path("direction").asText());
        assertEquals("live_spot", sizing.path("entry_source").asText());
        assertFalse(sizing.path("exceeds_daily_risk_limit").asBoolean());
    }

    @Test
    void summarizesErrorsBlankInputsAndNonJsonPayloadsSafely() throws Exception {
        String errorJson = ToolResultSummarizer.summarize(
            "update_alert_rule_status",
            "{\"error\":\"Alert rule not found.\"}"
        );
        JsonNode errorNode = objectMapper.readTree(errorJson);
        assertEquals("error", errorNode.path("status").asText());
        assertEquals("Alert rule not found.", errorNode.path("error").asText());

        assertEquals("error", objectMapper.readTree(ToolResultSummarizer.summarize("x", "")).path("status").asText());
        assertEquals("error", objectMapper.readTree(ToolResultSummarizer.summarize("x", null)).path("status").asText());
        assertEquals("ok", objectMapper.readTree(ToolResultSummarizer.summarize("x", "not-json")).path("status").asText());
        assertEquals("ok", objectMapper.readTree(ToolResultSummarizer.summarize("x", "[]")).path("status").asText());

        String oversizedField = ToolResultSummarizer.summarize(
            "x",
            "{\"pair\":\"" + "A".repeat(600) + "\"}"
        );
        assertTrue(oversizedField.length() <= 500);
    }
}
