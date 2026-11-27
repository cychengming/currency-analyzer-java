package com.currencyanalyzer.quant.pipeline;

import org.ta4j.core.BarSeries;

import java.util.List;

/**
 * Stage 2 Master Arena candidate inspired by Richard Dennis / Jerry Parker (Turtles):
 * dual 20-day / 55-day Donchian channel breakout without look-ahead on the current bar.
 */
public class DonchianBreakoutSignalEngine implements SignalEngine {

    public static final String ENGINE_ID = "turtle-donchian";

    private final int fastWindow;
    private final int slowWindow;
    private final int warmupBars;

    public DonchianBreakoutSignalEngine() {
        this(20, 55, 252);
    }

    public DonchianBreakoutSignalEngine(int fastWindow, int slowWindow, int warmupBars) {
        this.fastWindow = Math.max(5, fastWindow);
        this.slowWindow = Math.max(this.fastWindow + 5, slowWindow);
        this.warmupBars = Math.max(this.slowWindow + 1, warmupBars);
    }

    @Override
    public String id() {
        return ENGINE_ID;
    }

    @Override
    public String displayName() {
        return "Turtle / Parker Donchian Breakout (20/55)";
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
        double fastSignal = channelPosition(bars, index, fastWindow);
        double slowSignal = channelPosition(bars, index, slowWindow);
        return 0.5 * fastSignal + 0.5 * slowSignal;
    }

    private static double channelPosition(List<FxDailyBar> bars, int index, int window) {
        double priorHigh = Double.NEGATIVE_INFINITY;
        double priorLow = Double.POSITIVE_INFINITY;
        int start = Math.max(0, index - window);
        for (int i = start; i < index; i++) {
            priorHigh = Math.max(priorHigh, bars.get(i).high());
            priorLow = Math.min(priorLow, bars.get(i).low());
        }
        double close = bars.get(index).close();
        if (close > priorHigh) {
            return 1.0;
        }
        if (close < priorLow) {
            return -1.0;
        }
        double span = priorHigh - priorLow;
        if (span <= 1e-12) {
            return 0.0;
        }
        double normalized = ((close - priorLow) / span) * 2.0 - 1.0;
        return Math.max(-0.5, Math.min(0.5, normalized * 0.5));
    }
}
