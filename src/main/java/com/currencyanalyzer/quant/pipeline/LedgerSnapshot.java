package com.currencyanalyzer.quant.pipeline;

import java.time.LocalDate;

/**
 * Stage 5 daily portfolio mark-to-market snapshot with Spot vs. Carry vs. Friction attribution.
 */
public record LedgerSnapshot(
    LocalDate date,
    double equityUsd,
    double dailySpotPnlUsd,
    double dailyCarryPnlUsd,
    double dailyFrictionCostUsd,
    double netDailyPnlUsd,
    double cumulativeSpotPnlUsd,
    double cumulativeCarryPnlUsd,
    double cumulativeFrictionCostUsd,
    double drawdownPct,
    double grossLeverage
) {
}
