package com.currencyanalyzer.quant.pipeline;

/**
 * Configuration parameters governing Stage 3 sizing and Stage 4 execution friction.
 */
public record PipelineConfig(
    double initialEquityUsd,
    double targetVolatilityPct,
    double maxCurrencyExposureMultiple,
    double rebalanceInertiaPct,
    double brokerCarryMarkupAnnual,
    boolean enableCarry,
    int traceLimit
) {
    public PipelineConfig {
        if (!Double.isFinite(initialEquityUsd) || initialEquityUsd <= 0) {
            throw new IllegalArgumentException("initialEquityUsd must be positive");
        }
        if (!Double.isFinite(targetVolatilityPct) || targetVolatilityPct <= 0 || targetVolatilityPct > 1.0) {
            throw new IllegalArgumentException("targetVolatilityPct must be in (0, 1.0]");
        }
        if (!Double.isFinite(maxCurrencyExposureMultiple) || maxCurrencyExposureMultiple <= 0) {
            throw new IllegalArgumentException("maxCurrencyExposureMultiple must be positive");
        }
        if (!Double.isFinite(rebalanceInertiaPct) || rebalanceInertiaPct < 0 || rebalanceInertiaPct > 0.5) {
            throw new IllegalArgumentException("rebalanceInertiaPct must be in [0, 0.5]");
        }
        if (!Double.isFinite(brokerCarryMarkupAnnual) || brokerCarryMarkupAnnual < 0) {
            throw new IllegalArgumentException("brokerCarryMarkupAnnual must be >= 0");
        }
    }

    public static PipelineConfig defaults() {
        return new PipelineConfig(100_000.0, 0.10, 1.50, 0.10, 0.01, true, 250);
    }
}
