package com.currencyanalyzer.marketdata;

public record HistoricalPoint(
    String date,
    double rate
) {
}
