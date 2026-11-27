package com.currencyanalyzer.quant.pipeline;

import java.util.Locale;

/**
 * Canonical pair metadata for G10 FX and Spot Gold/Silver ({@code GOLD/USD}, {@code SILVER/USD}).
 */
public final class InstrumentCatalog {

    private static final double DEFAULT_CARRY_MARKUP_ANNUAL = 0.01;

    private InstrumentCatalog() {
    }

    public static InstrumentSpec forPair(String rawPair) {
        if (rawPair == null || rawPair.isBlank()) {
            throw new IllegalArgumentException("pair is required");
        }
        String pair = rawPair.strip().toUpperCase(Locale.ROOT);
        if ("GOLD/USD".equals(pair) || "XAU/USD".equals(pair)) {
            return new InstrumentSpec(pair, "XAU", "USD", 0.10, 3.0, DEFAULT_CARRY_MARKUP_ANNUAL);
        }
        if ("SILVER/USD".equals(pair) || "XAG/USD".equals(pair)) {
            return new InstrumentSpec(pair, "XAG", "USD", 0.01, 2.0, DEFAULT_CARRY_MARKUP_ANNUAL);
        }
        String[] parts = pair.split("/", -1);
        if (parts.length != 2 || !parts[0].matches("[A-Z]{3}") || !parts[1].matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Unsupported instrument pair: " + pair);
        }
        double pipSize = "JPY".equals(parts[1]) ? 0.01 : 0.0001;
        return new InstrumentSpec(pair, parts[0], parts[1], pipSize, 2.0, DEFAULT_CARRY_MARKUP_ANNUAL);
    }
}
