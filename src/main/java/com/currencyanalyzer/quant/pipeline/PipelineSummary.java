package com.currencyanalyzer.quant.pipeline;

/**
 * Aggregate Stage 5 portfolio performance and Spot-vs-Carry attribution summary.
 */
public record PipelineSummary(
    double initialEquityUsd,
    double finalEquityUsd,
    double totalReturnPct,
    double cagrPct,
    double annualizedVolPct,
    double netSharpeRatio,
    double spotOnlySharpeRatio,
    double sortinoRatio,
    double calmarRatio,
    double maxDrawdownPct,
    double historicalVar95DailyPct,
    double totalSpotPnlUsd,
    double totalCarryPnlUsd,
    double totalFrictionCostUsd,
    int executedTradesCount,
    int inertiaSkippedCount,
    double annualizedTurnover
) {
}
