package com.currencyanalyzer.quant.pipeline;

/**
 * Snapshot of an open position in the portfolio ledger.
 */
public record OpenPosition(
    String pair,
    double baseUnits,
    double averageEntryPrice,
    double currentNotionalUsd
) {
}
