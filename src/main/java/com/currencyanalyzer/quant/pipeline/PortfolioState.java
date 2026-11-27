package com.currencyanalyzer.quant.pipeline;

import java.time.LocalDate;
import java.util.Map;

/**
 * Portfolio state fed back from Stage 5 (Portfolio Ledger) into Stage 3 (Risk Sizer).
 */
public record PortfolioState(
    LocalDate asOfDate,
    double equityUsd,
    double peakEquityUsd,
    double drawdownPct,
    Map<String, OpenPosition> positions,
    Map<String, Double> netCurrencyExposureUsd
) {
    public PortfolioState {
        positions = positions == null ? Map.of() : Map.copyOf(positions);
        netCurrencyExposureUsd = netCurrencyExposureUsd == null ? Map.of() : Map.copyOf(netCurrencyExposureUsd);
    }
}
