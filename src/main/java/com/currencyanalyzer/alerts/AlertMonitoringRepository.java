package com.currencyanalyzer.alerts;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AlertMonitoringRepository {

    private final JdbcClient jdbcClient;

    public AlertMonitoringRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<AlertEntry> listAlerts(int limit) {
        return jdbcClient.sql("""
                SELECT id, pair, percent_change, old_rate, new_rate, timestamp,
                       email_sent, alert_type, trigger_value, threshold_value
                FROM alerts
                ORDER BY timestamp DESC, id DESC
                LIMIT ?
                """)
            .param(limit)
            .query((rs, rowNum) -> mapAlert(rs))
            .list();
    }

    public Optional<AlertEntry> findAlertById(long id) {
        return jdbcClient.sql("""
                SELECT id, pair, percent_change, old_rate, new_rate, timestamp,
                       email_sent, alert_type, trigger_value, threshold_value
                FROM alerts
                WHERE id = ?
                """)
            .param(id)
            .query((rs, rowNum) -> mapAlert(rs))
            .optional();
    }

    @Transactional
    public AlertEntry insertAlert(
        String pair,
        double percentChange,
        double oldRate,
        double newRate,
        Instant timestamp,
        String alertType,
        double triggerValue,
        double thresholdValue
    ) {
        OffsetDateTime ts = OffsetDateTime.ofInstant(timestamp, ZoneOffset.UTC);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                INSERT INTO alerts (
                    pair, percent_change, old_rate, new_rate, timestamp,
                    email_sent, alert_type, trigger_value, threshold_value
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .param(pair)
            .param(percentChange)
            .param(oldRate)
            .param(newRate)
            .param(ts)
            .param(false)
            .param(alertType)
            .param(triggerValue)
            .param(thresholdValue)
            .update(keyHolder, "id");

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Inserted alert did not return a generated id");
        }
        return new AlertEntry(
            key.longValue(),
            pair,
            percentChange,
            oldRate,
            newRate,
            timestamp.toString(),
            false,
            alertType,
            triggerValue,
            thresholdValue
        );
    }

    @Transactional
    public boolean markAlertDelivered(long id) {
        int updated = jdbcClient.sql("""
                UPDATE alerts
                SET email_sent = TRUE
                WHERE id = ? AND email_sent = FALSE
                """)
            .param(id)
            .update();
        return updated > 0;
    }

    @Transactional
    public boolean claimCooldownWindow(String pair, Instant now, Instant cutoff) {
        OffsetDateTime nowTs = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        OffsetDateTime cutoffTs = OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC);
        int updated = jdbcClient.sql("""
                UPDATE monitoring_state
                SET last_alert_time = ?
                WHERE pair = ? AND (last_alert_time IS NULL OR last_alert_time <= ?)
                """)
            .param(nowTs)
            .param(pair)
            .param(cutoffTs)
            .update();
        if (updated > 0) {
            return true;
        }
        boolean exists = jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM monitoring_state WHERE pair = ?)")
            .param(pair)
            .query(Boolean.class)
            .single();
        if (exists) {
            return false;
        }
        try {
            jdbcClient.sql("INSERT INTO monitoring_state (pair, last_alert_time) VALUES (?, ?)")
                .param(pair)
                .param(nowTs)
                .update();
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    @Transactional
    public int clearAlerts() {
        return jdbcClient.sql("DELETE FROM alerts").update();
    }

    public Map<String, String> findAllSettings() {
        Map<String, String> settings = new LinkedHashMap<>();
        jdbcClient.sql("SELECT key, value FROM settings")
            .query(rs -> {
                settings.put(rs.getString(1), rs.getString(2));
            });
        return settings;
    }

    public Optional<String> findSetting(String key) {
        return jdbcClient.sql("SELECT value FROM settings WHERE key = ?")
            .param(key)
            .query(String.class)
            .optional();
    }

    @Transactional
    public void upsertSetting(String key, String value) {
        if (updateSetting(key, value) > 0) {
            return;
        }
        try {
            jdbcClient.sql("INSERT INTO settings (key, value) VALUES (?, ?)")
                .param(key)
                .param(value)
                .update();
        } catch (DuplicateKeyException ignored) {
            updateSetting(key, value);
        }
    }

    public Map<String, AlertPreference> findAllAlertPreferences() {
        List<AlertPreference> rows = jdbcClient.sql("""
                SELECT pair, enabled, alert_type, custom_threshold, custom_period,
                       enable_trend_consistency, lookback_years, price_high, price_low,
                       trigger_type, volatility_type, ma_short_period, ma_long_period, signal_type
                FROM alert_preferences
                ORDER BY pair ASC
                """)
            .query((rs, rowNum) -> mapAlertPreference(rs))
            .list();

        Map<String, AlertPreference> byPair = new LinkedHashMap<>();
        for (AlertPreference preference : rows) {
            byPair.put(preference.pair(), preference);
        }
        return byPair;
    }

    @Transactional
    public void upsertAlertPreference(AlertPreference preference) {
        if (updateAlertPreference(preference) > 0) {
            return;
        }
        try {
            insertAlertPreference(preference);
        } catch (DuplicateKeyException ignored) {
            updateAlertPreference(preference);
        }
    }

    private int updateSetting(String key, String value) {
        return jdbcClient.sql("UPDATE settings SET value = ? WHERE key = ?")
            .param(value)
            .param(key)
            .update();
    }

    private int updateAlertPreference(AlertPreference preference) {
        return jdbcClient.sql("""
                UPDATE alert_preferences
                SET enabled = ?,
                    alert_type = ?,
                    custom_threshold = ?,
                    custom_period = ?,
                    enable_trend_consistency = ?,
                    lookback_years = ?,
                    price_high = ?,
                    price_low = ?,
                    trigger_type = ?,
                    volatility_type = ?,
                    ma_short_period = ?,
                    ma_long_period = ?,
                    signal_type = ?
                WHERE pair = ?
                """)
            .param(preference.enabled())
            .param(preference.alertType())
            .param(preference.customThreshold())
            .param(preference.customPeriod())
            .param(preference.enableTrendConsistency())
            .param(preference.lookbackYears())
            .param(preference.priceHigh())
            .param(preference.priceLow())
            .param(preference.triggerType())
            .param(preference.volatilityType())
            .param(preference.maShortPeriod())
            .param(preference.maLongPeriod())
            .param(preference.signalType())
            .param(preference.pair())
            .update();
    }

    private void insertAlertPreference(AlertPreference preference) {
        jdbcClient.sql("""
                INSERT INTO alert_preferences (
                    pair, enabled, alert_type, custom_threshold, custom_period,
                    enable_trend_consistency, lookback_years, price_high, price_low,
                    trigger_type, volatility_type, ma_short_period, ma_long_period, signal_type
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .param(preference.pair())
            .param(preference.enabled())
            .param(preference.alertType())
            .param(preference.customThreshold())
            .param(preference.customPeriod())
            .param(preference.enableTrendConsistency())
            .param(preference.lookbackYears())
            .param(preference.priceHigh())
            .param(preference.priceLow())
            .param(preference.triggerType())
            .param(preference.volatilityType())
            .param(preference.maShortPeriod())
            .param(preference.maLongPeriod())
            .param(preference.signalType())
            .update();
    }

    private static AlertEntry mapAlert(ResultSet rs) throws SQLException {
        OffsetDateTime timestamp = rs.getObject("timestamp", OffsetDateTime.class);
        return new AlertEntry(
            rs.getLong("id"),
            rs.getString("pair"),
            rs.getObject("percent_change", Double.class),
            rs.getObject("old_rate", Double.class),
            rs.getObject("new_rate", Double.class),
            timestamp == null ? null : timestamp.toInstant().toString(),
            rs.getBoolean("email_sent"),
            rs.getString("alert_type"),
            rs.getObject("trigger_value", Double.class),
            rs.getObject("threshold_value", Double.class)
        );
    }

    private static AlertPreference mapAlertPreference(ResultSet rs) throws SQLException {
        return new AlertPreference(
            rs.getString("pair"),
            rs.getBoolean("enabled"),
            rs.getString("alert_type"),
            rs.getObject("custom_threshold", Double.class),
            rs.getObject("custom_period", Integer.class),
            rs.getBoolean("enable_trend_consistency"),
            rs.getInt("lookback_years"),
            rs.getObject("price_high", Double.class),
            rs.getObject("price_low", Double.class),
            rs.getString("trigger_type"),
            rs.getString("volatility_type"),
            rs.getInt("ma_short_period"),
            rs.getInt("ma_long_period"),
            rs.getString("signal_type")
        );
    }

    public record AlertEntry(
        long id,
        String pair,
        @JsonProperty("percent_change") Double percentChange,
        @JsonProperty("old_rate") Double oldRate,
        @JsonProperty("new_rate") Double newRate,
        String timestamp,
        @JsonProperty("email_sent") boolean emailSent,
        @JsonProperty("alert_type") String alertType,
        @JsonProperty("trigger_value") Double triggerValue,
        @JsonProperty("threshold_value") Double thresholdValue
    ) {
    }

    public record AlertPreference(
        String pair,
        boolean enabled,
        String alertType,
        Double customThreshold,
        Integer customPeriod,
        boolean enableTrendConsistency,
        int lookbackYears,
        Double priceHigh,
        Double priceLow,
        String triggerType,
        String volatilityType,
        int maShortPeriod,
        int maLongPeriod,
        String signalType
    ) {
    }
}
