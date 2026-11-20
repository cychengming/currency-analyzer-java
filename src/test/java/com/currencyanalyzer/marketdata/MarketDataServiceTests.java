package com.currencyanalyzer.marketdata;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MarketDataServiceTests {

    @Test
    void liveRatesHitTheProviderOncePerTtlAndSkipUnconfiguredSymbols() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
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
            java.time.Clock.fixed(java.time.Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC),
            List.of("EUR/USD"),
            new LiveRateCache(Duration.ofSeconds(60), Ticker.systemTicker())
        );

        assertThat(service.fetchLiveRates()).containsKey("EUR/USD");
        assertThat(service.fetchLiveRates()).containsKey("EUR/USD");
        server.verify();
    }
}
