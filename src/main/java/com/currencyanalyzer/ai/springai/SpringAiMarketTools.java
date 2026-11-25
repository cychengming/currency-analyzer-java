package com.currencyanalyzer.ai.springai;

import com.currencyanalyzer.ai.tools.AssistantTools;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SpringAiMarketTools {

    private final AssistantTools assistantTools;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SpringAiMarketTools(AssistantTools assistantTools) {
        this.assistantTools = assistantTools;
    }

    @Tool(name = AssistantTools.GET_LIVE_RATES, description = AssistantTools.GET_LIVE_RATES_DESCRIPTION)
    public String getLiveRates() {
        return invoke(AssistantTools.GET_LIVE_RATES, Map.of());
    }

    @Tool(name = AssistantTools.GET_HISTORICAL_SERIES, description = AssistantTools.GET_HISTORICAL_SERIES_DESCRIPTION)
    public String getHistoricalSeries(
        @ToolParam(description = "Pair such as EUR/USD or GOLD/USD") String pair,
        @ToolParam(description = "Number of recent daily closes, from 1 to 365") int days
    ) {
        return invoke(AssistantTools.GET_HISTORICAL_SERIES, Map.of("pair", pair, "days", days));
    }

    @Tool(name = AssistantTools.COMPARE_PAIRS, description = AssistantTools.COMPARE_PAIRS_DESCRIPTION)
    public String comparePairs(
        @ToolParam(description = "First pair, such as EUR/USD") String first,
        @ToolParam(description = "Second pair, such as GBP/USD") String second,
        @ToolParam(description = "Number of recent daily closes, from 1 to 365") int days
    ) {
        return invoke(AssistantTools.COMPARE_PAIRS, Map.of("first", first, "second", second, "days", days));
    }

    @Tool(name = AssistantTools.GET_ALERT_HISTORY, description = AssistantTools.GET_ALERT_HISTORY_DESCRIPTION)
    public String getAlertHistory(
        @ToolParam(description = "Optional pair filter, such as EUR/USD", required = false) String pair,
        @ToolParam(description = "Maximum alerts to return, from 1 to 20", required = false) Integer limit
    ) {
        return invoke(AssistantTools.GET_ALERT_HISTORY, optional("pair", pair, "limit", limit));
    }

    @Tool(name = AssistantTools.GET_ALERT_PREFERENCES, description = AssistantTools.GET_ALERT_PREFERENCES_DESCRIPTION)
    public String getAlertPreferences(
        @ToolParam(description = "Optional pair filter, such as EUR/USD", required = false) String pair
    ) {
        return invoke(AssistantTools.GET_ALERT_PREFERENCES, optional("pair", pair));
    }

    @Tool(name = AssistantTools.GET_MONITORING_STATE, description = AssistantTools.GET_MONITORING_STATE_DESCRIPTION)
    public String getMonitoringState(
        @ToolParam(description = "Optional pair filter, such as EUR/USD", required = false) String pair
    ) {
        return invoke(AssistantTools.GET_MONITORING_STATE, optional("pair", pair));
    }

    @Tool(name = AssistantTools.GET_SETTINGS, description = AssistantTools.GET_SETTINGS_DESCRIPTION)
    public String getSettings() {
        return invoke(AssistantTools.GET_SETTINGS, Map.of());
    }

    @Tool(name = AssistantTools.GET_LATEST_FORECAST, description = AssistantTools.GET_LATEST_FORECAST_DESCRIPTION)
    public String getLatestForecast(
        @ToolParam(description = "Optional asset, such as EUR/USD or GOLD/USD", required = false) String asset
    ) {
        return invoke(AssistantTools.GET_LATEST_FORECAST, optional("asset", asset));
    }

    @Tool(name = AssistantTools.SEARCH_NOTES, description = AssistantTools.SEARCH_NOTES_DESCRIPTION)
    public String searchNotes(
        @ToolParam(description = "What the note should be about, in the user's own words") String query
    ) {
        return invoke(AssistantTools.SEARCH_NOTES, Map.of("query", query));
    }

    @Tool(name = AssistantTools.LIST_MY_ALERT_RULES, description = AssistantTools.LIST_MY_ALERT_RULES_DESCRIPTION)
    public String listMyAlertRules(
        @ToolParam(description = "Optional pair filter, such as EUR/USD", required = false) String pair,
        @ToolParam(description = "Optional status filter: ARMED, TRIGGERED, or PAUSED", required = false) String status
    ) {
        return invoke(AssistantTools.LIST_MY_ALERT_RULES, optional("pair", pair, "status", status));
    }

    @Tool(name = AssistantTools.GET_MY_ALERT_INBOX, description = AssistantTools.GET_MY_ALERT_INBOX_DESCRIPTION)
    public String getMyAlertInbox(
        @ToolParam(description = "Optional pair filter, such as EUR/USD", required = false) String pair,
        @ToolParam(description = "When true, return only unread alerts", required = false) Boolean unreadOnly,
        @ToolParam(description = "Maximum alerts to return, from 1 to 20", required = false) Integer limit
    ) {
        return invoke(
            AssistantTools.GET_MY_ALERT_INBOX,
            optional("pair", pair, "unread_only", unreadOnly, "limit", limit)
        );
    }

    @Tool(name = AssistantTools.CREATE_ALERT_RULE, description = AssistantTools.CREATE_ALERT_RULE_DESCRIPTION)
    public String createAlertRule(
        @ToolParam(description = "Pair such as EUR/USD or GOLD/USD") String pair,
        @ToolParam(description = "One of: cross_above, cross_below, pct_move, ma_crossover") String conditionType,
        @ToolParam(description = "Target price for cross_above/cross_below, or % move for pct_move", required = false) Double targetValue,
        @ToolParam(description = "Optional short label for the alert rule", required = false) String nickname,
        @ToolParam(description = "Lookback window in days for pct_move (1 to 365)", required = false) Integer windowDays,
        @ToolParam(description = "ONCE (default) or RECURRING", required = false) String frequency,
        @ToolParam(description = "Whether to send email when triggered (default true)", required = false) Boolean notifyEmail
    ) {
        return invoke(
            AssistantTools.CREATE_ALERT_RULE,
            optional(
                "pair", pair,
                "condition_type", conditionType,
                "target_value", targetValue,
                "nickname", nickname,
                "window_days", windowDays,
                "frequency", frequency,
                "notify_email", notifyEmail
            )
        );
    }

    @Tool(name = AssistantTools.UPDATE_ALERT_RULE_STATUS, description = AssistantTools.UPDATE_ALERT_RULE_STATUS_DESCRIPTION)
    public String updateAlertRuleStatus(
        @ToolParam(description = "UUID of the user's alert rule") String ruleId,
        @ToolParam(description = "New status: ARMED or PAUSED") String status
    ) {
        return invoke(AssistantTools.UPDATE_ALERT_RULE_STATUS, optional("rule_id", ruleId, "status", status));
    }

    private String invoke(String name, Map<String, Object> arguments) {
        String argumentsJson = write(arguments);
        ToolActivity.started(name);
        long startedAt = System.nanoTime();
        String result = assistantTools.execute(name, argumentsJson);
        int durationMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedAt) / 1_000_000);
        ToolActivity.finished(name, argumentsJson, result, durationMs);
        return result;
    }

    private Map<String, Object> optional(Object... pairs) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            Object value = pairs[index + 1];
            if (value == null) {
                continue;
            }
            if (value instanceof String text && text.isBlank()) {
                continue;
            }
            arguments.put(String.valueOf(pairs[index]), value);
        }
        return arguments;
    }

    private String write(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }
}
