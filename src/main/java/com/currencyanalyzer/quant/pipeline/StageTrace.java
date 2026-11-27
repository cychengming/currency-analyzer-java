package com.currencyanalyzer.quant.pipeline;

import java.time.LocalDate;

/**
 * Detailed per-pair, per-step trace across Stages 1-5 for inspection and auditing.
 */
public record StageTrace(
    LocalDate signalDate,
    LocalDate executionDate,
    String pair,
    FxDailyBar signalBar,
    double atr20,
    double annualizedVolPct,
    double signal,
    TargetPosition target,
    FillReport fill,
    double pairSpotPnlUsd,
    double pairCarryPnlUsd
) {
}
