package com.currencyanalyzer.quant.pipeline;

import org.hipparchus.stat.descriptive.DescriptiveStatistics;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.util.List;

/**
 * Canonical Carver / AQR Benchmark Spine:
 * blends multi-horizon Time-Series Momentum (60/120/252d + EWMAC 16/64) with
 * volatility-normalized policy-rate carry, scaled by Forecast Diversification Multiplier.
 */
public class CarverAqrSignalEngine implements SignalEngine {

    public static final String ENGINE_ID = "carver-aqr-spine";
    private static final double TRADING_DAYS_PER_YEAR = 252.0;
    private static final double TREND_WEIGHT = 0.70;
    private static final double CARRY_WEIGHT = 0.30;
    private static final double FORECAST_DIVERSIFICATION_MULTIPLIER = 1.25;

    private final int warmupBars;

    public CarverAqrSignalEngine() {
        this(252);
    }

    public CarverAqrSignalEngine(int warmupBars) {
        this.warmupBars = Math.max(64, warmupBars);
    }

    @Override
    public String id() {
        return ENGINE_ID;
    }

    @Override
    public String displayName() {
        return "Carver / AQR Trend + Carry Spine";
    }

    @Override
    public int warmupBars() {
        return warmupBars;
    }

    @Override
    public double computeSignal(BarSeries series, List<FxDailyBar> bars, int index) {
        if (bars == null || index < warmupBars || index >= bars.size()) {
            return 0.0;
        }

        double annualizedVol = trailingAnnualizedVol(bars, index, 63);
        double dailyVol = annualizedVol / Math.sqrt(TRADING_DAYS_PER_YEAR);

        double tsmom60 = normalizedReturn(bars, index, 60, dailyVol);
        double tsmom120 = normalizedReturn(bars, index, 120, dailyVol);
        double tsmom252 = normalizedReturn(bars, index, 252, dailyVol);
        double ewmac = normalizedEwmac1664(series, bars.get(index).close(), index, dailyVol);

        double trendComponent = clamp((tsmom60 + tsmom120 + tsmom252 + ewmac) / 4.0, -1.0, 1.0);

        FxDailyBar bar = bars.get(index);
        double rateSpreadDecimal = (bar.basePolicyRatePct() - bar.quotePolicyRatePct()) / 100.0;
        double carryForecast = clamp((rateSpreadDecimal / annualizedVol) * 2.5, -1.0, 1.0);

        double blended = (TREND_WEIGHT * trendComponent + CARRY_WEIGHT * carryForecast)
            * FORECAST_DIVERSIFICATION_MULTIPLIER;
        return clamp(blended, -1.0, 1.0);
    }

    private static double normalizedReturn(List<FxDailyBar> bars, int index, int lookback, double dailyVol) {
        if (index < lookback) {
            return 0.0;
        }
        double priorClose = bars.get(index - lookback).close();
        double currentClose = bars.get(index).close();
        if (priorClose <= 0 || currentClose <= 0) {
            return 0.0;
        }
        double logRet = Math.log(currentClose / priorClose);
        double horizonVol = Math.max(1e-6, dailyVol * Math.sqrt(lookback));
        return clamp(logRet / (1.5 * horizonVol), -1.0, 1.0);
    }

    private static double normalizedEwmac1664(BarSeries series, double closePrice, int index, double dailyVol) {
        if (series == null || index >= series.getBarCount() || closePrice <= 0) {
            return 0.0;
        }
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        double fast = new EMAIndicator(close, 16).getValue(index).doubleValue();
        double slow = new EMAIndicator(close, 64).getValue(index).doubleValue();
        double priceVol = Math.max(1e-8, closePrice * dailyVol);
        double raw = (fast - slow) / (priceVol * 4.1);
        return Double.isFinite(raw) ? clamp(raw, -1.0, 1.0) : 0.0;
    }

    static double trailingAnnualizedVol(List<FxDailyBar> bars, int index, int lookback) {
        int start = Math.max(1, index - lookback + 1);
        DescriptiveStatistics stats = new DescriptiveStatistics();
        for (int i = start; i <= index; i++) {
            double prev = bars.get(i - 1).close();
            double curr = bars.get(i).close();
            if (prev > 0 && curr > 0) {
                stats.addValue(Math.log(curr / prev));
            }
        }
        if (stats.getN() < 2) {
            return 0.08;
        }
        double std = stats.getStandardDeviation();
        if (!Double.isFinite(std)) {
            return 0.08;
        }
        return Math.max(0.02, std * Math.sqrt(TRADING_DAYS_PER_YEAR));
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
