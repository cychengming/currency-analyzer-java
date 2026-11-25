package com.currencyanalyzer.ai.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces compact, non-sensitive JSON summaries for {@code ai_tool_invocations.result_summary}
 * so audit rows capture structured outcomes without storing raw excerpts or full price arrays.
 */
public final class ToolResultSummarizer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_SUMMARY_CHARS = 500;
    private static final int MAX_ERROR_CHARS = 160;
    private static final List<String> SCALAR_FIELDS = List.of(
        "pair",
        "as_of",
        "days",
        "direction",
        "entry_source",
        "created",
        "updated",
        "count",
        "point_count",
        "unread_count",
        "exceeds_daily_risk_limit"
    );

    private ToolResultSummarizer() {
    }

    public static String summarize(String toolName, String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return encodeFallback("error", "Empty tool result");
        }
        JsonNode root = parseObject(rawResult);
        if (root == null) {
            return encodeFallback("ok", null);
        }
        if (root.hasNonNull("error")) {
            return encodeFallback("error", truncate(root.path("error").asText(""), MAX_ERROR_CHARS));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "ok");
        if (toolName != null && !toolName.isBlank()) {
            summary.put("tool", toolName.trim());
        }
        copyScalarFields(root, summary);
        addNestedSummaries(root, summary);
        return encodeBounded(summary);
    }

    private static void copyScalarFields(JsonNode root, Map<String, Object> summary) {
        for (String field : SCALAR_FIELDS) {
            JsonNode value = root.get(field);
            if (value == null || value.isNull() || !value.isValueNode()) {
                continue;
            }
            if (value.isBoolean()) {
                summary.put(field, value.booleanValue());
            } else if (value.isNumber()) {
                summary.put(field, value.numberValue());
            } else {
                String text = value.asText("");
                if (!text.isEmpty()) {
                    summary.put(field, truncate(text, 64));
                }
            }
        }
    }

    private static void addNestedSummaries(JsonNode root, Map<String, Object> summary) {
        JsonNode rates = root.get("rates");
        if (rates != null && rates.isObject()) {
            summary.put("pair_count", rates.size());
        }
        JsonNode rule = root.get("rule");
        if (rule != null && rule.isObject()) {
            putTextIfPresent(rule, "id", summary, "rule_id");
            putTextIfPresent(rule, "pair", summary, "pair");
            putTextIfPresent(rule, "condition_type", summary, "condition_type");
            putTextIfPresent(rule, "status", summary, "rule_status");
        }
        JsonNode comparison = root.get("comparison");
        if (comparison != null && comparison.isObject()) {
            JsonNode overlapping = comparison.get("overlapping_dates");
            if (overlapping != null && overlapping.isNumber()) {
                summary.put("overlapping_dates", overlapping.numberValue());
            }
            JsonNode correlation = comparison.get("pearson_correlation");
            if (correlation != null && correlation.isNumber()) {
                summary.put("pearson_correlation", correlation.numberValue());
            }
        }
    }

    private static void putTextIfPresent(
        JsonNode source,
        String sourceKey,
        Map<String, Object> target,
        String targetKey
    ) {
        JsonNode value = source.get(sourceKey);
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            target.put(targetKey, truncate(value.asText(), 64));
        }
    }

    private static JsonNode parseObject(String rawResult) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(rawResult);
            return node != null && node.isObject() ? node : null;
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static String encodeBounded(Map<String, Object> summary) {
        try {
            String json = OBJECT_MAPPER.writeValueAsString(summary);
            if (json.length() <= MAX_SUMMARY_CHARS) {
                return json;
            }
            return encodeFallback(String.valueOf(summary.getOrDefault("status", "ok")), null);
        } catch (JsonProcessingException exception) {
            return "{\"status\":\"ok\"}";
        }
    }

    private static String encodeFallback(String status, String error) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status);
        if (error != null && !error.isBlank()) {
            map.put("error", truncate(error, MAX_ERROR_CHARS));
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException exception) {
            return "{\"status\":\"" + status + "\"}";
        }
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars);
    }
}
