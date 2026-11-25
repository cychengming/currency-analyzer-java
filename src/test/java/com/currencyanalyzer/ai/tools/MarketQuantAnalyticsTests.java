package com.currencyanalyzer.ai.tools;

import com.currencyanalyzer.marketdata.HistoricalPoint;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketQuantAnalyticsTests {

    @Test
    void computesTa4jTechnicalIndicatorsAcrossRegimes() {
        Map<String, Object> tooShort = MarketQuantAnalytics.analyzeTechnicals(
            "EUR/USD",
            List.of(new HistoricalPoint("2026-11-01", 1.10)),
            30
        );
        assertEquals(false, tooShort.get("available"));

        List<HistoricalPoint> uptrend = series(60, i -> 1.00 + i * 0.004 + ((i % 3 == 0) ? 0.001 : 0.0));
        Map<String, Object> bullish = MarketQuantAnalytics.analyzeTechnicals("EUR/USD", uptrend, 60);
        assertEquals(true, bullish.get("available"));
        assertEquals("bullish_aligned", bullish.get("trend_regime"));
        assertEquals("overbought", bullish.get("rsi_regime"));
        assertNotNull(bullish.get("sma_20"));
        assertNotNull(bullish.get("sma_50"));
        assertNotNull(bullish.get("ema_12"));
        assertNotNull(bullish.get("ema_26"));
        assertNotNull(bullish.get("macd_12_26_9"));
        assertNotNull(bullish.get("bollinger_bands_20"));
        assertNotNull(bullish.get("annualized_volatility_pct"));
        assertEquals("ta4j-core + hipparchus-stat", bullish.get("engine"));

        List<HistoricalPoint> downtrend = series(60, i -> 1.40 - i * 0.004 - ((i % 4 == 0) ? 0.001 : 0.0));
        Map<String, Object> bearish = MarketQuantAnalytics.analyzeTechnicals("GBP/USD", downtrend, 60);
        assertEquals("bearish_aligned", bearish.get("trend_regime"));
        assertEquals("oversold", bearish.get("rsi_regime"));
        assertTrue(((Number) bearish.get("max_drawdown_pct")).doubleValue() < 0.0);

        List<HistoricalPoint> sideways = series(55, i -> 1.10 + 0.02 * Math.sin(i * 0.6));
        Map<String, Object> oscillating = MarketQuantAnalytics.analyzeTechnicals("USD/JPY", sideways, 55);
        assertEquals("neutral", oscillating.get("rsi_regime"));
        assertNotNull(oscillating.get("trend_regime"));

        List<HistoricalPoint> shortSeries = series(22, i -> 1.10 + (i % 3 == 0 ? 0.005 : -0.002));
        Map<String, Object> shortWindow = MarketQuantAnalytics.analyzeTechnicals("AUD/USD", shortSeries, 22);
        assertNotNull(shortWindow.get("trend_regime"));

        List<HistoricalPoint> nonIsoDates = List.of(
            new HistoricalPoint("bad-date", 1.10),
            new HistoricalPoint("bad-date", 1.12),
            new HistoricalPoint("2026-11-01", 1.11)
        );
        Map<String, Object> fallback = MarketQuantAnalytics.analyzeTechnicals("EUR/USD", nonIsoDates, 14);
        assertEquals(true, fallback.get("available"));
    }

    @Test
    void computesHipparchusPairCorrelationBetaAndVolatility() {
        List<HistoricalPoint> base = series(20, i -> 1.00 + 0.02 * Math.sin(i * 0.7) + i * 0.002);
        List<HistoricalPoint> alignedPositive = series(20, i -> 2.00 + 0.04 * Math.sin(i * 0.7) + i * 0.004);
        List<HistoricalPoint> alignedNegative = series(20, i -> 2.00 - 0.04 * Math.sin(i * 0.7) - i * 0.004);
        List<HistoricalPoint> flat = series(20, i -> 1.00);

        Map<String, Object> pos = MarketQuantAnalytics.comparePairStatistics(base, alignedPositive);
        assertEquals(true, pos.get("correlation_available"));
        assertEquals("strong_positive", pos.get("correlation_regime"));
        assertTrue(((Number) pos.get("pearson_correlation")).doubleValue() > 0.9);
        assertNotNull(pos.get("beta_first_vs_second"));
        assertNotNull(pos.get("first_annualized_volatility_pct"));

        Map<String, Object> neg = MarketQuantAnalytics.comparePairStatistics(base, alignedNegative);
        assertEquals("strong_negative", neg.get("correlation_regime"));
        assertTrue(((Number) neg.get("pearson_correlation")).doubleValue() < -0.9);

        List<HistoricalPoint> orthogonal = series(20, i -> 1.50 + 0.03 * Math.cos(i * 1.4));
        Map<String, Object> weak = MarketQuantAnalytics.comparePairStatistics(base, orthogonal);
        assertEquals(true, weak.get("correlation_available"));
        assertNotNull(weak.get("correlation_regime"));

        Map<String, Object> zeroVar = MarketQuantAnalytics.comparePairStatistics(base, flat);
        assertEquals(false, zeroVar.get("correlation_available"));

        Map<String, Object> tooFew = MarketQuantAnalytics.comparePairStatistics(
            List.of(new HistoricalPoint("2026-11-01", 1.10)),
            List.of(new HistoricalPoint("2026-11-01", 1.25))
        );
        assertEquals(false, tooFew.get("correlation_available"));
    }

    @Test
    void calculatesLongAndShortPositionSizesAndValidatesInputs() {
        Map<String, Object> longPos = MarketQuantAnalytics.calculatePositionSize(
            "EUR/USD", 10_000.0, 1.0, 1.10, 1.08, 1.14, 3.0
        );
        assertEquals("long", longPos.get("direction"));
        assertEquals(100.0, longPos.get("risk_amount"));
        assertEquals(5000.0, longPos.get("position_units"));
        assertEquals(2.0, longPos.get("reward_to_risk_ratio"));
        assertEquals(false, longPos.get("exceeds_daily_risk_limit"));

        Map<String, Object> shortPos = MarketQuantAnalytics.calculatePositionSize(
            "GOLD/USD", 10_000.0, 4.0, 2600.0, 2650.0, 2500.0, 3.0
        );
        assertEquals("short", shortPos.get("direction"));
        assertEquals(400.0, shortPos.get("risk_amount"));
        assertEquals(8.0, shortPos.get("position_units"));
        assertEquals(2.0, shortPos.get("reward_to_risk_ratio"));
        assertEquals(true, shortPos.get("exceeds_daily_risk_limit"));

        assertThrows(IllegalArgumentException.class, () ->
            MarketQuantAnalytics.calculatePositionSize("EUR/USD", 0, 1.0, 1.10, 1.08, null, null));
        assertThrows(IllegalArgumentException.class, () ->
            MarketQuantAnalytics.calculatePositionSize("EUR/USD", 1000, 150.0, 1.10, 1.08, null, null));
        assertThrows(IllegalArgumentException.class, () ->
            MarketQuantAnalytics.calculatePositionSize("EUR/USD", 1000, 1.0, 1.10, 1.10, null, null));
        assertThrows(IllegalArgumentException.class, () ->
            MarketQuantAnalytics.calculatePositionSize("EUR/USD", 1000, 1.0, 1.10, 1.08, 1.09, null));
        assertThrows(IllegalArgumentException.class, () ->
            MarketQuantAnalytics.calculatePositionSize("EUR/USD", 1000, 1.0, 1.10, 1.12, 1.11, null));
        assertFalse(shortPos.isEmpty());
    }

    private static List<HistoricalPoint> series(int count, java.util.function.IntToDoubleFunction priceAt) {
        LocalDate start = LocalDate.of(2026, 9, 1);
        List<HistoricalPoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(new HistoricalPoint(start.plusDays(i).toString(), priceAt.applyAsDouble(i)));
        }
        return points;
    }
}
