package com.currencyanalyzer.quant.pipeline;

import java.time.LocalDate;

/**
 * Rebalance order emitted at Day {@code t} close to be filled at Day {@code t+1} Open.
 */
public record OrderDelta(
    String pair,
    LocalDate signalDate,
    double previousBaseUnits,
    double targetBaseUnits,
    double deltaBaseUnits
) {
}
