package com.currencyanalyzer.quant.pipeline;

/**
 * Stage 3 output describing volatility-targeted position size and safeguards.
 */
public record TargetPosition(
    String pair,
    double signal,
    double annualizedVolPct,
    double targetNotionalUsd,
    double targetBaseUnits,
    boolean cappedByExposureLimit,
    boolean heldByInertiaBuffer
) {
}
