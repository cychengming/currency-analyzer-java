package com.currencyanalyzer.trade;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class TradeJournalRepository {

    private static final String STATUS_OPEN = "open";
    private static final String STATUS_CLOSED = "closed";

    private final JdbcTemplate jdbcTemplate;

    public TradeJournalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TradeRecord> findByUsername(String username, int limit) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        return jdbcTemplate.query(
            """
            SELECT id, username, pair, side, status, entry_price, stop_price, close_price,
                   quantity, risk_amount_usd, risk_pct_of_equity, atr, sigma,
                   entry_reason, close_reason, notes, opened_at, closed_at, created_at, updated_at
            FROM trade_journal
            WHERE username = ?
            ORDER BY opened_at DESC, id DESC
            LIMIT ?
            """,
            ROW_MAPPER,
            username,
            safeLimit
        );
    }

    public List<TradeRecord> findOpenByUsername(String username) {
        return jdbcTemplate.query(
            """
            SELECT id, username, pair, side, status, entry_price, stop_price, close_price,
                   quantity, risk_amount_usd, risk_pct_of_equity, atr, sigma,
                   entry_reason, close_reason, notes, opened_at, closed_at, created_at, updated_at
            FROM trade_journal
            WHERE username = ? AND status = ?
            ORDER BY opened_at DESC, id DESC
            """,
            ROW_MAPPER,
            username,
            STATUS_OPEN
        );
    }

    public Optional<TradeRecord> findByIdAndUsername(long id, String username) {
        List<TradeRecord> rows = jdbcTemplate.query(
            """
            SELECT id, username, pair, side, status, entry_price, stop_price, close_price,
                   quantity, risk_amount_usd, risk_pct_of_equity, atr, sigma,
                   entry_reason, close_reason, notes, opened_at, closed_at, created_at, updated_at
            FROM trade_journal
            WHERE id = ? AND username = ?
            """,
            ROW_MAPPER,
            id,
            username
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public DailyRiskAggregate aggregateOpenedOnUtcDate(String username, LocalDate utcDate) {
        Instant start = utcDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = utcDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(SUM(risk_amount_usd), 0.0) AS total_risk,
                   COUNT(*) AS trade_count
            FROM trade_journal
            WHERE username = ? AND opened_at >= ? AND opened_at < ?
            """,
            (rs, rowNum) -> new DailyRiskAggregate(rs.getDouble("total_risk"), rs.getInt("trade_count")),
            username,
            Timestamp.from(start),
            Timestamp.from(end)
        );
    }

    public DailyRiskAggregate aggregateOpenRisk(String username) {
        return jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(SUM(risk_amount_usd), 0.0) AS total_risk,
                   COUNT(*) AS trade_count
            FROM trade_journal
            WHERE username = ? AND status = ?
            """,
            (rs, rowNum) -> new DailyRiskAggregate(rs.getDouble("total_risk"), rs.getInt("trade_count")),
            username,
            STATUS_OPEN
        );
    }

    public TradeRecord insert(NewTradeRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                INSERT INTO trade_journal (
                    username, pair, side, status, entry_price, stop_price, close_price,
                    quantity, risk_amount_usd, risk_pct_of_equity, atr, sigma,
                    entry_reason, close_reason, notes, opened_at, closed_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, ?, ?)
                """,
                new String[]{"id"}
            );
            ps.setString(1, row.username());
            ps.setString(2, row.pair());
            ps.setString(3, row.side());
            ps.setString(4, STATUS_OPEN);
            ps.setDouble(5, row.entryPrice());
            ps.setDouble(6, row.stopPrice());
            ps.setDouble(7, row.quantity());
            ps.setDouble(8, row.riskAmountUsd());
            setNullableDouble(ps, 9, row.riskPctOfEquity());
            setNullableDouble(ps, 10, row.atr());
            setNullableDouble(ps, 11, row.sigma());
            ps.setString(12, row.entryReason());
            ps.setString(13, row.notes());
            Timestamp nowTs = Timestamp.from(row.now());
            ps.setTimestamp(14, nowTs);
            ps.setTimestamp(15, nowTs);
            ps.setTimestamp(16, nowTs);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to retrieve generated trade_journal id");
        }
        return findByIdAndUsername(key.longValue(), row.username())
            .orElseThrow(() -> new IllegalStateException("Inserted trade journal entry was not found"));
    }

    public Optional<TradeRecord> updateEntry(long id, String username, UpdatedTradeRow row) {
        int updated = jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                UPDATE trade_journal
                SET pair = ?, side = ?, entry_price = ?, stop_price = ?, quantity = ?,
                    risk_amount_usd = ?, risk_pct_of_equity = ?, atr = ?, sigma = ?,
                    entry_reason = ?, notes = ?, close_price = ?, close_reason = ?, updated_at = ?
                WHERE id = ? AND username = ?
                """
            );
            ps.setString(1, row.pair());
            ps.setString(2, row.side());
            ps.setDouble(3, row.entryPrice());
            ps.setDouble(4, row.stopPrice());
            ps.setDouble(5, row.quantity());
            ps.setDouble(6, row.riskAmountUsd());
            setNullableDouble(ps, 7, row.riskPctOfEquity());
            setNullableDouble(ps, 8, row.atr());
            setNullableDouble(ps, 9, row.sigma());
            ps.setString(10, row.entryReason());
            ps.setString(11, row.notes());
            setNullableDouble(ps, 12, row.closePrice());
            ps.setString(13, row.closeReason());
            ps.setTimestamp(14, Timestamp.from(row.updatedAt()));
            ps.setLong(15, id);
            ps.setString(16, username);
            return ps;
        });
        if (updated == 0) {
            return Optional.empty();
        }
        return findByIdAndUsername(id, username);
    }

    public Optional<TradeRecord> closeTrade(
        long id,
        String username,
        Double closePrice,
        String closeReason,
        Instant now
    ) {
        int updated = jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                """
                UPDATE trade_journal
                SET status = ?, close_price = ?, close_reason = ?, closed_at = ?, updated_at = ?
                WHERE id = ? AND username = ?
                """
            );
            ps.setString(1, STATUS_CLOSED);
            setNullableDouble(ps, 2, closePrice);
            ps.setString(3, closeReason);
            Timestamp nowTs = Timestamp.from(now);
            ps.setTimestamp(4, nowTs);
            ps.setTimestamp(5, nowTs);
            ps.setLong(6, id);
            ps.setString(7, username);
            return ps;
        });
        if (updated == 0) {
            return Optional.empty();
        }
        return findByIdAndUsername(id, username);
    }

    public Optional<Double> findUserDailyRiskLimitPct(String username) {
        List<Double> rows = jdbcTemplate.query(
            "SELECT daily_risk_limit_pct FROM user_trade_settings WHERE username = ?",
            (rs, rowNum) -> rs.getDouble("daily_risk_limit_pct"),
            username
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public Optional<Double> findGlobalDefaultDailyRiskLimitPct() {
        List<String> rows = jdbcTemplate.query(
            "SELECT value FROM settings WHERE key = 'daily_risk_limit_pct'",
            (rs, rowNum) -> rs.getString("value")
        );
        if (rows.isEmpty() || rows.getFirst() == null || rows.getFirst().isBlank()) {
            return Optional.empty();
        }
        try {
            double v = Double.parseDouble(rows.getFirst().trim());
            return Double.isFinite(v) && v > 0 ? Optional.of(v) : Optional.empty();
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    public void upsertUserDailyRiskLimitPct(String username, double limitPct, Instant now) {
        int updated = jdbcTemplate.update(
            "UPDATE user_trade_settings SET daily_risk_limit_pct = ?, updated_at = ? WHERE username = ?",
            limitPct,
            Timestamp.from(now),
            username
        );
        if (updated == 0) {
            jdbcTemplate.update(
                "INSERT INTO user_trade_settings (username, daily_risk_limit_pct, updated_at) VALUES (?, ?, ?)",
                username,
                limitPct,
                Timestamp.from(now)
            );
        }
    }

    private static void setNullableDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null || !Double.isFinite(value)) {
            ps.setNull(index, Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }

    private static Double getNullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant getInstant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static final RowMapper<TradeRecord> ROW_MAPPER = (rs, rowNum) -> new TradeRecord(
        rs.getLong("id"),
        rs.getString("username"),
        rs.getString("pair"),
        rs.getString("side"),
        rs.getString("status"),
        getNullableDouble(rs, "entry_price"),
        getNullableDouble(rs, "stop_price"),
        getNullableDouble(rs, "close_price"),
        getNullableDouble(rs, "quantity"),
        getNullableDouble(rs, "risk_amount_usd"),
        getNullableDouble(rs, "risk_pct_of_equity"),
        getNullableDouble(rs, "atr"),
        getNullableDouble(rs, "sigma"),
        rs.getString("entry_reason"),
        rs.getString("close_reason"),
        rs.getString("notes"),
        getInstant(rs, "opened_at"),
        getInstant(rs, "closed_at"),
        getInstant(rs, "created_at"),
        getInstant(rs, "updated_at")
    );

    public record DailyRiskAggregate(double totalRiskUsd, int tradeCount) {
    }

    public record NewTradeRow(
        String username,
        String pair,
        String side,
        double entryPrice,
        double stopPrice,
        double quantity,
        double riskAmountUsd,
        Double riskPctOfEquity,
        Double atr,
        Double sigma,
        String entryReason,
        String notes,
        Instant now
    ) {
    }

    public record UpdatedTradeRow(
        String pair,
        String side,
        double entryPrice,
        double stopPrice,
        double quantity,
        double riskAmountUsd,
        Double riskPctOfEquity,
        Double atr,
        Double sigma,
        String entryReason,
        String notes,
        Double closePrice,
        String closeReason,
        Instant updatedAt
    ) {
    }

    public record TradeRecord(
        long id,
        String username,
        String pair,
        String side,
        String status,
        Double entryPrice,
        Double stopPrice,
        Double closePrice,
        Double quantity,
        Double riskAmountUsd,
        Double riskPctOfEquity,
        Double atr,
        Double sigma,
        String entryReason,
        String closeReason,
        String notes,
        Instant openedAt,
        Instant closedAt,
        Instant createdAt,
        Instant updatedAt
    ) {
    }
}
