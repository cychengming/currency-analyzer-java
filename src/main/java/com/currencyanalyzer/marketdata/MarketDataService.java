package com.currencyanalyzer.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);
    private static final int MAX_HISTORY_DAYS = 3650;

    private static final Map<String, String> COMMODITY_SYMBOLS = Map.ofEntries(
        Map.entry("GOLD/USD", "GC=F"),
        Map.entry("SILVER/USD", "SI=F"),
        Map.entry("COPPER/USD", "HG=F"),
        Map.entry("WHEAT/USD", "ZW=F"),
        Map.entry("SOYBEAN/USD", "ZS=F"),
        Map.entry("CORN/USD", "ZC=F"),
        Map.entry("WTI/USD", "CL=F"),
        Map.entry("BRENT/USD", "BZ=F"),
        Map.entry("NDX/USD", "^NDX"),
        Map.entry("SPX/USD", "^GSPC")
    );

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final List<String> configuredPairs;

    @Autowired
    public MarketDataService(
        RestClient.Builder restClientBuilder,
        Clock clock,
        @Value("${app.market.pairs:}") String pairs
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(12));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.objectMapper = new ObjectMapper();
        this.clock = clock;
        this.configuredPairs = parseConfiguredPairs(pairs);
    }

    MarketDataService(RestClient restClient, Clock clock, List<String> configuredPairs) {
        this.restClient = restClient;
        this.objectMapper = new ObjectMapper();
        this.clock = clock;
        this.configuredPairs = configuredPairs.stream()
            .map(MarketDataService::normalizePair)
            .toList();
    }

    public Map<String, LiveRate> fetchLiveRates() {
        Map<String, LiveRate> rates = new LinkedHashMap<>();
        rates.putAll(fetchCommodityRates());
        rates.putAll(fetchFxRates());

        Map<String, LiveRate> ordered = new LinkedHashMap<>();
        for (String pair : configuredPairs) {
            LiveRate rate = rates.get(pair);
            if (rate != null) {
                ordered.put(pair, rate);
            }
        }
        return ordered;
    }

    public List<HistoricalPoint> fetchHistorical(String requestedPair, int requestedDays) {
        String pair = normalizePair(requestedPair);
        validatePair(pair);
        if (requestedDays < 1 || requestedDays > MAX_HISTORY_DAYS) {
            throw new IllegalArgumentException("days must be between 1 and " + MAX_HISTORY_DAYS);
        }

        String commoditySymbol = COMMODITY_SYMBOLS.get(pair);
        if (commoditySymbol != null) {
            List<HistoricalPoint> points = fetchYahooHistory(commoditySymbol, requestedDays);
            return trimToRecent(points, requestedDays);
        }

        String[] parts = pair.split("/");
        List<HistoricalPoint> points = fetchFrankfurterHistory(parts[0], parts[1], requestedDays);
        if (points.isEmpty()) {
            points = fetchYahooHistory(parts[0] + parts[1] + "=X", requestedDays);
        }
        return trimToRecent(points, requestedDays);
    }

    private Map<String, LiveRate> fetchCommodityRates() {
        Map<String, LiveRate> rates = new LinkedHashMap<>();
        if (COMMODITY_SYMBOLS.isEmpty()) {
            return rates;
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<Optional<LiveRate>>> tasks = new LinkedHashMap<>();
            COMMODITY_SYMBOLS.forEach((pair, symbol) ->
                tasks.put(pair, executor.submit(() -> fetchYahooLiveRate(symbol))));

            tasks.forEach((pair, task) -> {
                try {
                    task.get(15, TimeUnit.SECONDS).ifPresent(rate -> rates.put(pair, rate));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while fetching {}", pair);
                } catch (ExecutionException | TimeoutException exception) {
                    log.warn("Unable to fetch commodity quote for {}: {}", pair, rootMessage(exception));
                }
            });
        }
        return rates;
    }

    private Map<String, LiveRate> fetchFxRates() {
        List<String> fxPairs = configuredPairs.stream()
            .filter(pair -> !COMMODITY_SYMBOLS.containsKey(pair))
            .toList();
        if (fxPairs.isEmpty()) {
            return Map.of();
        }

        Set<String> currencies = new LinkedHashSet<>();
        for (String pair : fxPairs) {
            String[] parts = pair.split("/");
            if (parts.length == 2) {
                if (!"USD".equals(parts[0])) {
                    currencies.add(parts[0]);
                }
                if (!"USD".equals(parts[1])) {
                    currencies.add(parts[1]);
                }
            }
        }
        if (currencies.isEmpty()) {
            currencies.add("EUR");
        }

        try {
            String to = String.join(",", currencies);
            JsonNode latest = getJson(
                "https://api.frankfurter.app/latest?from=USD&to={currencies}",
                to
            );
            Map<String, Double> currentRates = readFrankfurterRates(latest);
            LocalDate latestDate = LocalDate.parse(latest.path("date").asText());
            Map<String, Double> priorRates = fetchPriorFrankfurterRates(latestDate, to);

            Map<String, LiveRate> rates = new LinkedHashMap<>();
            for (String pair : fxPairs) {
                calculateFxRate(pair, currentRates, priorRates).ifPresent(rate -> rates.put(pair, rate));
            }
            return rates;
        } catch (RuntimeException exception) {
            log.warn("Unable to fetch FX rates from Frankfurter: {}", rootMessage(exception));
            return fetchFxRatesFromYahoo(fxPairs);
        }
    }

    private Map<String, Double> fetchPriorFrankfurterRates(LocalDate latestDate, String to) {
        for (int daysBack = 1; daysBack <= 10; daysBack++) {
            LocalDate date = latestDate.minusDays(daysBack);
            try {
                JsonNode payload = getJson(
                    "https://api.frankfurter.app/{date}?from=USD&to={currencies}",
                    date,
                    to
                );
                Map<String, Double> rates = readFrankfurterRates(payload);
                if (!rates.isEmpty()) {
                    return rates;
                }
            } catch (RuntimeException exception) {
                log.debug("No prior Frankfurter rates for {}: {}", date, rootMessage(exception));
            }
        }
        return Map.of();
    }

    private Map<String, LiveRate> fetchFxRatesFromYahoo(List<String> pairs) {
        Map<String, LiveRate> rates = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String pair : pairs) {
                String symbol = pair.replace("/", "") + "=X";
                Future<Optional<LiveRate>> task = executor.submit(() -> fetchYahooLiveRate(symbol));
                try {
                    task.get(15, TimeUnit.SECONDS).ifPresent(rate -> rates.put(pair, rate));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException | TimeoutException exception) {
                    log.warn("Unable to fetch Yahoo FX quote for {}: {}", pair, rootMessage(exception));
                }
            }
        }
        return rates;
    }

    private Optional<LiveRate> calculateFxRate(
        String pair,
        Map<String, Double> current,
        Map<String, Double> previous
    ) {
        String[] parts = pair.split("/");
        if (parts.length != 2) {
            return Optional.empty();
        }

        Double currentRate;
        Double previousRate;
        if ("USD".equals(parts[0])) {
            currentRate = current.get(parts[1]);
            previousRate = previous.getOrDefault(parts[1], currentRate);
        } else if ("USD".equals(parts[1])) {
            Double inverseCurrent = current.get(parts[0]);
            Double inversePrevious = previous.getOrDefault(parts[0], inverseCurrent);
            currentRate = inverseCurrent == null || inverseCurrent == 0 ? null : 1.0 / inverseCurrent;
            previousRate = inversePrevious == null || inversePrevious == 0 ? null : 1.0 / inversePrevious;
        } else {
            Double usdToQuote = current.get(parts[1]);
            Double usdToBase = current.get(parts[0]);
            Double usdToQuotePrevious = previous.getOrDefault(parts[1], usdToQuote);
            Double usdToBasePrevious = previous.getOrDefault(parts[0], usdToBase);
            currentRate = usdToQuote == null || usdToBase == null || usdToBase == 0
                ? null
                : usdToQuote / usdToBase;
            previousRate = usdToQuotePrevious == null || usdToBasePrevious == null || usdToBasePrevious == 0
                ? null
                : usdToQuotePrevious / usdToBasePrevious;
        }

        if (currentRate == null) {
            return Optional.empty();
        }
        if (previousRate == null) {
            previousRate = currentRate;
        }
        double change = currentRate - previousRate;
        double percent = previousRate == 0 ? 0 : change / previousRate * 100;
        return Optional.of(new LiveRate(round4(currentRate), round4(change), round2(percent)));
    }

    private List<HistoricalPoint> fetchFrankfurterHistory(String base, String quote, int days) {
        LocalDate end = LocalDate.now(clock);
        LocalDate start = end.minusDays(days);
        try {
            JsonNode payload = getJson(
                "https://api.frankfurter.app/{start}..{end}?from={base}&to={quote}",
                start,
                end,
                base,
                quote
            );
            Map<String, HistoricalPoint> byDate = new TreeMap<>();
            payload.path("rates").fields().forEachRemaining(entry -> {
                JsonNode rate = entry.getValue().path(quote);
                if (rate.isNumber()) {
                    byDate.put(entry.getKey(), new HistoricalPoint(entry.getKey(), rate.asDouble()));
                }
            });
            return new ArrayList<>(byDate.values());
        } catch (RuntimeException exception) {
            log.warn("Unable to fetch Frankfurter history for {}/{}: {}", base, quote, rootMessage(exception));
            return List.of();
        }
    }

    private List<HistoricalPoint> fetchYahooHistory(String symbol, int days) {
        String range = yahooRange(days);
        try {
            JsonNode payload = getJson(
                "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?range={range}&interval=1d",
                symbol,
                range
            );
            JsonNode results = payload.path("chart").path("result");
            if (!results.isArray() || results.isEmpty()) {
                return List.of();
            }

            JsonNode result = results.get(0);
            JsonNode timestamps = result.path("timestamp");
            JsonNode closes = result.path("indicators").path("quote").path(0).path("close");
            if (!timestamps.isArray() || !closes.isArray()) {
                return List.of();
            }

            List<HistoricalPoint> points = new ArrayList<>();
            int count = Math.min(timestamps.size(), closes.size());
            for (int index = 0; index < count; index++) {
                JsonNode close = closes.get(index);
                JsonNode timestamp = timestamps.get(index);
                if (!close.isNumber() || !timestamp.canConvertToLong()) {
                    continue;
                }
                String date = Instant.ofEpochSecond(timestamp.asLong())
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .toString();
                points.add(new HistoricalPoint(date, close.asDouble()));
            }
            return points;
        } catch (RuntimeException exception) {
            log.warn("Unable to fetch Yahoo history for {}: {}", symbol, rootMessage(exception));
            return List.of();
        }
    }

    private Optional<LiveRate> fetchYahooLiveRate(String symbol) {
        List<HistoricalPoint> history = fetchYahooHistory(symbol, 10);
        if (history.isEmpty()) {
            return Optional.empty();
        }

        HistoricalPoint latest = history.getLast();
        double previous = history.size() >= 2 ? history.get(history.size() - 2).rate() : latest.rate();
        double change = latest.rate() - previous;
        double percent = previous == 0 ? 0 : change / previous * 100;
        return Optional.of(new LiveRate(round4(latest.rate()), round4(change), round2(percent)));
    }

    private JsonNode getJson(String uriTemplate, Object... variables) {
        String response = restClient.get()
            .uri(uriTemplate, variables)
            .retrieve()
            .body(String.class);
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("Market provider returned an empty response");
        }
        try {
            return objectMapper.readTree(response);
        } catch (Exception exception) {
            throw new IllegalStateException("Market provider returned invalid JSON", exception);
        }
    }

    private static Map<String, Double> readFrankfurterRates(JsonNode payload) {
        Map<String, Double> rates = new LinkedHashMap<>();
        JsonNode values = payload.path("rates");
        if (!values.isObject()) {
            return rates;
        }
        values.fields().forEachRemaining(entry -> {
            if (entry.getValue().isNumber()) {
                rates.put(entry.getKey(), entry.getValue().asDouble());
            }
        });
        return rates;
    }

    private static String normalizePair(String pair) {
        if (pair == null) {
            throw new IllegalArgumentException("pair is required");
        }
        return pair.trim().toUpperCase(Locale.ROOT);
    }

    private static void validatePair(String pair) {
        if (COMMODITY_SYMBOLS.containsKey(pair)) {
            return;
        }
        String[] parts = pair.split("/", -1);
        if (parts.length != 2 || !parts[0].matches("[A-Z]{3}") || !parts[1].matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Invalid currency pair: " + pair);
        }
    }

    private static List<String> parseConfiguredPairs(String pairs) {
        String defaultPairs = String.join(",",
            "EUR/USD", "GBP/USD", "USD/JPY", "USD/CHF", "AUD/USD", "USD/CAD", "NZD/USD", "USD/CNY",
            "GOLD/USD", "SILVER/USD", "COPPER/USD", "WHEAT/USD", "SOYBEAN/USD", "CORN/USD",
            "WTI/USD", "BRENT/USD", "NDX/USD", "SPX/USD"
        );
        String value = pairs == null || pairs.isBlank() ? defaultPairs : pairs;
        return java.util.Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(candidate -> !candidate.isEmpty())
            .map(MarketDataService::normalizePair)
            .distinct()
            .toList();
    }

    private static List<HistoricalPoint> trimToRecent(List<HistoricalPoint> points, int days) {
        List<HistoricalPoint> sorted = points.stream()
            .sorted(Comparator.comparing(HistoricalPoint::date))
            .toList();
        int fromIndex = Math.max(0, sorted.size() - days);
        return sorted.subList(fromIndex, sorted.size());
    }

    private static String yahooRange(int days) {
        if (days >= 3650) {
            return "10y";
        }
        if (days >= 1825) {
            return "5y";
        }
        if (days >= 700) {
            return "2y";
        }
        if (days >= 240) {
            return "1y";
        }
        if (days >= 180) {
            return "6mo";
        }
        if (days >= 90) {
            return "3mo";
        }
        if (days >= 30) {
            return "1mo";
        }
        return days + "d";
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
