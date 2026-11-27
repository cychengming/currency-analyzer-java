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
                        "previousClose": 1.0900,
                        "chartPreviousClose": 1.0880
                      },
                      "indicators": {
                        "quote": [{
                          "close": [1.0880, 1.0900, 1.0950]
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
                        "previousClose": 2630.00,
                        "chartPreviousClose": 2610.00
                      },
                      "indicators": {
                        "quote": [{
                          "close": [2610.00, 2630.00, 2655.50]
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

    @Test
    void historicalFxUsesYahooPrimaryDeduplicatesSameDayAndCaches() {
        long nov20 = Instant.parse("2026-11-20T00:00:00Z").getEpochSecond();
        long nov23Early = Instant.parse("2026-11-23T01:00:00Z").getEpochSecond();
        long nov23Later = Instant.parse("2026-11-23T09:00:00Z").getEpochSecond();

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://query1.finance.yahoo.com/v8/finance/chart/USDJPY%3DX?range=1mo&interval=1d"))
            .andRespond(withSuccess("""
                {
                  "chart": {
                    "result": [{
                      "timestamp": [%d, %d, %d],
                      "indicators": {
                        "quote": [{
                          "close": [153.80, 154.10, 154.55]
                        }]
                      }
                    }]
                  }
                }
                """.formatted(nov20, nov23Early, nov23Later), MediaType.APPLICATION_JSON));

        MarketDataService service = new MarketDataService(
            builder.build(),
            FIXED_CLOCK,
            List.of("USD/JPY"),
            new LiveRateCache(Duration.ofSeconds(60), Ticker.systemTicker())
        );

        List<HistoricalPoint> first = service.fetchHistorical("USD/JPY", 30);
        assertThat(first).containsExactly(
            new HistoricalPoint("2026-11-20", 153.8),
            new HistoricalPoint("2026-11-23", 154.55)
        );

        List<HistoricalPoint> second = service.fetchHistorical("USD/JPY", 30);
        assertThat(second).isEqualTo(first);
        server.verify();
    }

    @Test
    void historicalFxFallsBackToFrankfurterWhenYahooFails() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://query1.finance.yahoo.com/v8/finance/chart/USDJPY%3DX?range=1mo&interval=1d"))
            .andRespond(withServerError());
        server.expect(requestTo("https://api.frankfurter.app/2026-10-21..2026-11-20?from=USD&to=JPY"))
            .andRespond(withSuccess("""
                {"rates":{"2026-11-19":{"JPY":153.2},"2026-11-20":{"JPY":153.8}}}
                """, MediaType.APPLICATION_JSON));

        MarketDataService service = new MarketDataService(
            builder.build(),
            FIXED_CLOCK,
            List.of("USD/JPY"),
            new LiveRateCache(Duration.ofSeconds(60), Ticker.systemTicker())
        );

        List<HistoricalPoint> points = service.fetchHistorical("USD/JPY", 30);
        assertThat(points).containsExactly(
            new HistoricalPoint("2026-11-19", 153.2),
            new HistoricalPoint("2026-11-20", 153.8)
        );
        server.verify();
    }

    @Test
    void ndxLiveRateUsesFulldayChangeInsteadOfFiveDayChartPreviousClose() {
        MarketDataService service = serviceWithMockYahooResponse("%5ENDX", List.of("NDX/USD"), """
            {
              "chart": {
                "result": [{
                  "meta": {
                    "regularMarketPrice": 26746.096,
                    "regularMarketChangePercent": 6.86,
                    "fulldayChange": 1717.096,
                    "fulldayChangePercent": 6.86,
                    "chartPreviousClose": 27197.15
                  },
                  "indicators": {
                    "quote": [{
                      "close": [27001.78, 27116.26, 26517.05, 25028.98, 26746.10]
                    }]
                  }
                }]
              }
            }
            """);

        assertThat(service.configuredPairs()).containsExactly("NDX/USD");
        Map<String, LiveRate> rates = service.fetchLiveRates();
        assertThat(rates.get("NDX/USD")).isEqualTo(new LiveRate(26746.096, 1717.096, 6.86));
    }

    @Test
    void yahooLiveRateFallsBackToPriorDailyCloseBeforeChartPreviousClose() {
        MarketDataService service = serviceWithMockYahooResponse("%5ENDX", List.of("NDX/USD"), """
            {
              "chart": {
                "result": [{
                  "meta": {
                    "regularMarketPrice": 26746.096,
                    "chartPreviousClose": 27197.15
                  },
                  "indicators": {
                    "quote": [{
                      "close": [27001.78, 25028.98, 26746.10]
                    }]
                  }
                }]
              }
            }
            """);

        Map<String, LiveRate> rates = service.fetchLiveRates();
        assertThat(rates.get("NDX/USD")).isEqualTo(new LiveRate(26746.096, 1717.116, 6.86));
    }

    @Test
    void yahooLiveRateSupportsRegularMarketChangePercentAndSingleBarFallbacks() {
        MarketDataService percentFallback = serviceWithMockYahooResponse("%5ENDX", List.of("NDX/USD"), """
            {
              "chart": {
                "result": [{
                  "meta": {
                    "regularMarketPrice": 110.0,
                    "regularMarketChangePercent": 10.0,
                    "chartPreviousClose": 125.0
                  },
                  "indicators": {
                    "quote": [{
                      "close": [110.0]
                    }]
                  }
                }]
              }
            }
            """);
        assertThat(percentFallback.fetchLiveRates().get("NDX/USD"))
            .isEqualTo(new LiveRate(110.0, 10.0, 10.0));

        MarketDataService fulldayDerivedPercent = serviceWithMockYahooResponse("%5EGSPC", List.of("SPX/USD"), """
            {
              "chart": {
                "result": [{
                  "meta": {
                    "regularMarketPrice": 105.0,
                    "fulldayChange": 5.0
                  },
                  "indicators": {
                    "quote": [{
                      "close": [105.0]
                    }]
                  }
                }]
              }
            }
            """);
        assertThat(fulldayDerivedPercent.fetchLiveRates().get("SPX/USD"))
            .isEqualTo(new LiveRate(105.0, 5.0, 5.0));

        MarketDataService chartPreviousLastResort = serviceWithMockYahooResponse("%5EGSPC", List.of("SPX/USD"), """
            {
              "chart": {
                "result": [{
                  "meta": {
                    "regularMarketPrice": 105.0,
                    "chartPreviousClose": 100.0
                  },
                  "indicators": {
                    "quote": [{
                      "close": [105.0]
                    }]
                  }
                }]
              }
            }
            """);
        assertThat(chartPreviousLastResort.fetchLiveRates().get("SPX/USD"))
            .isEqualTo(new LiveRate(105.0, 5.0, 5.0));

        MarketDataService fulldayWithMarketPercent = serviceWithMockYahooResponse("%5EGSPC", List.of("SPX/USD"), """
            {
              "chart": {
                "result": [{
                  "meta": {
                    "regularMarketPrice": 105.0,
                    "fulldayChange": 5.0,
                    "regularMarketChangePercent": 5.25
                  },
                  "indicators": {
                    "quote": [{
                      "close": [105.0]
                    }]
                  }
                }]
              }
            }
            """);
        assertThat(fulldayWithMarketPercent.fetchLiveRates().get("SPX/USD"))
            .isEqualTo(new LiveRate(105.0, 5.0, 5.25));

        MarketDataService singleBarNoPrevious = serviceWithMockYahooResponse("%5EGSPC", List.of("SPX/USD"), """
            {
              "chart": {
                "result": [{
                  "meta": {
                    "regularMarketPrice": 105.0
                  },
                  "indicators": {
                    "quote": [{
                      "close": [105.0]
                    }]
                  }
                }]
              }
            }
            """);
        assertThat(singleBarNoPrevious.fetchLiveRates().get("SPX/USD"))
            .isEqualTo(new LiveRate(105.0, 0.0, 0.0));
    }

    @Test
    void historicalOhlcParsesAndSanitizesYahooBarsAndFallsBackToFrankfurter() {
        long nov19 = Instant.parse("2026-11-19T00:00:00Z").getEpochSecond();
        long nov20 = Instant.parse("2026-11-20T00:00:00Z").getEpochSecond();

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://query1.finance.yahoo.com/v8/finance/chart/GC%3DF?range=1mo&interval=1d"))
            .andRespond(withSuccess("""
                {
                  "chart": {
                    "result": [{
                      "timestamp": [%d, %d],
                      "indicators": {
                        "quote": [{
                          "open": [2600.0, null],
                          "high": [2595.0, 2650.0],
                          "low": [2615.0, 2610.0],
                          "close": [2610.0, 2640.0],
                          "volume": [1200.0, -5.0]
                        }]
                      }
                    }]
                  }
                }
                """.formatted(nov19, nov20), MediaType.APPLICATION_JSON));

        MarketDataService service = new MarketDataService(
            builder.build(),
            FIXED_CLOCK,
            List.of("GOLD/USD", "EUR/USD"),
            new LiveRateCache(Duration.ofSeconds(60), Ticker.systemTicker())
        );

        List<OhlcBar> first = service.fetchHistoricalOhlc("GOLD/USD", 30);
        assertThat(first).containsExactly(
            new OhlcBar("2026-11-19", 2600.0, 2610.0, 2600.0, 2610.0, 1200.0),
            new OhlcBar("2026-11-20", 2640.0, 2650.0, 2610.0, 2640.0, 0.0)
        );
        assertThat(service.fetchHistoricalOhlc("GOLD/USD", 30)).isEqualTo(first);
        server.verify();

        RestClient.Builder fallbackBuilder = RestClient.builder();
        MockRestServiceServer fallbackServer = MockRestServiceServer.bindTo(fallbackBuilder).build();
        fallbackServer.expect(requestTo("https://query1.finance.yahoo.com/v8/finance/chart/EURUSD%3DX?range=1mo&interval=1d"))
            .andRespond(withServerError());
        fallbackServer.expect(requestTo("https://api.frankfurter.app/2026-10-21..2026-11-20?from=EUR&to=USD"))
            .andRespond(withSuccess("""
                {"rates":{"2026-11-20":{"USD":1.08}}}
                """, MediaType.APPLICATION_JSON));

        MarketDataService fallbackService = new MarketDataService(
            fallbackBuilder.build(),
            FIXED_CLOCK,
            List.of("EUR/USD"),
            new LiveRateCache(Duration.ofSeconds(60), Ticker.systemTicker())
        );
        assertThat(fallbackService.fetchHistoricalOhlc("EUR/USD", 30)).containsExactly(
            new OhlcBar("2026-11-20", 1.08, 1.08, 1.08, 1.08, 0.0)
        );
        fallbackServer.verify();
    }

    private static MarketDataService serviceWithMockYahooResponse(
        String encodedSymbol,
        List<String> pairs,
        String payload
    ) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://query1.finance.yahoo.com/v8/finance/chart/" + encodedSymbol + "?range=5d&interval=1d"))
            .andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));
        return new MarketDataService(
            builder.build(),
            FIXED_CLOCK,
            pairs,
            new LiveRateCache(Duration.ofSeconds(60), Ticker.systemTicker())
        );
    }
}
