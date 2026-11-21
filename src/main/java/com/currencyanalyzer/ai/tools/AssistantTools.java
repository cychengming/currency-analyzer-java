package com.currencyanalyzer.ai.tools;

import com.currencyanalyzer.ai.provider.AiProvider.ToolDefinition;
import com.currencyanalyzer.marketdata.HistoricalPoint;
import com.currencyanalyzer.marketdata.LiveRate;
import com.currencyanalyzer.marketdata.MarketDataService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@Component
public class AssistantTools {

    static final int MAX_HISTORY_DAYS = 365;
    static final int MAX_SERIES_POINTS = 40;
    static final int MAX_ALERTS = 20;
    static final int MAX_FORECAST_POINTS = 40;
    private static final int MAX_RESULT_CHARS = 6000;

    private final MarketDataService marketDataService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock;
    private final long liveRateMaxAgeSeconds;

    public AssistantTools(
        MarketDataService marketDataService,
        JdbcTemplate jdbcTemplate,
        Clock clock,
        @Value("${app.market.live-cache-ttl-seconds:60}") long liveRateMaxAgeSeconds
    ) {
        this.marketDataService = marketDataService;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.liveRateMaxAgeSeconds = liveRateMaxAgeSeconds;
    }

    public List<ToolDefinition> definitions() {
        return List.of(
            new ToolDefinition(
                "get_live_rates",
                "Read the current dashboard quotes for the configured pairs. Values come from the application's cached feed.",
                schema(Map.of(), List.of())
            ),
            new ToolDefinition(
                "get_historical_series",
                "Read daily closes for one pair. Days must be from 1 to 365. The result includes the change over that window and a sampled series.",
                schema(Map.of(
                    "pair", stringProp("Pair such as EUR/USD or GOLD/USD"),
                    "days", integerProp("Number of recent daily closes", 1, MAX_HISTORY_DAYS)
                ), List.of("pair", "days"))
            ),
            new ToolDefinition(
                "get_alert_history",
                "Read alerts this application has already recorded. Omit pair to read the latest alerts across pairs.",
                schema(Map.of(
                    "pair", stringProp("Optional pair filter, such as EUR/USD"),
                    "limit", integerProp("Maximum alerts to return", 1, MAX_ALERTS)
                ), List.of())
            ),
            new ToolDefinition(
                "get_latest_forecast",
                "Read the newest stored model forecast. This is a saved path, not a live price and not a promise of a future price.",
                schema(Map.of(
                    "asset", stringProp("Optional asset, such as EUR/USD or GOLD/USD. Omit it to read the newest run.")
                ), List.of())
            )
        );
    }

    public String execute(String name, String argumentsJson) {
        try {
            JsonNode arguments = parseArguments(argumentsJson);
            String result = switch (name == null ? "" : name) {
                case "get_live_rates" -> liveRates();
                case "get_historical_series" -> historicalSeries(arguments);
                case "get_alert_history" -> alertHistory(arguments);
                case "get_latest_forecast" -> latestForecast(arguments);
                default -> error("Unknown tool");
            };
            return limit(result);
        } catch (IllegalArgumentException exception) {
            return error(exception.getMessage());
        } catch (RestClientException exception) {
            return error("The market data feed could not be reached.");
        } catch (DataAccessException exception) {
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
        int days = arguments.path("days").asInt(0);
        if (days < 1 || days > MAX_HISTORY_DAYS) {
            throw new IllegalArgumentException("days must be between 1 and " + MAX_HISTORY_DAYS);
        }
        List<HistoricalPoint> points = marketDataService.fetchHistorical(pair, days);
        if (points.isEmpty()) {
            return write(Map.of("available", false, "pair", pair, "reason", "No daily closes were returned."));
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
        return write(body);
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
        String pair = optionalPair(arguments);
        if (pair == null) {
            throw new IllegalArgumentException("pair is required");
        }
        return pair;
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

    private static Map<String, Object> integerProp(String description, int minimum, int maximum) {
        return Map.of(
            "type", "integer",
            "description", description,
            "minimum", minimum,
            "maximum", maximum
        );
    }
}
