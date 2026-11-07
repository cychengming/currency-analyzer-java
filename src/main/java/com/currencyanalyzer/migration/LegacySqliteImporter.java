package com.currencyanalyzer.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.legacy-import.enabled", havingValue = "true")
public class LegacySqliteImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacySqliteImporter.class);

    private final DataSource targetDataSource;
    private final ConfigurableApplicationContext applicationContext;
    private final boolean exitAfterImport;

    public LegacySqliteImporter(DataSource targetDataSource) {
        this(targetDataSource, null, false);
    }

    @Autowired
    public LegacySqliteImporter(
        DataSource targetDataSource,
        ConfigurableApplicationContext applicationContext,
        @Value("${app.legacy-import.exit-after:true}") boolean exitAfterImport
    ) {
        this.targetDataSource = targetDataSource;
        this.applicationContext = applicationContext;
        this.exitAfterImport = exitAfterImport;
    }

    @Override
    public void run(ApplicationArguments args) {
        String source = args.getOptionValues("source") == null || args.getOptionValues("source").isEmpty()
            ? null
            : args.getOptionValues("source").getFirst();

        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Usage: --app.legacy-import.enabled=true --source=/path/to/currency_monitor.db");
        }

        try {
            ImportSummary summary = importFrom(Path.of(source));
            summary.rowsByTable().forEach((table, count) ->
                log.info("Imported {} rows into {}", count, table));
            if (exitAfterImport && applicationContext != null) {
                System.exit(SpringApplication.exit(applicationContext, () -> 0));
            }
        } catch (Exception exception) {
            throw new IllegalStateException("SQLite import failed", exception);
        }
    }

    public ImportSummary importFrom(Path sourcePath) throws Exception {
        Path normalizedSource = sourcePath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedSource)) {
            throw new IllegalArgumentException("SQLite database does not exist: " + normalizedSource);
        }

        String sqliteUrl = "jdbc:sqlite:file:" + normalizedSource.toString().replace('\\', '/') + "?mode=ro";
        Map<String, Long> counts = new LinkedHashMap<>();

        try (Connection source = DriverManager.getConnection(sqliteUrl);
             Connection target = targetDataSource.getConnection()) {
            target.setAutoCommit(false);
            try {
                counts.put("settings", copySettings(source, target));
                counts.put("users", copyUsers(source, target));
                counts.put("alert_preferences", copyAlertPreferences(source, target));
                counts.put("monitoring_state", copyMonitoringState(source, target));
                counts.put("alerts", copyAlerts(source, target));
                counts.put("price_history", copyPriceHistory(source, target));
                counts.put("trade_journal", copyTradeJournal(source, target));
                resetPostgresSequences(target);
                target.commit();
            } catch (Exception exception) {
                target.rollback();
                throw exception;
            }
        }

        return new ImportSummary(counts);
    }

    private long copySettings(Connection source, Connection target) throws SQLException {
        String sql = "SELECT key, value FROM settings";
        return copyRows(source, target, sql, """
            INSERT INTO settings (key, value)
            VALUES (?, ?)
            ON CONFLICT DO NOTHING
            """, row -> {
            row.setString(1, row.requiredString("key"));
            row.setString(2, row.requiredString("value"));
        });
    }

    private long copyUsers(Connection source, Connection target) throws SQLException {
        String sql = "SELECT id, username, password, created_at FROM users";
        return copyRows(source, target, sql, """
            INSERT INTO users (id, username, password, created_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """, row -> {
            row.setLong(1, row.requiredLong("id"));
            row.setString(2, row.requiredString("username"));
            row.setString(3, row.requiredString("password"));
            row.setObject(4, row.parseTimestamp("created_at"));
        });
    }

    private long copyAlertPreferences(Connection source, Connection target) throws SQLException {
        String sql = """
            SELECT pair, enabled, alert_type, custom_threshold, custom_period,
                   enable_trend_consistency, lookback_years, price_high, price_low,
                   trigger_type, volatility_type, ma_short_period, ma_long_period, signal_type
            FROM alert_preferences
            """;
        return copyRows(source, target, sql, """
            INSERT INTO alert_preferences (
                pair, enabled, alert_type, custom_threshold, custom_period,
                enable_trend_consistency, lookback_years, price_high, price_low,
                trigger_type, volatility_type, ma_short_period, ma_long_period, signal_type
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """, row -> {
            row.setString(1, row.requiredString("pair"));
            row.setBoolean(2, row.booleanValue("enabled", true));
            row.setString(3, row.defaultString("alert_type", "percentage_change"));
            setNullableDouble(row, 4, "custom_threshold");
            setNullableInteger(row, 5, "custom_period");
            row.setBoolean(6, row.booleanValue("enable_trend_consistency", true));
            row.setInt(7, row.nullableInt("lookback_years", 5));
            setNullableDouble(row, 8, "price_high");
            setNullableDouble(row, 9, "price_low");
            row.setString(10, row.nullableString("trigger_type"));
            row.setString(11, row.nullableString("volatility_type"));
            row.setInt(12, row.nullableInt("ma_short_period", 10));
            row.setInt(13, row.nullableInt("ma_long_period", 50));
            row.setString(14, row.nullableString("signal_type"));
        });
    }

    private long copyMonitoringState(Connection source, Connection target) throws SQLException {
        String sql = "SELECT pair, last_alert_time FROM monitoring_state";
        return copyRows(source, target, sql, """
            INSERT INTO monitoring_state (pair, last_alert_time)
            VALUES (?, ?)
            ON CONFLICT DO NOTHING
            """, row -> {
            row.setString(1, row.requiredString("pair"));
            Double epochSeconds = row.nullableDouble("last_alert_time");
            row.setObject(2, epochSeconds == null
                ? null
                : Instant.ofEpochMilli(Math.round(epochSeconds * 1000.0)));
        });
    }

    private long copyAlerts(Connection source, Connection target) throws SQLException {
        String sql = """
            SELECT id, pair, percent_change, old_rate, new_rate, timestamp, email_sent,
                   alert_type, trigger_value, threshold_value
            FROM alerts
            """;
        return copyRows(source, target, sql, """
            INSERT INTO alerts (
                id, pair, percent_change, old_rate, new_rate, timestamp, email_sent,
                alert_type, trigger_value, threshold_value
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """, row -> {
            row.setLong(1, row.requiredLong("id"));
            row.setString(2, row.nullableString("pair"));
            setNullableDouble(row, 3, "percent_change");
            setNullableDouble(row, 4, "old_rate");
            setNullableDouble(row, 5, "new_rate");
            row.setObject(6, row.parseTimestamp("timestamp"));
            row.setBoolean(7, row.booleanValue("email_sent", false));
            row.setString(8, row.nullableString("alert_type"));
            setNullableDouble(row, 9, "trigger_value");
            setNullableDouble(row, 10, "threshold_value");
        });
    }

    private long copyPriceHistory(Connection source, Connection target) throws SQLException {
        String sql = "SELECT id, pair, date, rate, fetched_at FROM price_history";
        return copyRows(source, target, sql, """
            INSERT INTO price_history (id, pair, date, rate, fetched_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """, row -> {
            row.setLong(1, row.requiredLong("id"));
            row.setString(2, row.requiredString("pair"));
            row.setObject(3, java.time.LocalDate.parse(row.requiredString("date")));
            row.setDouble(4, row.requiredDouble("rate"));
            row.setObject(5, row.parseTimestamp("fetched_at"));
        });
    }

    private long copyTradeJournal(Connection source, Connection target) throws SQLException {
        String sql = """
            SELECT id, username, pair, side, status, entry_price, stop_price, close_price,
                   quantity, risk_amount_usd, risk_pct_of_equity, atr, sigma,
                   entry_reason, close_reason, notes, opened_at, closed_at, created_at, updated_at
            FROM trade_journal
            """;
        return copyRows(source, target, sql, """
            INSERT INTO trade_journal (
                id, username, pair, side, status, entry_price, stop_price, close_price,
                quantity, risk_amount_usd, risk_pct_of_equity, atr, sigma,
                entry_reason, close_reason, notes, opened_at, closed_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """, row -> {
            row.setLong(1, row.requiredLong("id"));
            row.setString(2, row.requiredString("username"));
            row.setString(3, row.requiredString("pair"));
            row.setString(4, row.requiredString("side"));
            row.setString(5, row.defaultString("status", "open"));
            setNullableDouble(row, 6, "entry_price");
            setNullableDouble(row, 7, "stop_price");
            setNullableDouble(row, 8, "close_price");
            setNullableDouble(row, 9, "quantity");
            setNullableDouble(row, 10, "risk_amount_usd");
            setNullableDouble(row, 11, "risk_pct_of_equity");
            setNullableDouble(row, 12, "atr");
            setNullableDouble(row, 13, "sigma");
            row.setString(14, row.nullableString("entry_reason"));
            row.setString(15, row.nullableString("close_reason"));
            row.setString(16, row.nullableString("notes"));
            row.setObject(17, row.parseTimestamp("opened_at"));
            row.setObject(18, row.parseTimestamp("closed_at"));
            row.setObject(19, row.parseTimestamp("created_at"));
            row.setObject(20, row.parseTimestamp("updated_at"));
        });
    }

    private long copyRows(
        Connection source,
        Connection target,
        String selectSql,
        String insertSql,
        RowBinder binder
    ) throws SQLException {
        long imported = 0;
        try (PreparedStatement select = source.prepareStatement(selectSql);
             ResultSet resultSet = select.executeQuery();
             PreparedStatement insert = target.prepareStatement(insertSql)) {
            while (resultSet.next()) {
                binder.bind(new BoundRow(resultSet, insert));
                imported += insert.executeUpdate();
            }
        }
        return imported;
    }

    private static void resetPostgresSequences(Connection target) throws SQLException {
        DatabaseMetaData metadata = target.getMetaData();
        if (!metadata.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql")) {
            return;
        }

        resetSequence(target, "users");
        resetSequence(target, "alerts");
        resetSequence(target, "price_history");
        resetSequence(target, "trade_journal");
    }

    private static void resetSequence(Connection target, String table) throws SQLException {
        String sql = """
            SELECT setval(
                pg_get_serial_sequence(?, 'id'),
                COALESCE((SELECT MAX(id) FROM %s), 1),
                EXISTS (SELECT 1 FROM %s)
            )
            """.formatted(table, table);
        try (PreparedStatement statement = target.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.execute();
        }
    }

    private static Instant parseTimestampColumn(ResultSet resultSet, String column) throws SQLException {
        String value = resultSet.getString(column);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (Exception ignored) {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        }
    }

    private record BoundRow(ResultSet resultSet, PreparedStatement statement) {
        void setString(int index, String value) throws SQLException {
            statement.setString(index, value);
        }

        void setLong(int index, long value) throws SQLException {
            statement.setLong(index, value);
        }

        void setBoolean(int index, boolean value) throws SQLException {
            statement.setBoolean(index, value);
        }

        void setInt(int index, int value) throws SQLException {
            statement.setInt(index, value);
        }

        void setDouble(int index, double value) throws SQLException {
            statement.setDouble(index, value);
        }

        void setObject(int index, Object value) throws SQLException {
            statement.setObject(index, value);
        }

        String requiredString(String column) throws SQLException {
            return resultSet.getString(column);
        }

        String nullableString(String column) throws SQLException {
            return resultSet.getString(column);
        }

        String defaultString(String column, String defaultValue) throws SQLException {
            String value = resultSet.getString(column);
            return value == null ? defaultValue : value;
        }

        long requiredLong(String column) throws SQLException {
            long value = resultSet.getLong(column);
            if (resultSet.wasNull()) {
                throw new SQLException("Missing required value for " + column);
            }
            return value;
        }

        double requiredDouble(String column) throws SQLException {
            double value = resultSet.getDouble(column);
            if (resultSet.wasNull()) {
                throw new SQLException("Missing required value for " + column);
            }
            return value;
        }

        Double nullableDouble(String column) throws SQLException {
            double value = resultSet.getDouble(column);
            return resultSet.wasNull() ? null : value;
        }

        Integer nullableInt(String column, int defaultValue) throws SQLException {
            int value = resultSet.getInt(column);
            return resultSet.wasNull() ? defaultValue : value;
        }

        boolean booleanValue(String column, boolean defaultValue) throws SQLException {
            int value = resultSet.getInt(column);
            return resultSet.wasNull() ? defaultValue : value != 0;
        }

        Instant parseTimestamp(String column) throws SQLException {
            return parseTimestampColumn(resultSet, column);
        }
    }

    private static void setNullableDouble(BoundRow row, int index, String column) throws SQLException {
        Double value = row.nullableDouble(column);
        if (value == null) {
            row.setObject(index, null);
        } else {
            row.setDouble(index, value);
        }
    }

    private static void setNullableInteger(BoundRow row, int index, String column) throws SQLException {
        int value = row.resultSet().getInt(column);
        if (row.resultSet().wasNull()) {
            row.setObject(index, null);
        } else {
            row.setInt(index, value);
        }
    }

    @FunctionalInterface
    private interface RowBinder {
        void bind(BoundRow row) throws SQLException;
    }

    public record ImportSummary(Map<String, Long> rowsByTable) {
        public ImportSummary {
            rowsByTable = Map.copyOf(rowsByTable);
        }
    }
}
