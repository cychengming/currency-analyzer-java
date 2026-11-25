package com.currencyanalyzer.ai.tools;

import com.currencyanalyzer.ai.knowledge.KnowledgeNote;
import com.currencyanalyzer.ai.knowledge.KnowledgeRetriever;
import com.currencyanalyzer.ai.provider.AiProvider.ToolDefinition;
import com.currencyanalyzer.alerts.UserAlertRuleService;
import com.currencyanalyzer.alerts.UserAlertRuleService.AlertInboxResponse;
import com.currencyanalyzer.alerts.UserAlertRuleService.AlertRuleView;
import com.currencyanalyzer.alerts.UserAlertRuleService.RuleRequest;
import com.currencyanalyzer.alerts.UserAlertRuleService.UserAlertProfile;
import com.currencyanalyzer.auth.UserAccount;
import com.currencyanalyzer.auth.UserRepository;
import com.currencyanalyzer.marketdata.HistoricalPoint;
import com.currencyanalyzer.marketdata.LiveRate;
import com.currencyanalyzer.marketdata.MarketDataService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class AssistantTools {

    private static final Logger log = LoggerFactory.getLogger(AssistantTools.class);

    public static final String GET_LIVE_RATES = "get_live_rates";
    public static final String GET_HISTORICAL_SERIES = "get_historical_series";
    public static final String COMPARE_PAIRS = "compare_pairs";
    public static final String GET_ALERT_HISTORY = "get_alert_history";
    public static final String GET_ALERT_PREFERENCES = "get_alert_preferences";
    public static final String GET_MONITORING_STATE = "get_monitoring_state";
    public static final String GET_SETTINGS = "get_settings";
    public static final String GET_LATEST_FORECAST = "get_latest_forecast";
    public static final String SEARCH_NOTES = "search_notes";
    public static final String LIST_MY_ALERT_RULES = "list_my_alert_rules";
    public static final String GET_MY_ALERT_INBOX = "get_my_alert_inbox";
    public static final String CREATE_ALERT_RULE = "create_alert_rule";
    public static final String UPDATE_ALERT_RULE_STATUS = "update_alert_rule_status";

    public static final String GET_LIVE_RATES_DESCRIPTION =
        "Read the current dashboard quotes for the configured pairs. Values come from the application's cached feed.";
    public static final String GET_HISTORICAL_SERIES_DESCRIPTION =
        "Read daily closes for one pair. Days must be from 1 to 365. The result includes the change over that window and a sampled series.";
    public static final String COMPARE_PAIRS_DESCRIPTION =
        "Compare the change over the same daily window for two pairs. Days must be from 1 to 365.";
    public static final String GET_ALERT_HISTORY_DESCRIPTION =
        "Read alerts this application has already recorded. Omit pair to read the latest alerts across pairs.";
    public static final String GET_ALERT_PREFERENCES_DESCRIPTION =
        "Read legacy pair alert preferences. For the signed-in user's personal Alerts 2.0 rules, prefer list_my_alert_rules.";
    public static final String GET_MONITORING_STATE_DESCRIPTION =
        "Read the last time each pair raised an alert. Omit pair to list every monitored pair.";
    public static final String GET_SETTINGS_DESCRIPTION =
        "Read stored application settings. Passwords, tokens, and API keys are omitted.";
    public static final String GET_LATEST_FORECAST_DESCRIPTION =
        "Read the newest stored model forecast. This is a saved path, not a live price and not a promise of a future price.";
    public static final String SEARCH_NOTES_DESCRIPTION =
        "Search the investment notes by meaning. Use this for a method, a risk rule, or an investor.";
    public static final String LIST_MY_ALERT_RULES_DESCRIPTION =
        "List the signed-in user's personal Alerts 2.0 rules with live spot rate, distance to target, status, and favorite pairs.";
    public static final String GET_MY_ALERT_INBOX_DESCRIPTION =
        "Read the signed-in user's personal triggered alert inbox, unread count, favorite pairs, and alert email setting.";
    public static final String CREATE_ALERT_RULE_DESCRIPTION =
        "Create a new personal Alerts 2.0 rule for the signed-in user. condition_type must be cross_above, cross_below, pct_move, or ma_crossover. Call only when the user explicitly asks to create an alert.";
    public static final String UPDATE_ALERT_RULE_STATUS_DESCRIPTION =
        "Pause, resume, or re-arm one of the signed-in user's existing personal alert rules by rule_id. status must be ARMED or PAUSED. Call only when the user explicitly asks to change a rule.";

    static final int MAX_HISTORY_DAYS = 365;
    static final int MAX_SERIES_POINTS = 40;
    static final int MAX_ALERTS = 20;
    static final int MAX_FORECAST_POINTS = 40;
    private static final int MAX_RESULT_CHARS = 6000;
    private static final int MAX_SETTINGS = 40;
    private static final Set<String> VALID_RULE_STATUSES = Set.of("ARMED", "TRIGGERED", "PAUSED");

    private final MarketDataService marketDataService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock;
    private final long liveRateMaxAgeSeconds;
    private final KnowledgeRetriever knowledgeRetriever;
    private final UserAlertRuleService userAlertRuleService;
    private final UserRepository userRepository;

    @Autowired
    public AssistantTools(
        MarketDataService marketDataService,
        JdbcTemplate jdbcTemplate,
        Clock clock,
        @Value("${app.market.live-cache-ttl-seconds:60}") long liveRateMaxAgeSeconds,
        KnowledgeRetriever knowledgeRetriever,
        UserAlertRuleService userAlertRuleService,
        UserRepository userRepository
    ) {
        this.marketDataService = marketDataService;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.liveRateMaxAgeSeconds = liveRateMaxAgeSeconds;
        this.knowledgeRetriever = knowledgeRetriever;
        this.userAlertRuleService = userAlertRuleService;
        this.userRepository = userRepository;
    }

    public AssistantTools(
        MarketDataService marketDataService,
        JdbcTemplate jdbcTemplate,
        Clock clock,
        long liveRateMaxAgeSeconds,
        KnowledgeRetriever knowledgeRetriever
    ) {
        this(marketDataService, jdbcTemplate, clock, liveRateMaxAgeSeconds, knowledgeRetriever, null, null);
    }

    public List<ToolDefinition> definitions() {
        return List.of(
            new ToolDefinition(GET_LIVE_RATES, GET_LIVE_RATES_DESCRIPTION, schema(Map.of(), List.of())),
            new ToolDefinition(GET_HISTORICAL_SERIES, GET_HISTORICAL_SERIES_DESCRIPTION, schema(Map.of(
                "pair", stringProp("Pair such as EUR/USD or GOLD/USD"),
                "days", integerProp("Number of recent daily closes", 1, MAX_HISTORY_DAYS)
            ), List.of("pair", "days"))),
            new ToolDefinition(COMPARE_PAIRS, COMPARE_PAIRS_DESCRIPTION, schema(Map.of(
                "first", stringProp("First pair, such as EUR/USD"),
                "second", stringProp("Second pair, such as GBP/USD"),
                "days", integerProp("Number of recent daily closes", 1, MAX_HISTORY_DAYS)
            ), List.of("first", "second", "days"))),
            new ToolDefinition(GET_ALERT_HISTORY, GET_ALERT_HISTORY_DESCRIPTION, schema(Map.of(
                "pair", stringProp("Optional pair filter, such as EUR/USD"),
                "limit", integerProp("Maximum alerts to return", 1, MAX_ALERTS)
            ), List.of())),
            new ToolDefinition(GET_ALERT_PREFERENCES, GET_ALERT_PREFERENCES_DESCRIPTION, schema(Map.of(
                "pair", stringProp("Optional pair filter, such as EUR/USD")
            ), List.of())),
            new ToolDefinition(GET_MONITORING_STATE, GET_MONITORING_STATE_DESCRIPTION, schema(Map.of(
                "pair", stringProp("Optional pair filter, such as EUR/USD")
            ), List.of())),
            new ToolDefinition(GET_SETTINGS, GET_SETTINGS_DESCRIPTION, schema(Map.of(), List.of())),
            new ToolDefinition(GET_LATEST_FORECAST, GET_LATEST_FORECAST_DESCRIPTION, schema(Map.of(
                "asset", stringProp("Optional asset, such as EUR/USD or GOLD/USD. Omit it to read the newest run.")
            ), List.of())),
            new ToolDefinition(SEARCH_NOTES, SEARCH_NOTES_DESCRIPTION, schema(Map.of(
                "query", stringProp("What the note should be about, in the user's own words")
            ), List.of("query"))),
            new ToolDefinition(LIST_MY_ALERT_RULES, LIST_MY_ALERT_RULES_DESCRIPTION, schema(Map.of(
                "pair", stringProp("Optional pair filter, such as EUR/USD"),
                "status", stringProp("Optional status filter: ARMED, TRIGGERED, or PAUSED")
            ), List.of())),
            new ToolDefinition(GET_MY_ALERT_INBOX, GET_MY_ALERT_INBOX_DESCRIPTION, schema(Map.of(
                "pair", stringProp("Optional pair filter, such as EUR/USD"),
                "unread_only", booleanProp("When true, return only unread alerts"),
                "limit", integerProp("Maximum alerts to return", 1, MAX_ALERTS)
            ), List.of())),
            new ToolDefinition(CREATE_ALERT_RULE, CREATE_ALERT_RULE_DESCRIPTION, createAlertRuleSchema()),
            new ToolDefinition(UPDATE_ALERT_RULE_STATUS, UPDATE_ALERT_RULE_STATUS_DESCRIPTION, schema(Map.of(
                "rule_id", stringProp("UUID of the user's alert rule"),
                "status", stringProp("New status: ARMED or PAUSED")
            ), List.of("rule_id", "status")))
        );
    }

    public String execute(String name, String argumentsJson) {
        try {
            JsonNode arguments = parseArguments(argumentsJson);
            String result = switch (name == null ? "" : name) {
                case GET_LIVE_RATES -> liveRates();
                case GET_HISTORICAL_SERIES -> historicalSeries(arguments);
                case COMPARE_PAIRS -> comparePairs(arguments);
                case GET_ALERT_HISTORY -> alertHistory(arguments);
                case GET_ALERT_PREFERENCES -> alertPreferences(arguments);
                case GET_MONITORING_STATE -> monitoringState(arguments);
                case GET_SETTINGS -> settings();
                case GET_LATEST_FORECAST -> latestForecast(arguments);
                case SEARCH_NOTES -> searchNotes(arguments);
                case LIST_MY_ALERT_RULES -> listMyAlertRules(arguments);
                case GET_MY_ALERT_INBOX -> myAlertInbox(arguments);
                case CREATE_ALERT_RULE -> createAlertRule(arguments);
                case UPDATE_ALERT_RULE_STATUS -> updateAlertRuleStatus(arguments);
                default -> error("Unknown tool");
            };
            return limit(result);
        } catch (IllegalArgumentException exception) {
            return error(exception.getMessage());
        } catch (RestClientException exception) {
            return error("The market data feed could not be reached.");
        } catch (DataAccessException exception) {
            log.warn("Assistant tool {} database access failed: {}", name, exception.getMessage());
            return error("The application data could not be read.");
        } catch (RuntimeException exception) {
            return error("The tool could not be completed.");
        }
    }

    private String liveRates() {
        Map<String, Object> rates = new LinkedHashMap<>();
        marketDataService.fetchLiveRates().forEach((pair, rate) -> rates.put(pair, quote(rate)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("as_of", clock.instant().toString());
        body.put("max_age_seconds", liveRateMaxAgeSeconds);
        body.put("rates", rates);
        return write(body);
    }

    private String historicalSeries(JsonNode arguments) {
        return write(historicalBody(requiredPair(arguments), arguments.path("days").asInt(0)));
    }

    private String comparePairs(JsonNode arguments) {
        String first = requiredSymbol(arguments, "first");
        String second = requiredSymbol(arguments, "second");
        if (first.equals(second)) {
            throw new IllegalArgumentException("Compare two different pairs");
        }
        int days = arguments.path("days").asInt(0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("days", days);
        body.put("first", historicalBody(first, days));
        body.put("second", historicalBody(second, days));
        return write(body);
    }

    private Map<String, Object> historicalBody(String pair, int days) {
        if (days < 1 || days > MAX_HISTORY_DAYS) {
            throw new IllegalArgumentException("days must be between 1 and " + MAX_HISTORY_DAYS);
        }
        List<HistoricalPoint> points = marketDataService.fetchHistorical(pair, days);
        if (points.isEmpty()) {
            return new LinkedHashMap<>(Map.of(
                "available", false,
                "pair", pair,
                "reason", "No daily closes were returned."
            ));
        }
        HistoricalPoint first = points.getFirst();
        HistoricalPoint last = points.getLast();
        double change = last.rate() - first.rate();
        double changePercent = first.rate() == 0 ? 0 : (change / first.rate()) * 100;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("available", true);
        body.put("pair", pair);
        body.put("days", days);
        body.put("point_count", points.size());
        body.put("start", point(first));
        body.put("end", point(last));
        body.put("change", change);
        body.put("change_percent", changePercent);
        body.put("series", sample(points, MAX_SERIES_POINTS).stream().map(this::point).toList());
        return body;
    }

    private String alertHistory(JsonNode arguments) {
        String pair = optionalPair(arguments);
        int limit = arguments.path("limit").asInt(10);
        if (limit < 1 || limit > MAX_ALERTS) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_ALERTS);
        }
        List<Map<String, Object>> alerts = jdbcTemplate.query("""
                SELECT pair, percent_change, old_rate, new_rate, timestamp, email_sent,
                       alert_type, trigger_value, threshold_value
                FROM alerts
                WHERE (? IS NULL OR pair = ?)
                ORDER BY timestamp DESC
                LIMIT ?
                """,
            (resultSet, rowNumber) -> {
                Map<String, Object> alert = new LinkedHashMap<>();
                alert.put("pair", resultSet.getString("pair"));
                alert.put("alert_type", resultSet.getString("alert_type"));
                alert.put("percent_change", resultSet.getObject("percent_change"));
                alert.put("old_rate", resultSet.getObject("old_rate"));
                alert.put("new_rate", resultSet.getObject("new_rate"));
                alert.put("trigger_value", resultSet.getObject("trigger_value"));
                alert.put("threshold_value", resultSet.getObject("threshold_value"));
                alert.put("email_sent", resultSet.getBoolean("email_sent"));
                OffsetDateTime timestamp = resultSet.getObject("timestamp", OffsetDateTime.class);
                alert.put("timestamp", timestamp == null ? null : timestamp.toInstant().toString());
                return alert;
            },
            pair, pair, limit
        );
        return write(Map.of("count", alerts.size(), "alerts", alerts));
    }

    private String alertPreferences(JsonNode arguments) {
        String pair = optionalPair(arguments);
        List<Map<String, Object>> rules = jdbcTemplate.query("""
                SELECT pair, enabled, alert_type, custom_threshold, custom_period,
                       enable_trend_consistency, lookback_years, price_high, price_low,
                       trigger_type, volatility_type, ma_short_period, ma_long_period, signal_type
                FROM alert_preferences
                WHERE (? IS NULL OR pair = ?)
                ORDER BY pair
                """,
            (resultSet, rowNumber) -> {
                Map<String, Object> rule = new LinkedHashMap<>();
                rule.put("pair", resultSet.getString("pair"));
                rule.put("enabled", resultSet.getBoolean("enabled"));
                rule.put("alert_type", resultSet.getString("alert_type"));
                rule.put("custom_threshold", resultSet.getObject("custom_threshold"));
                rule.put("custom_period", resultSet.getObject("custom_period"));
                rule.put("enable_trend_consistency", resultSet.getBoolean("enable_trend_consistency"));
                rule.put("lookback_years", resultSet.getInt("lookback_years"));
                rule.put("price_high", resultSet.getObject("price_high"));
                rule.put("price_low", resultSet.getObject("price_low"));
                rule.put("trigger_type", resultSet.getString("trigger_type"));
                rule.put("volatility_type", resultSet.getString("volatility_type"));
                rule.put("ma_short_period", resultSet.getInt("ma_short_period"));
                rule.put("ma_long_period", resultSet.getInt("ma_long_period"));
                rule.put("signal_type", resultSet.getString("signal_type"));
                return rule;
            },
            pair, pair
        );
        return write(Map.of("count", rules.size(), "preferences", rules));
    }

    private String monitoringState(JsonNode arguments) {
        String pair = optionalPair(arguments);
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT pair, last_alert_time
                FROM monitoring_state
                WHERE (? IS NULL OR pair = ?)
                ORDER BY pair
                """,
            (resultSet, rowNumber) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("pair", resultSet.getString("pair"));
                OffsetDateTime lastAlert = resultSet.getObject("last_alert_time", OffsetDateTime.class);
                row.put("last_alert_time", lastAlert == null ? null : lastAlert.toInstant().toString());
                return row;
            },
            pair, pair
        );
        return write(Map.of("count", rows.size(), "monitoring", rows));
    }

    private String settings() {
        List<Map<String, Object>> rows = jdbcTemplate.query(
            "SELECT key, value FROM settings ORDER BY key",
            (resultSet, rowNumber) -> Map.of(
                "key", resultSet.getString("key"),
                "value", resultSet.getString("value") == null ? "" : resultSet.getString("value")
            )
        );
        List<Map<String, Object>> visible = new ArrayList<>();
        int omitted = 0;
        for (Map<String, Object> row : rows) {
            String key = String.valueOf(row.get("key"));
            if (secretSetting(key)) {
                omitted++;
                continue;
            }
            if (visible.size() >= MAX_SETTINGS) {
                break;
            }
            String value = String.valueOf(row.get("value"));
            if (value.length() > 240) {
                value = value.substring(0, 240);
            }
            visible.add(Map.of("key", key, "value", value));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", visible.size());
        body.put("secrets_omitted", omitted);
        body.put("settings", visible);
        return write(body);
    }

    static boolean secretSetting(String key) {
        if (key == null || key.isBlank()) {
            return true;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replace('-', '_');
        return normalized.contains("password")
            || normalized.contains("secret")
            || normalized.contains("token")
            || normalized.contains("api_key")
            || normalized.contains("apikey")
            || normalized.contains("credential");
    }

    private String searchNotes(JsonNode arguments) {
        if (knowledgeRetriever == null) {
            return error("Knowledge search is unavailable.");
        }
        String query = arguments.path("query").asText("").trim();
        if (query.isEmpty()) {
            throw new IllegalArgumentException("query is required");
        }
        if (query.length() > 500) {
            query = query.substring(0, 500);
        }
        List<Map<String, Object>> notes = new ArrayList<>();
        for (KnowledgeNote note : knowledgeRetriever.retrieve(query, 4)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", note.title());
            item.put("excerpt", note.excerpt(700));
            notes.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", notes.size());
        body.put("notes", notes);
        if (notes.isEmpty()) {
            body.put("reason", "No matching note was found.");
        }
        return write(body);
    }

    private String listMyAlertRules(JsonNode arguments) {
        long userId = requireAuthenticatedUserId();
        String pair = optionalPair(arguments);
        String statusFilter = optionalStatusFilter(arguments);
        List<AlertRuleView> rules = userAlertRuleService.listRules(userId).stream()
            .filter(rule -> pair == null || pair.equals(rule.pair()))
            .filter(rule -> statusFilter == null || statusFilter.equals(rule.status()))
            .toList();
        UserAlertProfile profile = userAlertRuleService.getProfile(userId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", rules.size());
        body.put("rules", rules);
        body.put("favorites", profile.favorites());
        body.put("alert_email", profile.alertEmail());
        return write(body);
    }

    private String myAlertInbox(JsonNode arguments) {
        long userId = requireAuthenticatedUserId();
        String pair = optionalPair(arguments);
        boolean unreadOnly = Boolean.TRUE.equals(optionalBoolean(arguments, "unread_only"));
        int limit = arguments.path("limit").asInt(10);
        if (limit < 1 || limit > MAX_ALERTS) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_ALERTS);
        }
        AlertInboxResponse inbox = userAlertRuleService.getInbox(userId);
        var alerts = inbox.alerts().stream()
            .filter(entry -> pair == null || pair.equals(entry.pair()))
            .filter(entry -> !unreadOnly || entry.readAt() == null)
            .limit(limit)
            .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("unread_count", inbox.unreadCount());
        body.put("count", alerts.size());
        body.put("alerts", alerts);
        body.put("favorites", inbox.favorites());
        body.put("alert_email", inbox.alertEmail());
        return write(body);
    }

    private String createAlertRule(JsonNode arguments) {
        long userId = requireAuthenticatedUserId();
        String pair = requiredPair(arguments);
        RuleRequest request = new RuleRequest(
            pair,
            optionalText(arguments, "nickname"),
            optionalText(arguments, "condition_type"),
            optionalDouble(arguments, "target_value"),
            optionalInt(arguments, "window_days"),
            optionalInt(arguments, "ma_short_period"),
            optionalInt(arguments, "ma_long_period"),
            optionalText(arguments, "signal_type"),
            optionalText(arguments, "frequency"),
            optionalInt(arguments, "cooldown_seconds"),
            optionalBoolean(arguments, "notify_email"),
            null
        );
        AlertRuleView created = userAlertRuleService.createRule(userId, request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("created", true);
        body.put("rule", created);
        return write(body);
    }

    private String updateAlertRuleStatus(JsonNode arguments) {
        long userId = requireAuthenticatedUserId();
        UUID ruleId = requiredUuid(arguments, "rule_id");
        String status = optionalText(arguments, "status");
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status is required (ARMED or PAUSED)");
        }
        RuleRequest request = new RuleRequest(
            null, null, null, null, null, null, null, null, null, null, null, status
        );
        Optional<AlertRuleView> updated = userAlertRuleService.updateRule(userId, ruleId, request);
        if (updated.isEmpty()) {
            return error("Alert rule not found.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("updated", true);
        body.put("rule", updated.get());
        return write(body);
    }

    private long requireAuthenticatedUserId() {
        if (userAlertRuleService == null || userRepository == null) {
            throw new IllegalArgumentException("Personal alert rules service is unavailable.");
        }
        String username = ToolUserContext.currentUsername();
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Authentication is required to access personal alert rules.");
        }
        return userRepository.findByUsername(username)
            .map(UserAccount::id)
            .orElseThrow(() -> new IllegalArgumentException("Authenticated user account was not found."));
    }

    private static String optionalStatusFilter(JsonNode arguments) {
        String raw = optionalText(arguments, "status");
        if (raw == null) {
            return null;
        }
        String normalized = raw.toUpperCase(Locale.ROOT);
        if (!VALID_RULE_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("status must be ARMED, TRIGGERED, or PAUSED");
        }
        return normalized;
    }

    private static UUID requiredUuid(JsonNode arguments, String field) {
        String raw = optionalText(arguments, field);
        if (raw == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid " + field + " UUID");
        }
    }

    private static String optionalText(JsonNode arguments, String field) {
        JsonNode node = arguments.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private static Double optionalDouble(JsonNode arguments, String field) {
        JsonNode node = arguments.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isTextual() && !node.asText().isBlank()) {
            try {
                return Double.parseDouble(node.asText().trim());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(field + " must be a number");
            }
        }
        return null;
    }

    private static Integer optionalInt(JsonNode arguments, String field) {
        JsonNode node = arguments.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asInt();
        }
        if (node.isTextual() && !node.asText().isBlank()) {
            try {
                return Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(field + " must be an integer");
            }
        }
        throw new IllegalArgumentException(field + " must be an integer");
    }

    private static Boolean optionalBoolean(JsonNode arguments, String field) {
        JsonNode node = arguments.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isTextual()) {
            String value = node.asText().trim().toLowerCase(Locale.ROOT);
            if ("true".equals(value)) {
                return Boolean.TRUE;
            }
            if ("false".equals(value)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private static Map<String, Object> createAlertRuleSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("pair", stringProp("Pair such as EUR/USD or GOLD/USD"));
        props.put("condition_type", stringProp("One of: cross_above, cross_below, pct_move, ma_crossover"));
        props.put("target_value", numberProp("Target price for cross_above/cross_below, or % move for pct_move"));
        props.put("nickname", stringProp("Optional short label for the alert rule"));
        props.put("window_days", integerProp("Lookback window in days for pct_move (default 30)", 1, MAX_HISTORY_DAYS));
        props.put("ma_short_period", integerProp("Short MA period for ma_crossover (default 20)", 2, 200));
        props.put("ma_long_period", integerProp("Long MA period for ma_crossover (default 50)", 3, MAX_HISTORY_DAYS));
        props.put("signal_type", stringProp("For ma_crossover: both, golden_cross, or death_cross"));
        props.put("frequency", stringProp("ONCE (default) or RECURRING"));
        props.put("cooldown_seconds", integerProp("Cooldown seconds for RECURRING rules (default 3600)", 300, 604_800));
        props.put("notify_email", booleanProp("Whether to send email when triggered (default true)"));
        return schema(props, List.of("pair", "condition_type"));
    }

    private String latestForecast(JsonNode arguments) {
        String asset = optionalAsset(arguments);
        List<Map<String, Object>> runs = jdbcTemplate.query("""
                SELECT id, created_at, model_type, lookback_days, horizon_days, asset, notes
                FROM dl_forecast_runs
                WHERE (? IS NULL OR asset = ?)
                ORDER BY id DESC
                LIMIT 1
                """,
            (resultSet, rowNumber) -> {
                Map<String, Object> run = new LinkedHashMap<>();
                run.put("id", resultSet.getLong("id"));
                run.put("created_at", resultSet.getString("created_at"));
                run.put("model_type", resultSet.getString("model_type"));
                run.put("lookback_days", resultSet.getInt("lookback_days"));
                run.put("horizon_days", resultSet.getInt("horizon_days"));
                run.put("asset", resultSet.getString("asset"));
                run.put("notes", resultSet.getString("notes"));
                return run;
            },
            asset, asset
        );
        if (runs.isEmpty()) {
            return write(Map.of("available", false, "reason", "No forecast has been stored."));
        }
        long runId = ((Number) runs.getFirst().get("id")).longValue();
        List<Map<String, Object>> points = forecastPoints(runId, false);
        if (points.isEmpty()) {
            points = forecastPoints(runId, true);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("available", !points.isEmpty());
        body.put("run", runs.getFirst());
        body.put("point_count", points.size());
        body.put("forecast", sampleMaps(points, MAX_FORECAST_POINTS));
        body.put("note", "Stored model path. It is not a live quote and not a guaranteed future price.");
        if (points.isEmpty()) {
            body.put("reason", "The forecast run has no stored points.");
        }
        return write(body);
    }

    private List<Map<String, Object>> forecastPoints(long runId, boolean gold) {
        String sql = gold
            ? """
                SELECT dt, predicted_close
                FROM gold_forecast_daily
                WHERE run_id = ?
                ORDER BY dt
                """
            : """
                SELECT dt, predicted_close
                FROM asset_forecast_daily
                WHERE run_id = ?
                ORDER BY dt
                """;
        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("dt", resultSet.getString("dt"));
            point.put("predicted_close", resultSet.getDouble("predicted_close"));
            return point;
        }, runId);
    }

    private JsonNode parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(argumentsJson);
            if (!node.isObject()) {
                throw new IllegalArgumentException("Tool arguments must be a JSON object.");
            }
            return node;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Tool arguments are not valid JSON.");
        }
    }

    private static String requiredPair(JsonNode arguments) {
        return requiredSymbol(arguments, "pair");
    }

    private static String requiredSymbol(JsonNode arguments, String field) {
        String symbol = optionalSymbol(arguments, field);
        if (symbol == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return symbol;
    }

    private static String optionalPair(JsonNode arguments) {
        return optionalSymbol(arguments, "pair");
    }

    private static String optionalAsset(JsonNode arguments) {
        return optionalSymbol(arguments, "asset");
    }

    private static String optionalSymbol(JsonNode arguments, String field) {
        String raw = arguments.path(field).asText("").trim();
        if (raw.isEmpty()) {
            return null;
        }
        String symbol = raw.toUpperCase(Locale.ROOT);
        if (symbol.length() > 32 || !symbol.matches("[A-Z0-9.]+/[A-Z0-9.]+")) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return symbol;
    }

    private Map<String, Object> quote(LiveRate rate) {
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("rate", rate.rate());
        quote.put("change", rate.change());
        quote.put("change_percent", rate.changePercent());
        return quote;
    }

    private Map<String, Object> point(HistoricalPoint point) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("date", point.date());
        body.put("rate", point.rate());
        return body;
    }

    private static List<HistoricalPoint> sample(List<HistoricalPoint> points, int maxPoints) {
        if (points.size() <= maxPoints) {
            return points;
        }
        List<HistoricalPoint> sampled = new ArrayList<>(maxPoints);
        double step = (points.size() - 1) / (double) (maxPoints - 1);
        for (int index = 0; index < maxPoints; index++) {
            sampled.add(points.get((int) Math.round(index * step)));
        }
        return sampled;
    }

    private static List<Map<String, Object>> sampleMaps(List<Map<String, Object>> points, int maxPoints) {
        if (points.size() <= maxPoints) {
            return points;
        }
        List<Map<String, Object>> sampled = new ArrayList<>(maxPoints);
        double step = (points.size() - 1) / (double) (maxPoints - 1);
        for (int index = 0; index < maxPoints; index++) {
            sampled.add(points.get((int) Math.round(index * step)));
        }
        return sampled;
    }

    private String write(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            return error("The tool result could not be encoded.");
        }
    }

    private String error(String message) {
        String text = message == null || message.isBlank() ? "The tool could not be completed." : message;
        return write(Map.of("error", text));
    }

    private static String limit(String result) {
        if (result.length() <= MAX_RESULT_CHARS) {
            return result;
        }
        return result.substring(0, MAX_RESULT_CHARS) + "...[truncated]";
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> stringProp(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> numberProp(String description) {
        return Map.of("type", "number", "description", description);
    }

    private static Map<String, Object> booleanProp(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    private static Map<String, Object> integerProp(String description, int minimum, int maximum) {
        return Map.of(
            "type", "integer",
            "description", description,
            "minimum", minimum,
            "maximum", maximum
        );
    }
}
