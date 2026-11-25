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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserAlertRuleRepository {

    public static final String COND_CROSS_ABOVE = "cross_above";
    public static final String COND_CROSS_BELOW = "cross_below";
    public static final String COND_PCT_MOVE = "pct_move";
    public static final String COND_MA_CROSSOVER = "ma_crossover";

    public static final String FREQ_ONCE = "ONCE";
    public static final String FREQ_RECURRING = "RECURRING";

    public static final String STATUS_ARMED = "ARMED";
    public static final String STATUS_TRIGGERED = "TRIGGERED";
    public static final String STATUS_PAUSED = "PAUSED";

    private static final String SELECT_RULE_COLUMNS = """
            SELECT id, user_id, pair, nickname, condition_type, target_value,
                   window_days, ma_short_period, ma_long_period, signal_type,
                   frequency, cooldown_seconds, notify_email, status,
                   last_evaluated_rate, last_triggered_at, created_at, updated_at
            FROM alert_rules
            """;

    private static final String SELECT_USER_ALERT_COLUMNS = """
            SELECT id, user_id, rule_id, rule_name, pair, percent_change,
                   old_rate, new_rate, timestamp, email_sent, alert_type,
                   trigger_value, threshold_value, read_at
            FROM alerts
            """;

    private final JdbcClient jdbcClient;

    public UserAlertRuleRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public long countAllRules() {
        Long count = jdbcClient.sql("SELECT COUNT(*) FROM alert_rules")
            .query(Long.class)
            .single();
        return count == null ? 0L : count;
    }

    public List<AlertRule> findAllArmedRules() {
        return jdbcClient.sql(SELECT_RULE_COLUMNS + " WHERE status = ? ORDER BY pair ASC, created_at ASC")
            .param(STATUS_ARMED)
            .query((rs, rowNum) -> mapRule(rs))
            .list();
    }

    public List<AlertRule> findRulesByUser(long userId) {
        return jdbcClient.sql(SELECT_RULE_COLUMNS + " WHERE user_id = ? ORDER BY created_at DESC, id ASC")
            .param(userId)
            .query((rs, rowNum) -> mapRule(rs))
            .list();
    }

    public Optional<AlertRule> findRuleByIdForUser(UUID id, long userId) {
        return jdbcClient.sql(SELECT_RULE_COLUMNS + " WHERE id = ? AND user_id = ?")
            .param(id)
            .param(userId)
            .query((rs, rowNum) -> mapRule(rs))
            .optional();
    }

    @Transactional
    public AlertRule insertRule(AlertRule rule) {
        jdbcClient.sql("""
                INSERT INTO alert_rules (
                    id, user_id, pair, nickname, condition_type, target_value,
                    window_days, ma_short_period, ma_long_period, signal_type,
                    frequency, cooldown_seconds, notify_email, status,
                    last_evaluated_rate, last_triggered_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .param(rule.id())
            .param(rule.userId())
            .param(rule.pair())
            .param(rule.nickname())
            .param(rule.conditionType())
            .param(rule.targetValue())
            .param(rule.windowDays())
            .param(rule.maShortPeriod())
            .param(rule.maLongPeriod())
            .param(rule.signalType())
            .param(rule.frequency())
            .param(rule.cooldownSeconds())
            .param(rule.notifyEmail())
            .param(rule.status())
            .param(rule.lastEvaluatedRate())
            .param(toOffsetDateTime(rule.lastTriggeredAt()))
            .param(toOffsetDateTime(rule.createdAt()))
            .param(toOffsetDateTime(rule.updatedAt()))
            .update();
        return rule;
    }

    @Transactional
    public boolean updateRuleForUser(AlertRule rule) {
        int updated = jdbcClient.sql("""
                UPDATE alert_rules
                SET pair = ?, nickname = ?, condition_type = ?, target_value = ?,
                    window_days = ?, ma_short_period = ?, ma_long_period = ?, signal_type = ?,
                    frequency = ?, cooldown_seconds = ?, notify_email = ?, status = ?,
                    last_evaluated_rate = ?, last_triggered_at = ?, updated_at = ?
                WHERE id = ? AND user_id = ?
                """)
            .param(rule.pair())
            .param(rule.nickname())
            .param(rule.conditionType())
            .param(rule.targetValue())
            .param(rule.windowDays())
            .param(rule.maShortPeriod())
            .param(rule.maLongPeriod())
            .param(rule.signalType())
            .param(rule.frequency())
            .param(rule.cooldownSeconds())
            .param(rule.notifyEmail())
            .param(rule.status())
            .param(rule.lastEvaluatedRate())
            .param(toOffsetDateTime(rule.lastTriggeredAt()))
            .param(toOffsetDateTime(rule.updatedAt()))
            .param(rule.id())
            .param(rule.userId())
            .update();
        return updated > 0;
    }

    @Transactional
    public boolean deleteRuleForUser(UUID id, long userId) {
        int deleted = jdbcClient.sql("DELETE FROM alert_rules WHERE id = ? AND user_id = ?")
            .param(id)
            .param(userId)
            .update();
        return deleted > 0;
    }

    @Transactional
    public void updateLastEvaluatedRate(UUID ruleId, double rate, Instant now) {
        jdbcClient.sql("""
                UPDATE alert_rules
                SET last_evaluated_rate = ?, updated_at = ?
                WHERE id = ?
                """)
            .param(rate)
            .param(toOffsetDateTime(now))
            .param(ruleId)
            .update();
    }

    @Transactional
    public boolean claimOnceTrigger(UUID ruleId, double currentRate, Instant now) {
        OffsetDateTime nowTs = toOffsetDateTime(now);
        int updated = jdbcClient.sql("""
                UPDATE alert_rules
                SET status = ?, last_triggered_at = ?, last_evaluated_rate = ?, updated_at = ?
                WHERE id = ? AND status = ?
                """)
            .param(STATUS_TRIGGERED)
            .param(nowTs)
            .param(currentRate)
            .param(nowTs)
            .param(ruleId)
            .param(STATUS_ARMED)
            .update();
        return updated > 0;
    }

    @Transactional
    public boolean claimRecurringTrigger(UUID ruleId, double currentRate, Instant now, Instant cutoff) {
        OffsetDateTime nowTs = toOffsetDateTime(now);
        OffsetDateTime cutoffTs = toOffsetDateTime(cutoff);
        int updated = jdbcClient.sql("""
                UPDATE alert_rules
                SET last_triggered_at = ?, last_evaluated_rate = ?, updated_at = ?
                WHERE id = ? AND status = ?
                  AND (last_triggered_at IS NULL OR last_triggered_at <= ?)
                """)
            .param(nowTs)
            .param(currentRate)
            .param(nowTs)
            .param(ruleId)
            .param(STATUS_ARMED)
            .param(cutoffTs)
            .update();
        return updated > 0;
    }

    @Transactional
    public UserAlertEntry insertUserAlert(
        long userId,
        UUID ruleId,
        String ruleName,
        String pair,
        double percentChange,
        double oldRate,
        double newRate,
        Instant timestamp,
        String alertType,
        double triggerValue,
        double thresholdValue
    ) {
        OffsetDateTime ts = toOffsetDateTime(timestamp);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                INSERT INTO alerts (
                    user_id, rule_id, rule_name, pair, percent_change, old_rate, new_rate,
                    timestamp, email_sent, alert_type, trigger_value, threshold_value
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .param(userId)
            .param(ruleId)
            .param(ruleName)
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
            throw new IllegalStateException("Inserted user alert did not return a generated id");
        }
        return new UserAlertEntry(
            key.longValue(),
            userId,
            ruleId,
            ruleName,
            pair,
            percentChange,
            oldRate,
            newRate,
            timestamp.toString(),
            false,
            alertType,
            triggerValue,
            thresholdValue,
            null
        );
    }

    public List<UserAlertEntry> listAlertsByUser(long userId, int limit) {
        return jdbcClient.sql(SELECT_USER_ALERT_COLUMNS + " WHERE user_id = ? ORDER BY timestamp DESC, id DESC LIMIT ?")
            .param(userId)
            .param(limit)
            .query((rs, rowNum) -> mapUserAlert(rs))
            .list();
    }

    public Optional<UserAlertEntry> findUserAlertById(long alertId) {
        return jdbcClient.sql(SELECT_USER_ALERT_COLUMNS + " WHERE id = ?")
            .param(alertId)
            .query((rs, rowNum) -> mapUserAlert(rs))
            .optional();
    }

    @Transactional
    public boolean markAlertReadForUser(long alertId, long userId, Instant now) {
        int updated = jdbcClient.sql("""
                UPDATE alerts
                SET read_at = COALESCE(read_at, ?)
                WHERE id = ? AND user_id = ?
                """)
            .param(toOffsetDateTime(now))
            .param(alertId)
            .param(userId)
            .update();
        return updated > 0;
    }

    @Transactional
    public int markAllAlertsReadForUser(long userId, Instant now) {
        return jdbcClient.sql("""
                UPDATE alerts
                SET read_at = ?
                WHERE user_id = ? AND read_at IS NULL
                """)
            .param(toOffsetDateTime(now))
            .param(userId)
            .update();
    }

    @Transactional
    public int clearAlertsForUser(long userId) {
        return jdbcClient.sql("DELETE FROM alerts WHERE user_id = ?")
            .param(userId)
            .update();
    }

    public String findAlertEmail(long userId) {
        return jdbcClient.sql("SELECT alert_email FROM user_alert_settings WHERE user_id = ?")
            .param(userId)
            .query(String.class)
            .optional()
            .orElse("");
    }

    @Transactional
    public void upsertAlertEmail(long userId, String email, Instant now) {
        OffsetDateTime nowTs = toOffsetDateTime(now);
        int updated = jdbcClient.sql("""
                UPDATE user_alert_settings
                SET alert_email = ?, updated_at = ?
                WHERE user_id = ?
                """)
            .param(email)
            .param(nowTs)
            .param(userId)
            .update();
        if (updated > 0) {
            return;
        }
        try {
            jdbcClient.sql("""
                    INSERT INTO user_alert_settings (user_id, alert_email, updated_at)
                    VALUES (?, ?, ?)
                    """)
                .param(userId)
                .param(email)
                .param(nowTs)
                .update();
        } catch (DuplicateKeyException ignored) {
            jdbcClient.sql("""
                    UPDATE user_alert_settings
                    SET alert_email = ?, updated_at = ?
                    WHERE user_id = ?
                    """)
                .param(email)
                .param(nowTs)
                .param(userId)
                .update();
        }
    }

    public List<String> listFavoritePairs(long userId) {
        return jdbcClient.sql("SELECT pair FROM user_favorite_pairs WHERE user_id = ? ORDER BY pair ASC")
            .param(userId)
            .query(String.class)
            .list();
    }

    @Transactional
    public void replaceFavoritePairs(long userId, List<String> pairs, Instant now) {
        jdbcClient.sql("DELETE FROM user_favorite_pairs WHERE user_id = ?")
            .param(userId)
            .update();
        OffsetDateTime nowTs = toOffsetDateTime(now);
        for (String pair : pairs) {
            jdbcClient.sql("""
                    INSERT INTO user_favorite_pairs (user_id, pair, created_at)
                    VALUES (?, ?, ?)
                    """)
                .param(userId)
                .param(pair)
                .param(nowTs)
                .update();
        }
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant toInstant(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }

    private static AlertRule mapRule(ResultSet rs) throws SQLException {
        return new AlertRule(
            rs.getObject("id", UUID.class),
            rs.getLong("user_id"),
            rs.getString("pair"),
            rs.getString("nickname"),
            rs.getString("condition_type"),
            rs.getObject("target_value", Double.class),
            rs.getInt("window_days"),
            rs.getInt("ma_short_period"),
            rs.getInt("ma_long_period"),
            rs.getString("signal_type"),
            rs.getString("frequency"),
            rs.getInt("cooldown_seconds"),
            rs.getBoolean("notify_email"),
            rs.getString("status"),
            rs.getObject("last_evaluated_rate", Double.class),
            toInstant(rs.getObject("last_triggered_at", OffsetDateTime.class)),
            toInstant(rs.getObject("created_at", OffsetDateTime.class)),
            toInstant(rs.getObject("updated_at", OffsetDateTime.class))
        );
    }

    private static UserAlertEntry mapUserAlert(ResultSet rs) throws SQLException {
        OffsetDateTime timestamp = rs.getObject("timestamp", OffsetDateTime.class);
        OffsetDateTime readAt = rs.getObject("read_at", OffsetDateTime.class);
        return new UserAlertEntry(
            rs.getLong("id"),
            rs.getObject("user_id", Long.class),
            rs.getObject("rule_id", UUID.class),
            rs.getString("rule_name"),
            rs.getString("pair"),
            rs.getObject("percent_change", Double.class),
            rs.getObject("old_rate", Double.class),
            rs.getObject("new_rate", Double.class),
            timestamp == null ? null : timestamp.toInstant().toString(),
            rs.getBoolean("email_sent"),
            rs.getString("alert_type"),
            rs.getObject("trigger_value", Double.class),
            rs.getObject("threshold_value", Double.class),
            readAt == null ? null : readAt.toInstant().toString()
        );
    }

    public record AlertRule(
        UUID id,
        long userId,
        String pair,
        String nickname,
        String conditionType,
        Double targetValue,
        int windowDays,
        int maShortPeriod,
        int maLongPeriod,
        String signalType,
        String frequency,
        int cooldownSeconds,
        boolean notifyEmail,
        String status,
        Double lastEvaluatedRate,
        Instant lastTriggeredAt,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    public record UserAlertEntry(
        long id,
        @JsonProperty("user_id") Long userId,
        @JsonProperty("rule_id") UUID ruleId,
        @JsonProperty("rule_name") String ruleName,
        String pair,
        @JsonProperty("percent_change") Double percentChange,
        @JsonProperty("old_rate") Double oldRate,
        @JsonProperty("new_rate") Double newRate,
        String timestamp,
        @JsonProperty("email_sent") boolean emailSent,
        @JsonProperty("alert_type") String alertType,
        @JsonProperty("trigger_value") Double triggerValue,
        @JsonProperty("threshold_value") Double thresholdValue,
        @JsonProperty("read_at") String readAt
    ) {
    }
}
