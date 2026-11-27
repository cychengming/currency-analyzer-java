package com.currencyanalyzer.quant.pipeline;

import org.ta4j.core.BarSeries;

import java.util.List;

/**
 * Stage 2 contract: stateless signal generator outputting a normalized target weight in [-1.0, +1.0].
 */
public interface SignalEngine {

    String id();

    String displayName();

    default int warmupBars() {
        return 252;
    }

    /**
     * Computes the normalized signal using strictly point-in-time bars {@code <= index}.
     */
    double computeSignal(BarSeries series, List<FxDailyBar> bars, int index);
}
