package com.currencyanalyzer.alerts;

import com.currencyanalyzer.alerts.AlertMonitoringRepository.AlertEntry;
import com.currencyanalyzer.alerts.AlertMonitoringRepository.AlertPreference;
import com.currencyanalyzer.marketdata.MarketDataService;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AlertMonitoringService {

    private static final int MAX_ALERTS_RETURNED = 200;
    private static final int MAX_PAIR_LENGTH = 64;
    private static final int MAX_EMAIL_LENGTH = 255;

    private static final String KEY_TREND_THRESHOLD = "trend_threshold";
    private static final String KEY_DETECTION_PERIOD = "detection_period";
    private static final String KEY_CHECK_INTERVAL = "check_interval";
    private static final String KEY_ALERT_EMAIL = "alert_email";
    private static final String KEY_ENABLE_ALERTS = "enable_alerts";
    private static final String KEY_ENABLE_SOUND = "enable_sound";
    private static final String KEY_DAILY_RISK_LIMIT_PCT = "daily_risk_limit_pct";
    private static final String KEY_MONITORING_ENABLED = "monitoring_enabled";

    private static final String KEY_PAIR = "pair";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ALERT_TYPE = "alert_type";
    private static final String KEY_CUSTOM_THRESHOLD = "custom_threshold";
    private static final String KEY_CUSTOM_PERIOD = "custom_period";
    private static final String KEY_ENABLE_TREND_CONSISTENCY = "enable_trend_consistency";
    private static final String KEY_LOOKBACK_YEARS = "lookback_years";
    private static final String KEY_PRICE_HIGH = "price_high";
    private static final String KEY_PRICE_LOW = "price_low";
    private static final String KEY_TRIGGER_TYPE = "trigger_type";
    private static final String KEY_VOLATILITY_TYPE = "volatility_type";
    private static final String KEY_MA_SHORT_PERIOD = "ma_short_period";
    private static final String KEY_MA_LONG_PERIOD = "ma_long_period";
    private static final String KEY_SIGNAL_TYPE = "signal_type";

    private static final String TYPE_PERCENTAGE_CHANGE = "percentage_change";
    private static final String TYPE_PRICE_THRESHOLD = "price_threshold";
    private static final String TYPE_MA_CROSSOVER = "moving_average_crossover";

    private static final String SPEC_TYPE = "type";
    private static final String SPEC_MIN = "min";
    private static final String SPEC_MAX = "max";
    private static final String SPEC_DEFAULT = "default";
    private static final String SPEC_UNIT = "unit";
    private static final String SPEC_OPTIONS = "options";
    private static final String TYPE_NUMBER = "number";
    private static final String TYPE_SELECT = "select";
    private static final String TYPE_BOOLEAN = "boolean";
    private static final String DEFAULT_TRIGGER_TYPE = "outside_range";
    private static final String DEFAULT_SIGNAL_TYPE = "both";

    private static final Map<String, Object> ALERT_CONDITIONS = buildAlertConditions();

    private final AlertMonitoringRepository repository;
    private final MarketDataService marketDataService;

    public AlertMonitoringService(
        AlertMonitoringRepository repository,
        MarketDataService marketDataService
    ) {
        this.repository = repository;
        this.marketDataService = marketDataService;
    }

    public List<AlertEntry> listAlerts() {
        return repository.listAlerts(MAX_ALERTS_RETURNED);
    }

    public void clearAlerts() {
        repository.clearAlerts();
    }

    public boolean isMonitoringActive() {
        return repository.findSetting(KEY_MONITORING_ENABLED)
            .map(value -> Boolean.parseBoolean(value.trim()))
            .orElse(false);
    }

    public void setMonitoringActive(boolean active) {
        repository.upsertSetting(KEY_MONITORING_ENABLED, Boolean.toString(active));
    }

    public Map<String, Object> getSettings() {
        Map<String, String> stored = repository.findAllSettings();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put(KEY_TREND_THRESHOLD, parseDoubleOrDefault(stored.get(KEY_TREND_THRESHOLD), 2.0));
        settings.put(KEY_DETECTION_PERIOD, parseIntOrDefault(stored.get(KEY_DETECTION_PERIOD), 30));
        settings.put(KEY_CHECK_INTERVAL, parseIntOrDefault(stored.get(KEY_CHECK_INTERVAL), 900));
        settings.put(KEY_ALERT_EMAIL, stored.getOrDefault(KEY_ALERT_EMAIL, ""));
        settings.put(KEY_ENABLE_ALERTS, parseBooleanOrDefault(stored.get(KEY_ENABLE_ALERTS), true));
        settings.put(KEY_ENABLE_SOUND, parseBooleanOrDefault(stored.get(KEY_ENABLE_SOUND), true));
        settings.put(KEY_DAILY_RISK_LIMIT_PCT, parseDoubleOrDefault(stored.get(KEY_DAILY_RISK_LIMIT_PCT), 3.0));
        return Collections.unmodifiableMap(settings);
    }

    public void saveSettings(Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Settings payload is required");
        }

        Map<String, String> updates = new LinkedHashMap<>();
        if (hasValue(payload, KEY_TREND_THRESHOLD)) {
            double threshold = parsePositiveDouble(payload.get(KEY_TREND_THRESHOLD), KEY_TREND_THRESHOLD, 100.0);
            updates.put(KEY_TREND_THRESHOLD, Double.toString(threshold));
        }
        if (hasValue(payload, KEY_DETECTION_PERIOD)) {
            int period = parseBoundedInt(payload.get(KEY_DETECTION_PERIOD), KEY_DETECTION_PERIOD, 1, 3650);
            updates.put(KEY_DETECTION_PERIOD, Integer.toString(period));
        }
        if (hasValue(payload, KEY_CHECK_INTERVAL)) {
            int interval = parseBoundedInt(payload.get(KEY_CHECK_INTERVAL), KEY_CHECK_INTERVAL, 60, 86400);
            updates.put(KEY_CHECK_INTERVAL, Integer.toString(interval));
        }
        if (hasValue(payload, KEY_DAILY_RISK_LIMIT_PCT)) {
            double limitPct = parsePositiveDouble(payload.get(KEY_DAILY_RISK_LIMIT_PCT), KEY_DAILY_RISK_LIMIT_PCT, 100.0);
            updates.put(KEY_DAILY_RISK_LIMIT_PCT, Double.toString(limitPct));
        }
        if (payload.containsKey(KEY_ALERT_EMAIL)) {
            String email = normalizeString(payload.get(KEY_ALERT_EMAIL), MAX_EMAIL_LENGTH, KEY_ALERT_EMAIL);
            updates.put(KEY_ALERT_EMAIL, email == null ? "" : email);
        }
        if (hasValue(payload, KEY_ENABLE_ALERTS)) {
            updates.put(KEY_ENABLE_ALERTS, Boolean.toString(parseStrictBoolean(payload.get(KEY_ENABLE_ALERTS), KEY_ENABLE_ALERTS)));
        }
        if (hasValue(payload, KEY_ENABLE_SOUND)) {
            updates.put(KEY_ENABLE_SOUND, Boolean.toString(parseStrictBoolean(payload.get(KEY_ENABLE_SOUND), KEY_ENABLE_SOUND)));
        }

        updates.forEach(repository::upsertSetting);
    }

    public Map<String, Object> getAlertConditions() {
        return ALERT_CONDITIONS;
    }

    public Map<String, Map<String, Object>> getAlertPreferences() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (AlertPreference preference : getEffectivePreferences()) {
            result.put(preference.pair(), toPreferenceMap(preference));
        }
        return Collections.unmodifiableMap(result);
    }

    public List<AlertPreference> getEffectivePreferences() {
        Map<String, AlertPreference> stored = repository.findAllAlertPreferences();
        Map<String, AlertPreference> ordered = new LinkedHashMap<>();
        for (String pair : marketDataService.configuredPairs()) {
            ordered.put(pair, stored.getOrDefault(pair, defaultPreference(pair)));
        }
        for (Map.Entry<String, AlertPreference> entry : stored.entrySet()) {
            ordered.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return List.copyOf(ordered.values());
    }

    public void saveAlertPreference(Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Alert preference payload is required");
        }
        String rawPair = normalizeString(payload.get(KEY_PAIR), MAX_PAIR_LENGTH, KEY_PAIR);
        if (rawPair == null || rawPair.isEmpty()) {
            throw new IllegalArgumentException("pair is required");
        }
        String pair = rawPair.toUpperCase(Locale.ROOT);

        String alertType = normalizeString(payload.get(KEY_ALERT_TYPE), MAX_PAIR_LENGTH, KEY_ALERT_TYPE);
        if (alertType == null || alertType.isEmpty()) {
            alertType = TYPE_PERCENTAGE_CHANGE;
        }
        if (!ALERT_CONDITIONS.containsKey(alertType)) {
            throw new IllegalArgumentException("Unsupported alert_type: " + alertType);
        }

        AlertPreference base = repository.findAllAlertPreferences()
            .getOrDefault(pair, defaultPreference(pair));
        AlertPreference preference = new AlertPreference(
            pair,
            resolveBooleanField(payload, KEY_ENABLED, base.enabled()),
            alertType,
            resolveDoubleField(payload, KEY_CUSTOM_THRESHOLD, 0.1, 100.0, base.customThreshold()),
            resolveIntField(payload, KEY_CUSTOM_PERIOD, 1, 3650, base.customPeriod()),
            resolveBooleanField(payload, KEY_ENABLE_TREND_CONSISTENCY, base.enableTrendConsistency()),
            resolveIntField(payload, KEY_LOOKBACK_YEARS, 1, 20, base.lookbackYears()),
            resolveDoubleField(payload, KEY_PRICE_HIGH, 0.0, 1_000_000.0, base.priceHigh()),
            resolveDoubleField(payload, KEY_PRICE_LOW, 0.0, 1_000_000.0, base.priceLow()),
            resolveStringField(payload, KEY_TRIGGER_TYPE, base.triggerType()),
            resolveStringField(payload, KEY_VOLATILITY_TYPE, base.volatilityType()),
            resolveIntField(payload, KEY_MA_SHORT_PERIOD, 2, 200, base.maShortPeriod()),
            resolveIntField(payload, KEY_MA_LONG_PERIOD, 5, 500, base.maLongPeriod()),
            resolveStringField(payload, KEY_SIGNAL_TYPE, base.signalType())
        );

        repository.upsertAlertPreference(preference);
    }

    private static AlertPreference defaultPreference(String pair) {
        return new AlertPreference(
            pair,
            true,
            TYPE_PERCENTAGE_CHANGE,
            2.0,
            30,
            true,
            5,
            null,
            null,
            DEFAULT_TRIGGER_TYPE,
            null,
            10,
            50,
            DEFAULT_SIGNAL_TYPE
        );
    }

    private static Map<String, Object> toPreferenceMap(AlertPreference preference) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(KEY_PAIR, preference.pair());
        map.put(KEY_ENABLED, preference.enabled());
        map.put(KEY_ALERT_TYPE, preference.alertType());
        map.put(KEY_CUSTOM_THRESHOLD, preference.customThreshold());
        map.put(KEY_CUSTOM_PERIOD, preference.customPeriod());
        map.put(KEY_ENABLE_TREND_CONSISTENCY, preference.enableTrendConsistency());
        map.put(KEY_LOOKBACK_YEARS, preference.lookbackYears());
        map.put(KEY_PRICE_HIGH, preference.priceHigh());
        map.put(KEY_PRICE_LOW, preference.priceLow());
        map.put(KEY_TRIGGER_TYPE, preference.triggerType());
        map.put(KEY_VOLATILITY_TYPE, preference.volatilityType());
        map.put(KEY_MA_SHORT_PERIOD, preference.maShortPeriod());
        map.put(KEY_MA_LONG_PERIOD, preference.maLongPeriod());
        map.put(KEY_SIGNAL_TYPE, preference.signalType());
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, Object> buildAlertConditions() {
        Map<String, Object> percentageParams = new LinkedHashMap<>();
        percentageParams.put(KEY_CUSTOM_THRESHOLD, numberParam(0.1, 50, 2.0, "%"));
        percentageParams.put(KEY_CUSTOM_PERIOD, numberParam(1, 365, 30, "days"));
        percentageParams.put(KEY_ENABLE_TREND_CONSISTENCY, Map.of(SPEC_TYPE, TYPE_BOOLEAN, SPEC_DEFAULT, true));

        Map<String, Object> priceParams = new LinkedHashMap<>();
        priceParams.put(KEY_PRICE_HIGH, numberParam(0, 1_000_000, 0, "rate"));
        priceParams.put(KEY_PRICE_LOW, numberParam(0, 1_000_000, 0, "rate"));
        priceParams.put(KEY_TRIGGER_TYPE, Map.of(
            SPEC_TYPE, TYPE_SELECT,
            SPEC_OPTIONS, List.of("above", "below", DEFAULT_TRIGGER_TYPE),
            SPEC_DEFAULT, DEFAULT_TRIGGER_TYPE
        ));

        Map<String, Object> maParams = new LinkedHashMap<>();
        maParams.put(KEY_MA_SHORT_PERIOD, numberParam(2, 200, 10, "days"));
        maParams.put(KEY_MA_LONG_PERIOD, numberParam(5, 500, 50, "days"));
        maParams.put(KEY_SIGNAL_TYPE, Map.of(
            SPEC_TYPE, TYPE_SELECT,
            SPEC_OPTIONS, List.of("golden_cross", "death_cross", DEFAULT_SIGNAL_TYPE),
            SPEC_DEFAULT, DEFAULT_SIGNAL_TYPE
        ));

        Map<String, Object> conditions = new LinkedHashMap<>();
        conditions.put(TYPE_PERCENTAGE_CHANGE, conditionSpec(
            "Percentage Change",
            "Trigger when rate moves by a percentage over a detection window.",
            percentageParams
        ));
        conditions.put(TYPE_PRICE_THRESHOLD, conditionSpec(
            "Price Threshold",
            "Trigger when rate crosses configured high or low bounds.",
            priceParams
        ));
        conditions.put(TYPE_MA_CROSSOVER, conditionSpec(
            "Moving Average Crossover",
            "Trigger when short moving average crosses long moving average.",
            maParams
        ));
        return Collections.unmodifiableMap(conditions);
    }

    private static Map<String, Object> conditionSpec(
        String name,
        String description,
        Map<String, Object> parameters
    ) {
        return Map.of(
            "name", name,
            "description", description,
            "parameters", Collections.unmodifiableMap(parameters)
        );
    }

    private static Map<String, Object> numberParam(Number min, Number max, Number defaultValue, String unit) {
        return Map.of(
            SPEC_TYPE, TYPE_NUMBER,
            SPEC_MIN, min,
            SPEC_MAX, max,
            SPEC_DEFAULT, defaultValue,
            SPEC_UNIT, unit
        );
    }

    private static boolean hasValue(Map<String, Object> payload, String key) {
        return payload.containsKey(key) && payload.get(key) != null;
    }

    private static boolean resolveBooleanField(Map<String, Object> payload, String key, boolean fallback) {
        if (!hasValue(payload, key)) {
            return fallback;
        }
        return parseStrictBoolean(payload.get(key), key);
    }

    private static Double resolveDoubleField(
        Map<String, Object> payload,
        String key,
        double min,
        double max,
        Double fallback
    ) {
        if (!payload.containsKey(key)) {
            return fallback;
        }
        Object raw = payload.get(key);
        if (raw == null) {
            return null;
        }
        double value = parseFiniteDouble(raw, key);
        if (value < min || value > max) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static int resolveIntField(
        Map<String, Object> payload,
        String key,
        int min,
        int max,
        int fallback
    ) {
        if (!hasValue(payload, key)) {
            return fallback;
        }
        return parseBoundedInt(payload.get(key), key, min, max);
    }

    private static String resolveStringField(Map<String, Object> payload, String key, String fallback) {
        if (!payload.containsKey(key)) {
            return fallback;
        }
        return normalizeString(payload.get(key), MAX_PAIR_LENGTH, key);
    }

    private static double parsePositiveDouble(Object raw, String field, double max) {
        double value = parseFiniteDouble(raw, field);
        if (value <= 0 || value > max) {
            throw new IllegalArgumentException(field + " must be greater than 0 and at most " + max);
        }
        return value;
    }

    private static double parseFiniteDouble(Object raw, String field) {
        double value;
        if (raw instanceof Number number) {
            value = number.doubleValue();
        } else {
            try {
                value = Double.parseDouble(raw.toString().trim());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(field + " must be a valid number", exception);
            }
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be a finite number");
        }
        return value;
    }

    private static int parseBoundedInt(Object raw, String field, int min, int max) {
        int value;
        if (raw instanceof Number number) {
            double numeric = number.doubleValue();
            if (!Double.isFinite(numeric) || Math.rint(numeric) != numeric) {
                throw new IllegalArgumentException(field + " must be an integer");
            }
            value = number.intValue();
        } else {
            try {
                value = Integer.parseInt(raw.toString().trim());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(field + " must be a valid integer", exception);
            }
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static boolean parseStrictBoolean(Object raw, String field) {
        if (raw instanceof Boolean bool) {
            return bool;
        }
        String text = raw.toString().trim().toLowerCase(Locale.ROOT);
        if ("true".equals(text)) {
            return true;
        }
        if ("false".equals(text)) {
            return false;
        }
        throw new IllegalArgumentException(field + " must be a boolean");
    }

    private static String normalizeString(Object raw, int maxLength, String field) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.toString().trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds maximum length of " + maxLength);
        }
        return trimmed;
    }

    private static double parseDoubleOrDefault(String raw, double fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(raw.trim());
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static int parseIntOrDefault(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static boolean parseBooleanOrDefault(String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(raw.trim());
    }
}
