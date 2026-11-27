package com.currencyanalyzer.quant.pipeline;

import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Point-in-time central-bank policy rate lookup using floor-date semantics so
 * historical backtests never read future rate changes.
 */
public final class PolicyRateResolver {

    public static final String SEED_RESOURCE_PATH = "marketdata/seed/policy-rates.csv";
    private static final Set<String> ZERO_YIELD_CURRENCIES = Set.of("XAU", "XAG", "GOLD", "SILVER");

    private final Map<String, NavigableMap<LocalDate, Double>> ratesByCurrency;

    private PolicyRateResolver(Map<String, NavigableMap<LocalDate, Double>> ratesByCurrency) {
        Map<String, NavigableMap<LocalDate, Double>> copy = new HashMap<>();
        ratesByCurrency.forEach((currency, series) -> copy.put(currency, new TreeMap<>(series)));
        this.ratesByCurrency = Map.copyOf(copy);
    }

    public static PolicyRateResolver fromEntries(List<PolicyRateEntry> entries) {
        Map<String, NavigableMap<LocalDate, Double>> map = new HashMap<>();
        if (entries != null) {
            for (PolicyRateEntry entry : entries) {
                if (entry == null || entry.currency() == null || entry.effectiveDate() == null) {
                    continue;
                }
                if (!Double.isFinite(entry.annualRatePct())) {
                    continue;
                }
                String currency = normalizeCurrency(entry.currency());
                map.computeIfAbsent(currency, ignored -> new TreeMap<>())
                    .put(entry.effectiveDate(), entry.annualRatePct());
            }
        }
        return new PolicyRateResolver(map);
    }

    public static PolicyRateResolver fromClasspathSeed() {
        return fromEntries(loadClasspathSeed());
    }

    public static List<PolicyRateEntry> loadClasspathSeed() {
        ClassPathResource resource = new ClassPathResource(SEED_RESOURCE_PATH);
        List<PolicyRateEntry> entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)
        )) {
            String line;
            boolean headerSeen = false;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (!headerSeen && trimmed.toLowerCase(Locale.ROOT).startsWith("currency,")) {
                    headerSeen = true;
                    continue;
                }
                String[] parts = trimmed.split(",", -1);
                if (parts.length < 3) {
                    continue;
                }
                String currency = normalizeCurrency(parts[0]);
                LocalDate effectiveDate = LocalDate.parse(parts[1].strip());
                double rate = Double.parseDouble(parts[2].strip());
                String source = parts.length >= 4 && !parts[3].isBlank() ? parts[3].strip() : "CSV_SEED";
                entries.add(new PolicyRateEntry(currency, effectiveDate, rate, source));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to read policy rate seed from " + SEED_RESOURCE_PATH, exception);
        }
        return List.copyOf(entries);
    }

    /**
     * Returns the policy rate effective on or before {@code asOfDate}.
     * Precious metals default to {@code 0.0%} unless explicitly overridden.
     */
    public double rateAsOf(String currency, LocalDate asOfDate) {
        if (currency == null || currency.isBlank() || asOfDate == null) {
            return 0.0;
        }
        String normalized = normalizeCurrency(currency);
        NavigableMap<LocalDate, Double> series = ratesByCurrency.get(normalized);
        if (series != null) {
            Map.Entry<LocalDate, Double> entry = series.floorEntry(asOfDate);
            if (entry != null) {
                return entry.getValue();
            }
        }
        if (ZERO_YIELD_CURRENCIES.contains(normalized)) {
            return 0.0;
        }
        return 0.0;
    }

    public boolean hasCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return false;
        }
        return ratesByCurrency.containsKey(normalizeCurrency(currency));
    }

    private static String normalizeCurrency(String raw) {
        String upper = raw.strip().toUpperCase(Locale.ROOT);
        if ("GOLD".equals(upper)) {
            return "XAU";
        }
        if ("SILVER".equals(upper)) {
            return "XAG";
        }
        return upper;
    }
}
