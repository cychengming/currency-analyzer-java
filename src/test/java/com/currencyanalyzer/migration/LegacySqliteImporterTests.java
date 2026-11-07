package com.currencyanalyzer.migration;

import com.currencyanalyzer.CurrencyAnalyzerApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = CurrencyAnalyzerApplication.class)
class LegacySqliteImporterTests {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbcClient;

    @TempDir
    Path tempDir;

    @Test
    void importsLegacyApplicationTables() throws Exception {
        Path sourcePath = tempDir.resolve("currency_monitor.db");
        createLegacyDatabase(sourcePath);

        LegacySqliteImporter.ImportSummary summary =
            new LegacySqliteImporter(dataSource).importFrom(sourcePath);

        assertThat(summary.rowsByTable())
            .containsEntry("settings", 1L)
            .containsEntry("users", 1L)
            .containsEntry("alert_preferences", 1L)
            .containsEntry("monitoring_state", 1L)
            .containsEntry("alerts", 1L)
            .containsEntry("price_history", 1L)
            .containsEntry("trade_journal", 1L);

        assertThat(count("settings")).isGreaterThanOrEqualTo(1);
        assertThat(count("users")).isGreaterThanOrEqualTo(1);
        assertThat(count("alerts")).isGreaterThanOrEqualTo(1);
        assertThat(count("alert_preferences")).isGreaterThanOrEqualTo(1);
        assertThat(count("monitoring_state")).isGreaterThanOrEqualTo(1);
        assertThat(count("price_history")).isGreaterThanOrEqualTo(1);
        assertThat(count("trade_journal")).isGreaterThanOrEqualTo(1);
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table)
            .query(Long.class)
            .single();
    }

    private static void createLegacyDatabase(Path path) throws Exception {
        try (Connection connection = DriverManager.getConnection(
            "jdbc:sqlite:" + path.toAbsolutePath().toString().replace('\\', '/')
        ); Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT)
                """);
            statement.execute("""
                CREATE TABLE users (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT UNIQUE NOT NULL,
                    password TEXT NOT NULL,
                    created_at TEXT
                )
                """);
            statement.execute("""
                CREATE TABLE alert_preferences (
                    pair TEXT PRIMARY KEY,
                    enabled INTEGER,
                    alert_type TEXT,
                    custom_threshold REAL,
                    custom_period INTEGER,
                    enable_trend_consistency INTEGER,
                    lookback_years INTEGER,
                    price_high REAL,
                    price_low REAL,
                    trigger_type TEXT,
                    volatility_type TEXT,
                    ma_short_period INTEGER,
                    ma_long_period INTEGER,
                    signal_type TEXT
                )
                """);
            statement.execute("""
                CREATE TABLE monitoring_state (
                    pair TEXT PRIMARY KEY,
                    last_alert_time REAL
                )
                """);
            statement.execute("""
                CREATE TABLE alerts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    pair TEXT,
                    percent_change REAL,
                    old_rate REAL,
                    new_rate REAL,
                    timestamp TEXT,
                    email_sent INTEGER,
                    alert_type TEXT,
                    trigger_value REAL,
                    threshold_value REAL
                )
                """);
            statement.execute("""
                CREATE TABLE price_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    pair TEXT NOT NULL,
                    date TEXT NOT NULL,
                    rate REAL NOT NULL,
                    fetched_at TEXT NOT NULL,
                    UNIQUE(pair, date)
                )
                """);
            statement.execute("""
                CREATE TABLE trade_journal (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL,
                    pair TEXT NOT NULL,
                    side TEXT NOT NULL,
                    status TEXT,
                    entry_price REAL,
                    stop_price REAL,
                    close_price REAL,
                    quantity REAL,
                    risk_amount_usd REAL,
                    risk_pct_of_equity REAL,
                    atr REAL,
                    sigma REAL,
                    entry_reason TEXT,
                    close_reason TEXT,
                    notes TEXT,
                    opened_at TEXT,
                    closed_at TEXT,
                    created_at TEXT,
                    updated_at TEXT
                )
                """);

            statement.execute("""
                INSERT INTO settings (key, value) VALUES ('migration_marker', 'ok')
                """);
            statement.execute("""
                INSERT INTO users (id, username, password, created_at)
                VALUES (101, 'legacy-user', 'salt$hash', '2026-01-01T00:00:00')
                """);
            statement.execute("""
                INSERT INTO alert_preferences (
                    pair, enabled, alert_type, custom_threshold, custom_period,
                    enable_trend_consistency, lookback_years, price_high, price_low,
                    trigger_type, volatility_type, ma_short_period, ma_long_period, signal_type
                ) VALUES ('EUR/USD', 1, 'percentage_change', 2.0, 30, 1, 5,
                    NULL, NULL, NULL, NULL, 10, 50, NULL)
                """);
            statement.execute("""
                INSERT INTO monitoring_state (pair, last_alert_time)
                VALUES ('EUR/USD', 1767225600.5)
                """);
            statement.execute("""
                INSERT INTO alerts (
                    id, pair, percent_change, old_rate, new_rate, timestamp, email_sent,
                    alert_type, trigger_value, threshold_value
                ) VALUES (201, 'EUR/USD', 2.1, 1.08, 1.10268,
                    '2026-01-02T00:00:00', 1, 'percentage_change', 2.1, 2.0)
                """);
            statement.execute("""
                INSERT INTO price_history (id, pair, date, rate, fetched_at)
                VALUES (301, 'EUR/USD', '2026-01-02', 1.10268, '2026-01-02T01:00:00')
                """);
            statement.execute("""
                INSERT INTO trade_journal (
                    id, username, pair, side, status, entry_price, stop_price, close_price,
                    quantity, risk_amount_usd, risk_pct_of_equity, atr, sigma,
                    entry_reason, close_reason, notes, opened_at, closed_at, created_at, updated_at
                ) VALUES (
                    401, 'legacy-user', 'EUR/USD', 'long', 'open', 1.10, 1.08, NULL,
                    1000, 20, 2.0, 0.01, 0.02,
                    'breakout', NULL, 'migration test',
                    '2026-01-03T00:00:00', NULL, '2026-01-03T00:00:00', '2026-01-03T00:00:00'
                )
                """);
        }
    }
}
