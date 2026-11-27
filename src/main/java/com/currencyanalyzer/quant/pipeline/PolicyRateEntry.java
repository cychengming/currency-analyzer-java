package com.currencyanalyzer.quant.pipeline;

import java.time.LocalDate;

/**
 * Central-bank policy rate effective from {@code effectiveDate} onward until superseded.
 */
public record PolicyRateEntry(
    String currency,
    LocalDate effectiveDate,
    double annualRatePct,
    String source
) {
}
