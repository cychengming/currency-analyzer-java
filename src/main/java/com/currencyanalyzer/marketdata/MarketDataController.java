package com.currencyanalyzer.marketdata;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
public class MarketDataController {

    private final MarketDataService marketDataService;

    public MarketDataController(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @GetMapping("/api/live-rates")
    public Map<String, LiveRate> liveRates() {
        return marketDataService.fetchLiveRates();
    }

    @GetMapping("/api/historical")
    public List<HistoricalPoint> historical(
        @RequestParam String pair,
        @RequestParam int days
    ) {
        return marketDataService.fetchHistorical(pair, days);
    }

    @GetMapping("/api/historical/{*legacyPath}")
    public List<HistoricalPoint> historicalLegacy(@PathVariable String legacyPath) {
        String path = legacyPath.startsWith("/") ? legacyPath.substring(1) : legacyPath;
        int separator = path.lastIndexOf('/');
        if (separator <= 0 || separator == path.length() - 1) {
            throw new IllegalArgumentException("Historical path must contain pair and days");
        }

        String pair = URLDecoder.decode(path.substring(0, separator), StandardCharsets.UTF_8);
        int days;
        try {
            days = Integer.parseInt(path.substring(separator + 1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("days must be a number");
        }
        return marketDataService.fetchHistorical(pair, days);
    }
}
