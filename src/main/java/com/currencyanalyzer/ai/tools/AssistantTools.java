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
    public static final String ANALYZE_PAIR_TECHNICALS = "analyze_pair_technicals";
    public static final String CALCULATE_POSITION_SIZE = "calculate_position_size";
    public static final String GET_SETTINGS = "get_settings";
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
        "Compare two pairs over the same daily window (1 to 365 days), including relative return, Pearson return correlation, beta, and annualized volatility.";
    public static final String ANALYZE_PAIR_TECHNICALS_DESCRIPTION =
        "Compute deterministic technical indicators for one pair using ta4j and Hipparchus: SMA-20/50, EMA-12/26, RSI-14, MACD, Bollinger Bands, period high/low, max drawdown, and annualized volatility. Days defaults to 90 (range 14 to 365).";
    public static final String CALCULATE_POSITION_SIZE_DESCRIPTION =
        "Calculate deterministic educational position sizing (risk amount, stop distance %, units, notional exposure, implied leverage, optional R:R ratio, and daily_risk_limit_pct check). Omit entry_price to use the live spot rate.";
    public static final String GET_SETTINGS_DESCRIPTION =
        "Read stored application settings. Passwords, tokens, and API keys are omitted.";
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
    static final int MIN_TECHNICAL_DAYS = 14;
    static final int DEFAULT_TECHNICAL_DAYS = 90;
    static final int MAX_SERIES_POINTS = 40;
    static final int MAX_COMPARE_SERIES_POINTS = 15;
    static final int MAX_ALERTS = 20;
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
            new ToolDefinition(ANALYZE_PAIR_TECHNICALS, ANALYZE_PAIR_TECHNICALS_DESCRIPTION, schema(Map.of(
                "pair", stringProp("Pair such as EUR/USD or GOLD/USD"),
                "days", integerProp("Lookback days (default 90)", MIN_TECHNICAL_DAYS, MAX_HISTORY_DAYS)
            ), List.of("pair"))),
            new ToolDefinition(CALCULATE_POSITION_SIZE, CALCULATE_POSITION_SIZE_DESCRIPTION, positionSizeSchema()),
            new ToolDefinition(GET_SETTINGS, GET_SETTINGS_DESCRIPTION, schema(Map.of(), List.of())),
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
                case ANALYZE_PAIR_TECHNICALS -> analyzePairTechnicals(arguments);
                case CALCULATE_POSITION_SIZE -> calculatePositionSize(arguments);
                case GET_SETTINGS -> settings();
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
        String pair = requiredPair(arguments);
        int days = validateHistoryDays(arguments.path("days").asInt(0));
        List<HistoricalPoint> points = marketDataService.fetchHistorical(pair, days);
        return write(historicalBody(pair, days, points, MAX_SERIES_POINTS));
    }

    private String comparePairs(JsonNode arguments) {
        String first = requiredSymbol(arguments, "first");
        String second = requiredSymbol(arguments, "second");
        if (first.equals(second)) {
            throw new IllegalArgumentException("Compare two different pairs");
        }
        int days = validateHistoryDays(arguments.path("days").asInt(0));
        List<HistoricalPoint> firstPoints = marketDataService.fetchHistorical(first, days);
        List<HistoricalPoint> secondPoints = marketDataService.fetchHistorical(second, days);
        Map<String, Object> firstBody = historicalBody(first, days, firstPoints, MAX_COMPARE_SERIES_POINTS);
        Map<String, Object> secondBody = historicalBody(second, days, secondPoints, MAX_COMPARE_SERIES_POINTS);
        Map<String, Object> comparison = MarketQuantAnalytics.comparePairStatistics(firstPoints, secondPoints);
        if (Boolean.TRUE.equals(firstBody.get("available")) && Boolean.TRUE.equals(secondBody.get("available"))) {
            double firstPct = ((Number) firstBody.get("change_percent")).doubleValue();
            double secondPct = ((Number) secondBody.get("change_percent")).doubleValue();
            comparison.put("relative_change_pct", Math.round((firstPct - secondPct) * 100.0) / 100.0);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("days", days);
        body.put("first", firstBody);
        body.put("second", secondBody);
        body.put("comparison", comparison);
        return write(body);
    }

    private String analyzePairTechnicals(JsonNode arguments) {
        String pair = requiredPair(arguments);
        Integer rawDays = optionalInt(arguments, "days");
        int days = rawDays == null ? DEFAULT_TECHNICAL_DAYS : rawDays;
        if (days < MIN_TECHNICAL_DAYS || days > MAX_HISTORY_DAYS) {
            throw new IllegalArgumentException("days must be between " + MIN_TECHNICAL_DAYS + " and " + MAX_HISTORY_DAYS);
        }
        List<HistoricalPoint> points = marketDataService.fetchHistorical(pair, days);
        return write(MarketQuantAnalytics.analyzeTechnicals(pair, points, days));
    }

    private String calculatePositionSize(JsonNode arguments) {
        String pair = requiredPair(arguments);
        Double accountEquity = optionalDouble(arguments, "account_equity");
        Double riskPct = optionalDouble(arguments, "risk_pct");
        Double stopLossPrice = optionalDouble(arguments, "stop_loss_price");
        if (accountEquity == null) {
            throw new IllegalArgumentException("account_equity is required");
        }
        if (riskPct == null) {
            throw new IllegalArgumentException("risk_pct is required");
        }
        if (stopLossPrice == null) {
            throw new IllegalArgumentException("stop_loss_price is required");
        }
        Double entryPrice = optionalDouble(arguments, "entry_price");
        boolean usedLiveSpot = false;
        if (entryPrice == null) {
            LiveRate live = marketDataService.fetchLiveRates().get(pair);
            if (live == null || live.rate() <= 0) {
                throw new IllegalArgumentException("entry_price is required because live rate is unavailable for " + pair);
            }
            entryPrice = live.rate();
            usedLiveSpot = true;
        }
        Double takeProfitPrice = optionalDouble(arguments, "take_profit_price");
        Double dailyRiskLimitPct = readDailyRiskLimitPct();
        Map<String, Object> result = MarketQuantAnalytics.calculatePositionSize(
            pair, accountEquity, riskPct, entryPrice, stopLossPrice, takeProfitPrice, dailyRiskLimitPct
        );
        result.put("entry_source", usedLiveSpot ? "live_spot" : "user_specified");
        if (usedLiveSpot) {
            result.put("as_of", clock.instant().toString());
        }
        return write(result);
    }

    private Double readDailyRiskLimitPct() {
        try {
            List<String> values = jdbcTemplate.query(
                "SELECT value FROM settings WHERE key = 'daily_risk_limit_pct'",
                (rs, rowNum) -> rs.getString("value")
            );
            if (values.isEmpty() || values.getFirst() == null || values.getFirst().isBlank()) {
                return null;
            }
            return Double.parseDouble(values.getFirst().trim());
        } catch (DataAccessException | NumberFormatException ignored) {
            return null;
        }
    }

    private static int validateHistoryDays(int days) {
        if (days < 1 || days > MAX_HISTORY_DAYS) {
            throw new IllegalArgumentException("days must be between 1 and " + MAX_HISTORY_DAYS);
        }
        return days;
    }

    private Map<String, Object> historicalBody(
        String pair,
        int days,
        List<HistoricalPoint> points,
        int maxSamplePoints
    ) {
        if (points == null || points.isEmpty()) {
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
        body.put("series", sample(points, maxSamplePoints).stream().map(this::point).toList());
        return body;
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

    private static Map<String, Object> positionSizeSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("pair", stringProp("Pair such as EUR/USD or GOLD/USD"));
        props.put("account_equity", numberProp("Total account equity (positive number)"));
        props.put("risk_pct", numberProp("Percentage of equity to risk on this setup (e.g. 1.0 for 1%)"));
        props.put("stop_loss_price", numberProp("Planned stop-loss price"));
        props.put("entry_price", numberProp("Optional planned entry price; omit to use live spot rate"));
        props.put("take_profit_price", numberProp("Optional take-profit target price for R:R calculation"));
        return schema(props, List.of("pair", "account_equity", "risk_pct", "stop_loss_price"));
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
