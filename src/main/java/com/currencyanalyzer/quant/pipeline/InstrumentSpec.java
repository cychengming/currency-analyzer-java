package com.currencyanalyzer.quant.pipeline;

/**
 * Trading metadata and execution friction defaults for a single currency or metal pair.
 */
public record InstrumentSpec(
    String pair,
    String baseCurrency,
    String quoteCurrency,
    double pipSize,
    double defaultOneWayCostPips,
    double brokerCarryMarkupAnnual
) {
}
