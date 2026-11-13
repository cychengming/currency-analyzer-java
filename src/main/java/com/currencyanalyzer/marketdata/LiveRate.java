package com.currencyanalyzer.marketdata;

public record LiveRate(
    double rate,
    double change,
    double changePercent
) {
}
