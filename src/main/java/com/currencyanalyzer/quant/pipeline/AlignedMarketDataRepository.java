package com.currencyanalyzer.quant.pipeline;

import com.currencyanalyzer.marketdata.OhlcBar;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Stage 1 persistence store for NY 5:00 PM aligned daily OHLC bars,
 * point-in-time central-bank policy rates, and persisted Master Arena runs.
 */
@Repository
public class AlignedMarketDataRepository {

    private final JdbcTemplate jdbcTemplate;

    public AlignedMarketDataRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public int upsertOhlcBars(String pair, String source, List<OhlcBar> bars, Instant fetchedAt) {
        if (pair == null || pair.isBlank() || bars == null || bars.isEmpty()) {
            return 0;
        }
        String normalizedPair = pair.strip().toUpperCase(Locale.ROOT);
        String resolvedSource = (source == null || source.isBlank()) ? "YAHOO" : source.strip();
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC);
        int written = 0;

        for (OhlcBar bar : bars) {
            if (bar == null || bar.date() == null || !isValidBar(bar)) {
                continue;
            }
            LocalDate barDate = LocalDate.parse(bar.date().strip());
            Date sqlDate = Date.valueOf(barDate);
            int updated = jdbcTemplate.update("""
                    UPDATE ohlc_history
                    SET open_price = ?, high_price = ?, low_price = ?, close_price = ?,
                        volume = ?, source = ?, fetched_at = ?
                    WHERE pair = ? AND bar_date = ?
                    """,
                bar.open(), bar.high(), bar.low(), bar.close(), bar.volume(),
                resolvedSource, timestamp, normalizedPair, sqlDate
            );
            if (updated == 0) {
                jdbcTemplate.update("""
                        INSERT INTO ohlc_history (
                            pair, bar_date, open_price, high_price, low_price, close_price,
                            volume, source, fetched_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                    normalizedPair, sqlDate, bar.open(), bar.high(), bar.low(), bar.close(),
                    bar.volume(), resolvedSource, timestamp
                );
            }
            written++;
        }
        return written;
    }

    @Transactional
    public int upsertPolicyRates(List<PolicyRateEntry> entries, Instant fetchedAt) {
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC);
        int written = 0;

        for (PolicyRateEntry entry : entries) {
            if (entry == null || entry.currency() == null || entry.effectiveDate() == null
                || !Double.isFinite(entry.annualRatePct())) {
                continue;
            }
            String currency = entry.currency().strip().toUpperCase(Locale.ROOT);
            String source = (entry.source() == null || entry.source().isBlank()) ? "MANUAL" : entry.source().strip();
            Date sqlDate = Date.valueOf(entry.effectiveDate());
            int updated = jdbcTemplate.update("""
                    UPDATE policy_rate_history
                    SET annual_rate_pct = ?, source = ?, fetched_at = ?
                    WHERE currency = ? AND effective_date = ?
                    """,
                entry.annualRatePct(), source, timestamp, currency, sqlDate
            );
            if (updated == 0) {
                jdbcTemplate.update("""
                        INSERT INTO policy_rate_history (
                            currency, effective_date, annual_rate_pct, source, fetched_at
                        ) VALUES (?, ?, ?, ?, ?)
                        """,
                    currency, sqlDate, entry.annualRatePct(), source, timestamp
                );
            }
            written++;
        }
        return written;
    }

    @Transactional
    public int seedClasspathPolicyRatesIfEmpty(Clock clock) {
        Long existingCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM policy_rate_history", Long.class);
        if (existingCount != null && existingCount > 0) {
            return 0;
        }
        return upsertPolicyRates(PolicyRateResolver.loadClasspathSeed(), clock.instant());
    }

    public List<PolicyRateEntry> findAllPolicyRates() {
        return jdbcTemplate.query("""
                SELECT currency, effective_date, annual_rate_pct, source
                FROM policy_rate_history
                ORDER BY currency ASC, effective_date ASC
                """,
            (rs, rowNum) -> new PolicyRateEntry(
                rs.getString("currency"),
                rs.getDate("effective_date").toLocalDate(),
                rs.getDouble("annual_rate_pct"),
                rs.getString("source")
            )
        );
    }

    public PolicyRateResolver loadPolicyRateResolver() {
        List<PolicyRateEntry> combined = new ArrayList<>(PolicyRateResolver.loadClasspathSeed());
        combined.addAll(findAllPolicyRates());
        return PolicyRateResolver.fromEntries(combined);
    }

    public Optional<LocalDate> findLatestOhlcDate(String pair) {
        if (pair == null || pair.isBlank()) {
            return Optional.empty();
        }
        String normalizedPair = pair.strip().toUpperCase(Locale.ROOT);
        Date maxDate = jdbcTemplate.queryForObject(
            "SELECT MAX(bar_date) FROM ohlc_history WHERE pair = ?",
            Date.class,
            normalizedPair
        );
        return Optional.ofNullable(maxDate).map(Date::toLocalDate);
    }

    public long countOhlcBars(String pair) {
        if (pair == null || pair.isBlank()) {
            return 0L;
        }
        String normalizedPair = pair.strip().toUpperCase(Locale.ROOT);
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ohlc_history WHERE pair = ?",
            Long.class,
            normalizedPair
        );
        return count == null ? 0L : count;
    }

    public List<OhlcBar> findOhlcBars(String pair, LocalDate startDate, LocalDate endDate) {
        if (pair == null || pair.isBlank() || startDate == null || endDate == null) {
            return List.of();
        }
        String normalizedPair = pair.strip().toUpperCase(Locale.ROOT);
        return jdbcTemplate.query("""
                SELECT bar_date, open_price, high_price, low_price, close_price, volume
                FROM ohlc_history
                WHERE pair = ? AND bar_date >= ? AND bar_date <= ?
                ORDER BY bar_date ASC
                """,
            (rs, rowNum) -> mapOhlcBar(rs),
            normalizedPair,
            Date.valueOf(startDate),
            Date.valueOf(endDate)
        );
    }

    /**
     * Emits Stage 1 daily bars enriched with point-in-time base and quote policy rates.
     */
    public Map<String, List<FxDailyBar>> emitAlignedBars(
        List<String> pairs,
        LocalDate startDate,
        LocalDate endDate
    ) {
        if (pairs == null || pairs.isEmpty() || startDate == null || endDate == null) {
            return Map.of();
        }
        PolicyRateResolver resolver = loadPolicyRateResolver();
        Map<String, List<FxDailyBar>> result = new LinkedHashMap<>();

        for (String rawPair : pairs) {
            if (rawPair == null || rawPair.isBlank()) {
                continue;
            }
            InstrumentSpec spec = InstrumentCatalog.forPair(rawPair);
            List<OhlcBar> ohlcBars = findOhlcBars(spec.pair(), startDate, endDate);
            List<FxDailyBar> aligned = new ArrayList<>(ohlcBars.size());
            for (OhlcBar bar : ohlcBars) {
                LocalDate date = LocalDate.parse(bar.date());
                double baseRate = resolver.rateAsOf(spec.baseCurrency(), date);
                double quoteRate = resolver.rateAsOf(spec.quoteCurrency(), date);
                aligned.add(new FxDailyBar(
                    spec.pair(),
                    date,
                    bar.open(),
                    bar.high(),
                    bar.low(),
                    bar.close(),
                    bar.volume(),
                    baseRate,
                    quoteRate
                ));
            }
            result.put(spec.pair(), List.copyOf(aligned));
        }
        return Map.copyOf(result);
    }

    @Transactional
    public void saveBacktestRun(
        UUID id,
        String username,
        String profileId,
        LocalDate startDate,
        LocalDate endDate,
        String configJson,
        String summaryJson,
        Instant createdAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO pipeline_backtest_runs (
                    id, username, profile_id, start_date, end_date,
                    config_json, summary_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            id,
            username,
            profileId,
            Date.valueOf(startDate),
            Date.valueOf(endDate),
            configJson,
            summaryJson,
            OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC)
        );
    }

    public List<PipelineRunRecord> findRunsByUsername(String username, int limit) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        return jdbcTemplate.query("""
                SELECT id, username, profile_id, start_date, end_date,
                       config_json, summary_json, created_at
                FROM pipeline_backtest_runs
                WHERE username = ?
                ORDER BY created_at DESC
                LIMIT ?
                """,
            (rs, rowNum) -> new PipelineRunRecord(
                UUID.fromString(rs.getString("id")),
                rs.getString("username"),
                rs.getString("profile_id"),
                rs.getDate("start_date").toLocalDate(),
                rs.getDate("end_date").toLocalDate(),
                rs.getString("config_json"),
                rs.getString("summary_json"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()
            ),
            username,
            safeLimit
        );
    }

    private static OhlcBar mapOhlcBar(ResultSet rs) throws SQLException {
        return new OhlcBar(
            rs.getDate("bar_date").toLocalDate().toString(),
            rs.getDouble("open_price"),
            rs.getDouble("high_price"),
            rs.getDouble("low_price"),
            rs.getDouble("close_price"),
            rs.getDouble("volume")
        );
    }

    private static boolean isValidBar(OhlcBar bar) {
        return Double.isFinite(bar.open()) && bar.open() > 0
            && Double.isFinite(bar.high()) && bar.high() > 0
            && Double.isFinite(bar.low()) && bar.low() > 0
            && Double.isFinite(bar.close()) && bar.close() > 0
            && bar.high() >= bar.low()
            && Double.isFinite(bar.volume()) && bar.volume() >= 0;
    }

    public record PipelineRunRecord(
        UUID id,
        String username,
        String profileId,
        LocalDate startDate,
        LocalDate endDate,
        String configJson,
        String summaryJson,
        Instant createdAt
    ) {
    }
}
