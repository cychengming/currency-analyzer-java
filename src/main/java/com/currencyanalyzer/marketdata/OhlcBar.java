package com.currencyanalyzer.marketdata;

/**
 * Daily OHLC bar aligned to a calendar trading date.
 */
public record OhlcBar(
    String date,
    double open,
    double high,
    double low,
    double close,
    double volume
) {
}
