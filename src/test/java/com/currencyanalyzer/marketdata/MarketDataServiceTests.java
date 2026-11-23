package com.currencyanalyzer.marketdata;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MarketDataServiceTests {

    private static final Clock FIXED_CLOCK =
        Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void liveRatesHitTheProviderOncePerTtlAndSkipUnconfiguredSymbols() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://query1.finance.yahoo.com/v8/finance/chart/EURUSD%3DX?range=5d&interval=1d"))
            .andExpect(header("User-Agent", "Mozilla/5.0 (compatible; CurrencyAnalyzer/1.0)"))
            .andRespond(withSuccess("""
                {
                  "chart": {
                    "result": [{
                      "meta": {
                        "regularMarketPrice": 1.0950,
                        "chartPreviousClose": 1.0900
                      },
                      "indicators": {
                        "quote": [{
                          "close": [1.0880, 1.0900]
                        }]
                      }
                    }]
                  }
                }
                """, MediaType.APPLICATION_JSON));

        MarketDataService service = new MarketDataService(
            builder.build(),
            FIXED_CLOCK,
            List.of("EUR/USD"),
            new LiveRateCache(Duration.ofSeconds(60), Ticker.systemTicker())
        );

        Map<String, LiveRate> first = service.fetchLiveRates();
        assertThat(first).containsKey("EUR/USD");
        assertThat(first.get("EUR/USD")).isEqualTo(new LiveRate(1.095, 0.005, 0.46));

        Map<String, LiveRate> second = service.fetchLiveRates();
        assertThat(second).isEqualTo(first);
        server.verify();
    }

    @Test
    void fallsBackToFrankfurterWhenYahooFxFails() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://query1.finance.yahoo.com/v8/finance/chart/EURUSD%3DX?range=5d&interval=1d"))
            .andRespond(withServerError());
        server.expect(requestTo("https://api.frankfurter.app/latest?from=USD&to=EUR"))
            .andRespond(withSuccess("""
                {"date":"2026-11-19","rates":{"EUR":0.92}}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.frankfurter.app/2026-11-18?from=USD&to=EUR"))
            .andRespond(withSuccess("""
                {"date":"2026-11-18","rates":{"EUR":0.91}}
                """, MediaType.APPLICATION_JSON));

        MarketDataService service = new MarketDataService(
            builder.build(),
            FIXED_CLOCK,
            List.of("EUR/USD"),
            new LiveRateCache(Duration.ofSeconds(60), Ticker.systemTicker())
        );

        Map<String, LiveRate> rates = service.fetchLiveRates();
        assertThat(rates).containsKey("EUR/USD");
        assertThat(rates.get("EUR/USD").rate()).isEqualTo(1.087);
        server.verify();
    }

    @Test
    void commodityLiveRateUsesRegularMarketPriceAndFallsBackToDailyClose() {
        RestClient.Builder goldBuilder = RestClient.builder();
        MockRestServiceServer goldServer = MockRestServiceServer.bindTo(goldBuilder).build();
        goldServer.expect(requestTo("https://query1.finance.yahoo.com/v8/finance/chart/GC%3DF?range=5d&interval=1d"))
            .andRespond(withSuccess("""
                {
                  "chart": {
                    "result": [{
                      "meta": {
                        "regularMarketPrice": 2655.50,
                        "chartPreviousClose": 2630.00
                      },
                      "indicators": {
                        "quote": [{
                          "close": [2610.00, 2630.00]
                        }]
                      }
                    }]
                  }
                }
                """, MediaType.APPLICATION_JSON));

        MarketDataService goldService = new MarketDataService(
            goldBuilder.build(),
            FIXED_CLOCK,
            List.of("GOLD/USD"),
            new LiveRateCache(Duration.ofSeconds(60), Ticker.systemTicker())
        );
        Map<String, LiveRate> goldRates = goldService.fetchLiveRates();
        assertThat(goldRates.get("GOLD/USD")).isEqualTo(new LiveRate(2655.5, 25.5, 0.97));
        goldServer.verify();

        RestClient.Builder silverBuilder = RestClient.builder();
        MockRestServiceServer silverServer = MockRestServiceServer.bindTo(silverBuilder).build();
        silverServer.expect(requestTo("https://query1.finance.yahoo.com/v8/finance/chart/SI%3DF?range=5d&interval=1d"))
            .andRespond(withSuccess("""
                {
                  "chart": {
                    "result": [{
                      "meta": {},
                      "indicators": {
                        "quote": [{
                          "close": [30.00, 31.50]
                        }]
                      }
                    }]
                  }
                }
                """, MediaType.APPLICATION_JSON));

        MarketDataService silverService = new MarketDataService(
            silverBuilder.build(),
            FIXED_CLOCK,
            List.of("SILVER/USD"),
            new LiveRateCache(Duration.ofSeconds(60), Ticker.systemTicker())
        );
        Map<String, LiveRate> silverRates = silverService.fetchLiveRates();
        assertThat(silverRates.get("SILVER/USD")).isEqualTo(new LiveRate(31.5, 1.5, 5.0));
        silverServer.verify();
    }

    @Test
    void parsesProxyEnvironmentValuesSafely() {
        assertThat(MarketDataService.parseProxyAddress(null)).isNull();
        assertThat(MarketDataService.parseProxyAddress("   ")).isNull();
        assertThat(MarketDataService.parseProxyAddress("not a valid uri")).isNull();
        assertThat(MarketDataService.parseProxyAddress("http://127.0.0.1:7890"))
            .isEqualTo(new InetSocketAddress("127.0.0.1", 7890));
        assertThat(MarketDataService.parseProxyAddress("127.0.0.1:7890"))
            .isEqualTo(new InetSocketAddress("127.0.0.1", 7890));
        assertThat(MarketDataService.parseProxyAddress("https://proxy.example.com"))
            .isEqualTo(new InetSocketAddress("proxy.example.com", 443));
    }
}
