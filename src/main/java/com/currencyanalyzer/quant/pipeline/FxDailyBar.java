package com.currencyanalyzer.quant.pipeline;

import java.time.LocalDate;

/**
 * Stage 1 aligned daily market bar enriched with point-in-time central-bank policy rates.
 */
public record FxDailyBar(
    String pair,
    LocalDate date,
    double open,
    double high,
    double low,
    double close,
    double volume,
    double basePolicyRatePct,
    double quotePolicyRatePct
) {
}
