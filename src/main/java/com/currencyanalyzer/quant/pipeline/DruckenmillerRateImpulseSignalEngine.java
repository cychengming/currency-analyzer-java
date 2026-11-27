package com.currencyanalyzer.quant.pipeline;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.util.List;

/**
 * Stage 2 Master Arena candidate inspired by Stanley Druckenmiller:
 * central-bank 63-day policy-rate differential impulse confirmed by SMA-200 price trend.
 */
public class DruckenmillerRateImpulseSignalEngine implements SignalEngine {

    public static final String ENGINE_ID = "druckenmiller-rate-impulse";

    private final int impulseDays;
    private final int trendSmaDays;
    private final int warmupBars;

    public DruckenmillerRateImpulseSignalEngine() {
        this(63, 200, 252);
    }

    public DruckenmillerRateImpulseSignalEngine(int impulseDays, int trendSmaDays, int warmupBars) {
        this.impulseDays = Math.max(10, impulseDays);
        this.trendSmaDays = Math.max(20, trendSmaDays);
        this.warmupBars = Math.max(Math.max(this.impulseDays, this.trendSmaDays), warmupBars);
    }

    @Override
    public String id() {
        return ENGINE_ID;
    }

    @Override
    public String displayName() {
        return "Druckenmiller Policy-Impulse + SMA-200";
    }

    @Override
    public int warmupBars() {
        return warmupBars;
    }

    @Override
    public double computeSignal(BarSeries series, List<FxDailyBar> bars, int index) {
        if (bars == null || index < warmupBars || index >= bars.size() || index < impulseDays) {
            return 0.0;
        }

        FxDailyBar current = bars.get(index);
        FxDailyBar prior = bars.get(index - impulseDays);

        double currentSpread = current.basePolicyRatePct() - current.quotePolicyRatePct();
        double priorSpread = prior.basePolicyRatePct() - prior.quotePolicyRatePct();
        double spreadImpulse = currentSpread - priorSpread;

        double trendDirection = resolveTrendDirection(series, bars, index);
        double levelCarryBias = Math.max(-1.0, Math.min(1.0, currentSpread / 4.0));
        double impulseScore = Math.max(-1.0, Math.min(1.0, spreadImpulse / 1.0));
        double macroBias = Math.max(-1.0, Math.min(1.0, 0.70 * impulseScore + 0.30 * levelCarryBias));

        if (Math.abs(macroBias) < 0.05) {
            return 0.35 * trendDirection;
        }
        if (Math.signum(macroBias) == Math.signum(trendDirection)) {
            return Math.max(-1.0, Math.min(1.0, 0.60 * macroBias + 0.40 * trendDirection));
        }
        return 0.25 * trendDirection;
    }

    private double resolveTrendDirection(BarSeries series, List<FxDailyBar> bars, int index) {
        double closePrice = bars.get(index).close();
        if (series != null && index < series.getBarCount()) {
            ClosePriceIndicator close = new ClosePriceIndicator(series);
            double sma = new SMAIndicator(close, trendSmaDays).getValue(index).doubleValue();
            if (Double.isFinite(sma) && sma > 0) {
                return closePrice >= sma ? 1.0 : -1.0;
            }
        }
        int start = Math.max(0, index - trendSmaDays + 1);
        double sum = 0.0;
        for (int i = start; i <= index; i++) {
            sum += bars.get(i).close();
        }
        double sma = sum / (index - start + 1);
        return closePrice >= sma ? 1.0 : -1.0;
    }
}
