package com.currencyanalyzer.ai.tools;

import com.currencyanalyzer.marketdata.HistoricalPoint;
import org.hipparchus.stat.correlation.Covariance;
import org.hipparchus.stat.correlation.PearsonsCorrelation;
import org.hipparchus.stat.descriptive.DescriptiveStatistics;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.Num;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic technical analysis, cross-pair correlation, and position sizing
 * powered by {@code ta4j-core} and {@code hipparchus-stat}.
 */
public final class MarketQuantAnalytics {

    private static final double TRADING_DAYS_PER_YEAR = 252.0;

    private MarketQuantAnalytics() {
    }

    public static Map<String, Object> analyzeTechnicals(
        String pair,
        List<HistoricalPoint> rawPoints,
        int requestedDays
    ) {
        List<HistoricalPoint> points = validPoints(rawPoints);
        if (points.size() < 2) {
            return new LinkedHashMap<>(Map.of(
                "available", false,
                "pair", pair,
                "reason", "At least 2 daily closes are required for technical analysis."
            ));
        }

        BarSeries series = toBarSeries(pair, points);
        int end = series.getEndIndex();
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        double firstClose = points.getFirst().rate();
        double latestClose = points.getLast().rate();

        double high = Double.NEGATIVE_INFINITY;
        double low = Double.POSITIVE_INFINITY;
        double peak = Double.NEGATIVE_INFINITY;
        double maxDrawdownPct = 0.0;
        for (HistoricalPoint point : points) {
            double rate = point.rate();
            high = Math.max(high, rate);
            low = Math.min(low, rate);
            peak = Math.max(peak, rate);
            if (peak > 0) {
                double drawdownPct = (rate - peak) / peak * 100.0;
                maxDrawdownPct = Math.min(maxDrawdownPct, drawdownPct);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("available", true);
        body.put("pair", pair);
        body.put("requested_days", requestedDays);
        body.put("point_count", points.size());
        body.put("start_date", points.getFirst().date());
        body.put("end_date", points.getLast().date());
        body.put("latest_close", round6(latestClose));
        body.put("period_return_pct", round2((latestClose - firstClose) / firstClose * 100.0));
        body.put("period_high", round6(high));
        body.put("period_low", round6(low));
        body.put("distance_from_high_pct", round2((latestClose - high) / high * 100.0));
        body.put("distance_from_low_pct", round2((latestClose - low) / low * 100.0));
        body.put("max_drawdown_pct", round2(maxDrawdownPct));

        Double annualizedVol = annualizedVolatilityPct(dailyReturns(points));
        if (annualizedVol != null) {
            body.put("annualized_volatility_pct", round2(annualizedVol));
        }

        populateMovingAverages(body, close, end, points.size(), latestClose);
        populateMomentumAndBands(body, close, end, points.size());
        body.put("engine", "ta4j-core + hipparchus-stat");
        return body;
    }

    public static Map<String, Object> comparePairStatistics(
        List<HistoricalPoint> firstRaw,
        List<HistoricalPoint> secondRaw
    ) {
        List<HistoricalPoint> first = validPoints(firstRaw);
        List<HistoricalPoint> second = validPoints(secondRaw);
        Map<String, Double> secondByDate = new LinkedHashMap<>();
        for (HistoricalPoint point : second) {
            secondByDate.put(point.date(), point.rate());
        }

        List<Double> alignedFirst = new ArrayList<>();
        List<Double> alignedSecond = new ArrayList<>();
        for (HistoricalPoint point : first) {
            Double other = secondByDate.get(point.date());
            if (other != null) {
                alignedFirst.add(point.rate());
                alignedSecond.add(other);
            }
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("overlapping_dates", alignedFirst.size());
        if (alignedFirst.size() < 3) {
            stats.put("correlation_available", false);
            stats.put("reason", "At least 3 overlapping trading dates are required for return correlation.");
            return stats;
        }

        double[] firstReturns = returnsFromRates(alignedFirst);
        double[] secondReturns = returnsFromRates(alignedSecond);

        Double firstVol = annualizedVolatilityPct(firstReturns);
        Double secondVol = annualizedVolatilityPct(secondReturns);
        if (firstVol != null) {
            stats.put("first_annualized_volatility_pct", round2(firstVol));
        }
        if (secondVol != null) {
            stats.put("second_annualized_volatility_pct", round2(secondVol));
        }

        double correlation = new PearsonsCorrelation().correlation(firstReturns, secondReturns);
        if (Double.isFinite(correlation)) {
            stats.put("correlation_available", true);
            stats.put("pearson_correlation", round4(correlation));
            stats.put("correlation_regime", classifyCorrelation(correlation));
            double secondVar = new DescriptiveStatistics(secondReturns).getVariance();
            if (Double.isFinite(secondVar) && secondVar > 0) {
                double cov = new Covariance().covariance(firstReturns, secondReturns);
                if (Double.isFinite(cov)) {
                    stats.put("beta_first_vs_second", round4(cov / secondVar));
                }
            }
        } else {
            stats.put("correlation_available", false);
            stats.put("reason", "At least one series had zero return variance over the window.");
        }
        return stats;
    }

    public static Map<String, Object> calculatePositionSize(
        String pair,
        double accountEquity,
        double riskPct,
        double entryPrice,
        double stopLossPrice,
        Double takeProfitPrice,
        Double dailyRiskLimitPct
    ) {
        validateSizingInputs(accountEquity, riskPct, entryPrice, stopLossPrice, takeProfitPrice);

        boolean isLong = entryPrice > stopLossPrice;
        double stopDistance = Math.abs(entryPrice - stopLossPrice);
        double stopDistancePct = stopDistance / entryPrice * 100.0;
        double riskAmount = accountEquity * (riskPct / 100.0);
        double positionUnits = riskAmount / stopDistance;
        double notionalValue = positionUnits * entryPrice;
        double impliedLeverage = notionalValue / accountEquity;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pair", pair);
        body.put("direction", isLong ? "long" : "short");
        body.put("account_equity", round2(accountEquity));
        body.put("risk_pct", round2(riskPct));
        body.put("risk_amount", round2(riskAmount));
        body.put("entry_price", round6(entryPrice));
        body.put("stop_loss_price", round6(stopLossPrice));
        body.put("stop_distance", round6(stopDistance));
        body.put("stop_distance_pct", round2(stopDistancePct));
        body.put("position_units", round4(positionUnits));
        body.put("notional_value", round2(notionalValue));
        body.put("implied_leverage", round2(impliedLeverage));

        if (takeProfitPrice != null) {
            double rewardDistance = Math.abs(takeProfitPrice - entryPrice);
            body.put("take_profit_price", round6(takeProfitPrice));
            body.put("reward_distance", round6(rewardDistance));
            body.put("reward_distance_pct", round2(rewardDistance / entryPrice * 100.0));
            body.put("potential_profit", round2(positionUnits * rewardDistance));
            body.put("reward_to_risk_ratio", round2(rewardDistance / stopDistance));
        }

        if (dailyRiskLimitPct != null && Double.isFinite(dailyRiskLimitPct) && dailyRiskLimitPct > 0) {
            body.put("daily_risk_limit_pct", round2(dailyRiskLimitPct));
            body.put("exceeds_daily_risk_limit", riskPct > dailyRiskLimitPct);
        }

        body.put("note", "Educational position sizing calculation only; not personalized investment advice.");
        return body;
    }

    private static void validateSizingInputs(
        double accountEquity,
        double riskPct,
        double entryPrice,
        double stopLossPrice,
        Double takeProfitPrice
    ) {
        if (!Double.isFinite(accountEquity) || accountEquity <= 0) {
            throw new IllegalArgumentException("account_equity must be a positive number");
        }
        if (!Double.isFinite(riskPct) || riskPct <= 0 || riskPct > 100.0) {
            throw new IllegalArgumentException("risk_pct must be > 0 and <= 100");
        }
        if (!Double.isFinite(entryPrice) || entryPrice <= 0) {
            throw new IllegalArgumentException("entry_price must be a positive number");
        }
        if (!Double.isFinite(stopLossPrice) || stopLossPrice <= 0) {
            throw new IllegalArgumentException("stop_loss_price must be a positive number");
        }
        if (Math.abs(entryPrice - stopLossPrice) < 1e-12) {
            throw new IllegalArgumentException("stop_loss_price must differ from entry_price");
        }
        if (takeProfitPrice != null) {
            if (!Double.isFinite(takeProfitPrice) || takeProfitPrice <= 0) {
                throw new IllegalArgumentException("take_profit_price must be a positive number");
            }
            boolean isLong = entryPrice > stopLossPrice;
            if ((isLong && takeProfitPrice <= entryPrice) || (!isLong && takeProfitPrice >= entryPrice)) {
                throw new IllegalArgumentException(
                    isLong
                        ? "take_profit_price must be above entry_price for a long setup"
                        : "take_profit_price must be below entry_price for a short setup"
                );
            }
        }
    }

    private static void populateMovingAverages(
        Map<String, Object> body,
        ClosePriceIndicator close,
        int end,
        int barCount,
        double latestClose
    ) {
        Double sma20 = barCount >= 20 ? valueOf(new SMAIndicator(close, 20), end) : null;
        Double sma50 = barCount >= 50 ? valueOf(new SMAIndicator(close, 50), end) : null;
        Double ema12 = barCount >= 12 ? valueOf(new EMAIndicator(close, 12), end) : null;
        Double ema26 = barCount >= 26 ? valueOf(new EMAIndicator(close, 26), end) : null;

        if (sma20 != null) {
            body.put("sma_20", round6(sma20));
        }
        if (sma50 != null) {
            body.put("sma_50", round6(sma50));
        }
        if (ema12 != null) {
            body.put("ema_12", round6(ema12));
        }
        if (ema26 != null) {
            body.put("ema_26", round6(ema26));
        }

        if (sma20 != null && sma50 != null) {
            if (latestClose > sma20 && sma20 > sma50) {
                body.put("trend_regime", "bullish_aligned");
            } else if (latestClose < sma20 && sma20 < sma50) {
                body.put("trend_regime", "bearish_aligned");
            } else {
                body.put("trend_regime", "mixed");
            }
        } else if (sma20 != null) {
            body.put("trend_regime", latestClose >= sma20 ? "above_sma_20" : "below_sma_20");
        }
    }

    private static void populateMomentumAndBands(
        Map<String, Object> body,
        ClosePriceIndicator close,
        int end,
        int barCount
    ) {
        if (barCount >= 15) {
            Double rsi14 = valueOf(new RSIIndicator(close, 14), end);
            if (rsi14 != null) {
                body.put("rsi_14", round2(rsi14));
                body.put("rsi_regime", classifyRsi(rsi14));
            }
        }

        if (barCount >= 35) {
            MACDIndicator macd = new MACDIndicator(close, 12, 26);
            Double line = valueOf(macd, end);
            Double signal = valueOf(macd.getSignalLine(9), end);
            Double histogram = valueOf(macd.getHistogram(9), end);
            if (line != null && signal != null && histogram != null) {
                Map<String, Object> macdMap = new LinkedHashMap<>();
                macdMap.put("line", round6(line));
                macdMap.put("signal", round6(signal));
                macdMap.put("histogram", round6(histogram));
                body.put("macd_12_26_9", macdMap);
            }
        }

        if (barCount >= 20) {
            SMAIndicator sma20 = new SMAIndicator(close, 20);
            BollingerBandsMiddleIndicator middle = new BollingerBandsMiddleIndicator(sma20);
            StandardDeviationIndicator stdDev = StandardDeviationIndicator.ofPopulation(close, 20);
            BollingerBandsUpperIndicator upper = new BollingerBandsUpperIndicator(middle, stdDev);
            BollingerBandsLowerIndicator lower = new BollingerBandsLowerIndicator(middle, stdDev);
            Double midVal = valueOf(middle, end);
            Double upperVal = valueOf(upper, end);
            Double lowerVal = valueOf(lower, end);
            if (midVal != null && upperVal != null && lowerVal != null && midVal > 0) {
                Map<String, Object> bb = new LinkedHashMap<>();
                bb.put("upper", round6(upperVal));
                bb.put("middle", round6(midVal));
                bb.put("lower", round6(lowerVal));
                bb.put("bandwidth_pct", round2((upperVal - lowerVal) / midVal * 100.0));
                body.put("bollinger_bands_20", bb);
            }
        }
    }

    private static BarSeries toBarSeries(String pair, List<HistoricalPoint> points) {
        BarSeries series = new BaseBarSeriesBuilder().withName(pair).build();
        Instant previousEnd = null;
        for (int i = 0; i < points.size(); i++) {
            HistoricalPoint point = points.get(i);
            Instant endTime = parseDateEnd(point.date(), previousEnd, i);
            previousEnd = endTime;
            double rate = point.rate();
            series.barBuilder()
                .timePeriod(Duration.ofDays(1))
                .endTime(endTime)
                .openPrice(rate)
                .highPrice(rate)
                .lowPrice(rate)
                .closePrice(rate)
                .volume(0)
                .add();
        }
        return series;
    }

    private static Instant parseDateEnd(String dateText, Instant previousEnd, int index) {
        Instant parsed = null;
        if (dateText != null && !dateText.isBlank()) {
            try {
                parsed = LocalDate.parse(dateText.trim()).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException ignored) {
                parsed = null;
            }
        }
        if (parsed == null) {
            parsed = previousEnd != null
                ? previousEnd.plus(Duration.ofDays(1))
                : Instant.EPOCH.plus(Duration.ofDays(index + 1L));
        } else if (previousEnd != null && !parsed.isAfter(previousEnd)) {
            parsed = previousEnd.plus(Duration.ofDays(1));
        }
        return parsed;
    }

    private static List<HistoricalPoint> validPoints(List<HistoricalPoint> points) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        List<HistoricalPoint> valid = new ArrayList<>(points.size());
        for (HistoricalPoint point : points) {
            if (point != null && Double.isFinite(point.rate()) && point.rate() > 0) {
                valid.add(point);
            }
        }
        return valid;
    }

    private static double[] dailyReturns(List<HistoricalPoint> points) {
        List<Double> rates = new ArrayList<>(points.size());
        for (HistoricalPoint point : points) {
            rates.add(point.rate());
        }
        return returnsFromRates(rates);
    }

    private static double[] returnsFromRates(List<Double> rates) {
        if (rates.size() < 2) {
            return new double[0];
        }
        double[] returns = new double[rates.size() - 1];
        for (int i = 1; i < rates.size(); i++) {
            returns[i - 1] = (rates.get(i) - rates.get(i - 1)) / rates.get(i - 1);
        }
        return returns;
    }

    private static Double annualizedVolatilityPct(double[] returns) {
        if (returns.length < 2) {
            return null;
        }
        double stdDev = new DescriptiveStatistics(returns).getStandardDeviation();
        if (!Double.isFinite(stdDev)) {
            return null;
        }
        return stdDev * Math.sqrt(TRADING_DAYS_PER_YEAR) * 100.0;
    }

    private static Double valueOf(Indicator<Num> indicator, int index) {
        Num value = indicator.getValue(index);
        if (value == null || value.isNaN()) {
            return null;
        }
        double numeric = value.doubleValue();
        return Double.isFinite(numeric) ? numeric : null;
    }

    private static String classifyRsi(double rsi) {
        if (rsi >= 70.0) {
            return "overbought";
        }
        if (rsi <= 30.0) {
            return "oversold";
        }
        return "neutral";
    }

    private static String classifyCorrelation(double correlation) {
        double abs = Math.abs(correlation);
        if (abs < 0.25) {
            return "weak";
        }
        if (abs < 0.65) {
            return correlation >= 0 ? "moderate_positive" : "moderate_negative";
        }
        return correlation >= 0 ? "strong_positive" : "strong_negative";
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }
}
