package com.currencyanalyzer.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
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
    private static final int HISTORICAL_CACHE_MAX_ENTRIES = 200;
    private static final long DEFAULT_HISTORICAL_CACHE_TTL_SECONDS = 300;
    private static final String HTTP_USER_AGENT = "Mozilla/5.0 (compatible; CurrencyAnalyzer/1.0)";

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
    private final LiveRateCache liveRateCache;
    private final Cache<HistoricalKey, List<HistoricalPoint>> historicalCache;

    @Autowired
    public MarketDataService(
        RestClient.Builder restClientBuilder,
        Clock clock,
        @Value("${app.market.pairs:}") String pairs,
        @Value("${app.market.live-cache-ttl-seconds:60}") long liveCacheTtlSeconds,
        @Value("${app.market.historical-cache-ttl-seconds:300}") long historicalCacheTtlSeconds
    ) {
        this(
            buildRestClient(restClientBuilder),
            clock,
            parseConfiguredPairs(pairs),
            new LiveRateCache(Duration.ofSeconds(liveCacheTtlSeconds), Ticker.systemTicker()),
            buildHistoricalCache(historicalCacheTtlSeconds)
        );
    }

    MarketDataService(
        RestClient restClient,
        Clock clock,
        List<String> configuredPairs,
        LiveRateCache liveRateCache
    ) {
        this(
            restClient,
            clock,
            configuredPairs,
            liveRateCache,
            buildHistoricalCache(DEFAULT_HISTORICAL_CACHE_TTL_SECONDS)
        );
    }

    MarketDataService(
        RestClient restClient,
        Clock clock,
        List<String> configuredPairs,
        LiveRateCache liveRateCache,
        Cache<HistoricalKey, List<HistoricalPoint>> historicalCache
    ) {
        this.restClient = restClient;
        this.objectMapper = new ObjectMapper();
        this.clock = clock;
        this.configuredPairs = configuredPairs.stream()
            .map(MarketDataService::normalizePair)
            .toList();
        this.liveRateCache = liveRateCache;
        this.historicalCache = historicalCache;
    }

    private static Cache<HistoricalKey, List<HistoricalPoint>> buildHistoricalCache(long ttlSeconds) {
        return Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(Math.max(1, ttlSeconds)))
            .maximumSize(HISTORICAL_CACHE_MAX_ENTRIES)
            .build();
    }

    public List<String> configuredPairs() {
        return configuredPairs;
    }

    public Map<String, LiveRate> fetchLiveRates() {
        return liveRateCache.get(this::loadLiveRates);
    }

    private Map<String, LiveRate> loadLiveRates() {
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

    private static RestClient buildRestClient(RestClient.Builder restClientBuilder) {
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL);
        InetSocketAddress proxyAddress = resolveProxyAddress();
        if (proxyAddress != null) {
            log.info("Configuring market data HTTP client with proxy {}", proxyAddress);
            httpClientBuilder.proxy(ProxySelector.of(proxyAddress));
        }
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClientBuilder.build());
        requestFactory.setReadTimeout(Duration.ofSeconds(12));
        return restClientBuilder.requestFactory(requestFactory).build();
    }

    static InetSocketAddress resolveProxyAddress() {
        for (String envName : List.of("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")) {
            InetSocketAddress parsed = parseProxyAddress(System.getenv(envName));
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    static InetSocketAddress parseProxyAddress(String proxyUrl) {
        if (proxyUrl == null || proxyUrl.isBlank()) {
            return null;
        }
        String candidate = proxyUrl.trim();
        if (!candidate.contains("://")) {
            candidate = "http://" + candidate;
        }
        try {
            URI uri = URI.create(candidate);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            int port = uri.getPort();
            if (port <= 0) {
                port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            }
            if (port > 65535) {
                return null;
            }
            return new InetSocketAddress(host, port);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public List<HistoricalPoint> fetchHistorical(String requestedPair, int requestedDays) {
        String pair = normalizePair(requestedPair);
        validatePair(pair);
        if (requestedDays < 1 || requestedDays > MAX_HISTORY_DAYS) {
            throw new IllegalArgumentException("days must be between 1 and " + MAX_HISTORY_DAYS);
        }

        HistoricalKey cacheKey = new HistoricalKey(pair, requestedDays);
        List<HistoricalPoint> cached = historicalCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<HistoricalPoint> loaded = List.copyOf(loadHistorical(pair, requestedDays));
        if (!loaded.isEmpty()) {
            historicalCache.put(cacheKey, loaded);
        }
        return loaded;
    }

    private List<HistoricalPoint> loadHistorical(String pair, int requestedDays) {
        String commoditySymbol = COMMODITY_SYMBOLS.get(pair);
        if (commoditySymbol != null) {
            return trimToRecent(fetchYahooHistory(commoditySymbol, requestedDays), requestedDays);
        }

        String[] parts = pair.split("/");
        List<HistoricalPoint> points = fetchYahooHistory(parts[0] + parts[1] + "=X", requestedDays);
        if (points.isEmpty()) {
            points = fetchFrankfurterHistory(parts[0], parts[1], requestedDays);
        }
        return trimToRecent(points, requestedDays);
    }

    private Map<String, LiveRate> fetchCommodityRates() {
        Map<String, String> symbols = new LinkedHashMap<>();
        for (String pair : configuredPairs) {
            String symbol = COMMODITY_SYMBOLS.get(pair);
            if (symbol != null) {
                symbols.put(pair, symbol);
            }
        }
        return fetchYahooRatesBySymbol(symbols);
    }

    private Map<String, LiveRate> fetchFxRates() {
        List<String> fxPairs = configuredPairs.stream()
            .filter(pair -> !COMMODITY_SYMBOLS.containsKey(pair))
            .toList();
        if (fxPairs.isEmpty()) {
            return Map.of();
        }

        Map<String, LiveRate> rates = new LinkedHashMap<>(fetchFxRatesFromYahoo(fxPairs));
        List<String> missingPairs = fxPairs.stream()
            .filter(pair -> !rates.containsKey(pair))
            .toList();
        if (!missingPairs.isEmpty()) {
            rates.putAll(fetchFxRatesFromFrankfurter(missingPairs));
        }
        return rates;
    }

    private Map<String, LiveRate> fetchFxRatesFromFrankfurter(List<String> fxPairs) {
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
            return Map.of();
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
        Map<String, String> symbols = new LinkedHashMap<>();
        for (String pair : pairs) {
            symbols.put(pair, pair.replace("/", "") + "=X");
        }
        return fetchYahooRatesBySymbol(symbols);
    }

    private Map<String, LiveRate> fetchYahooRatesBySymbol(Map<String, String> symbols) {
        if (symbols.isEmpty()) {
            return Map.of();
        }
        Map<String, LiveRate> rates = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<Optional<LiveRate>>> tasks = new LinkedHashMap<>();
            symbols.forEach((pair, symbol) ->
                tasks.put(pair, executor.submit(() -> fetchYahooLiveRate(symbol))));

            tasks.forEach((pair, task) -> {
                try {
                    task.get(15, TimeUnit.SECONDS).ifPresent(rate -> rates.put(pair, rate));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while fetching {}", pair);
                } catch (ExecutionException | TimeoutException exception) {
                    log.warn("Unable to fetch Yahoo quote for {}: {}", pair, rootMessage(exception));
                }
            });
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

            Map<String, HistoricalPoint> byDate = new TreeMap<>();
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
                byDate.put(date, new HistoricalPoint(date, close.asDouble()));
            }
            return new ArrayList<>(byDate.values());
        } catch (RuntimeException exception) {
            log.warn("Unable to fetch Yahoo history for {}: {}", symbol, rootMessage(exception));
            return List.of();
        }
    }

    private Optional<LiveRate> fetchYahooLiveRate(String symbol) {
        try {
            JsonNode payload = getJson(
                "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?range=5d&interval=1d",
                symbol
            );
            JsonNode result = payload.path("chart").path("result").path(0);
            if (!result.isObject()) {
                return Optional.empty();
            }

            JsonNode meta = result.path("meta");
            List<Double> closes = extractValidCloses(result);

            Double current = positiveNumber(meta.path("regularMarketPrice"));
            if (current == null) {
                if (closes.isEmpty()) {
                    return Optional.empty();
                }
                current = closes.getLast();
            }

            return Optional.of(resolveYahooLiveRate(meta, closes, current));
        } catch (RuntimeException exception) {
            log.warn("Unable to fetch Yahoo live quote for {}: {}", symbol, rootMessage(exception));
            return Optional.empty();
        }
    }

    private static LiveRate resolveYahooLiveRate(JsonNode meta, List<Double> closes, double current) {
        Double fulldayChange = finiteNumber(meta.path("fulldayChange"));
        if (fulldayChange != null) {
            double change = fulldayChange;
            Double percent = resolvePercent(meta, current, change);
            return new LiveRate(round4(current), round4(change), round2(percent));
        }

        Double marketChangePercent = finiteNumber(meta.path("regularMarketChangePercent"));
        if (marketChangePercent != null) {
            double percent = marketChangePercent;
            double divisor = 1.0 + percent / 100.0;
            double change = divisor > 0 ? current - (current / divisor) : 0.0;
            return new LiveRate(round4(current), round4(change), round2(percent));
        }

        double previous = resolvePreviousClose(meta, closes, current);
        double change = current - previous;
        double percent = previous == 0 ? 0.0 : change / previous * 100.0;
        return new LiveRate(round4(current), round4(change), round2(percent));
    }

    private static double resolvePercent(JsonNode meta, double current, double change) {
        Double percent = finiteNumber(meta.path("fulldayChangePercent"));
        if (percent == null) {
            percent = finiteNumber(meta.path("regularMarketChangePercent"));
        }
        if (percent != null) {
            return percent;
        }
        double prior = current - change;
        return prior > 0 ? change / prior * 100.0 : 0.0;
    }

    private static double resolvePreviousClose(JsonNode meta, List<Double> closes, double current) {
        Double previous = positiveNumber(meta.path("previousClose"));
        if (previous != null) {
            return previous;
        }
        if (closes.size() >= 2) {
            return closes.get(closes.size() - 2);
        }
        previous = positiveNumber(meta.path("chartPreviousClose"));
        return previous != null ? previous : current;
    }

    private static List<Double> extractValidCloses(JsonNode result) {
        JsonNode closesNode = result.path("indicators").path("quote").path(0).path("close");
        if (!closesNode.isArray()) {
            return List.of();
        }
        List<Double> closes = new ArrayList<>();
        for (JsonNode close : closesNode) {
            if (close.isNumber() && Double.isFinite(close.asDouble())) {
                closes.add(close.asDouble());
            }
        }
        return closes;
    }

    private static Double positiveNumber(JsonNode node) {
        Double value = finiteNumber(node);
        return value != null && value > 0 ? value : null;
    }

    private static Double finiteNumber(JsonNode node) {
        if (!node.isNumber()) {
            return null;
        }
        double value = node.asDouble();
        return Double.isFinite(value) ? value : null;
    }

    private JsonNode getJson(String uriTemplate, Object... variables) {
        String response = restClient.get()
            .uri(uriTemplate, variables)
            .header("User-Agent", HTTP_USER_AGENT)
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

    record HistoricalKey(String pair, int days) {
    }
}
