package com.currencyanalyzer.quant.pipeline;

import java.time.LocalDate;

/**
 * Stage 4 output capturing Day {@code t+1} Open fill, spread/slippage friction, and daily carry.
 */
public record FillReport(
    String pair,
    LocalDate date,
    double filledDeltaBaseUnits,
    double fillPrice,
    double midOpenPrice,
    double frictionCostUsd,
    double dailyCarryUsd,
    int rolloverDays
) {
}
