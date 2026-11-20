package com.currencyanalyzer.marketdata;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveRateCacheTests {

    private static final LiveRate EUR_USD = new LiveRate(1.0876, 0.0021, 0.19);

    @Test
    void servesTheSameSnapshotUntilTheTtlExpires() {
        MutableTicker ticker = new MutableTicker();
        LiveRateCache cache = new LiveRateCache(Duration.ofSeconds(60), ticker);
        AtomicInteger loads = new AtomicInteger();

        Map<String, LiveRate> first = cache.get(() -> {
            loads.incrementAndGet();
            return Map.of("EUR/USD", EUR_USD);
        });
        Map<String, LiveRate> second = cache.get(() -> {
            loads.incrementAndGet();
            return Map.of("EUR/USD", new LiveRate(9, 9, 9));
        });

        ticker.advance(Duration.ofSeconds(59));
        Map<String, LiveRate> third = cache.get(() -> {
            loads.incrementAndGet();
            return Map.of();
        });

        ticker.advance(Duration.ofSeconds(1));
        Map<String, LiveRate> refreshed = cache.get(() -> {
            loads.incrementAndGet();
            return Map.of("GBP/USD", new LiveRate(1.27, 0.01, 0.8));
        });

        assertThat(first).containsEntry("EUR/USD", EUR_USD);
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        assertThat(refreshed).containsOnlyKeys("GBP/USD");
        assertThat(loads).hasValue(2);
    }

    @Test
    void doesNotCacheAnEmptySnapshot() {
        LiveRateCache cache = new LiveRateCache(Duration.ofMinutes(1), Ticker.systemTicker());
        AtomicInteger loads = new AtomicInteger();

        assertThat(cache.get(() -> {
            loads.incrementAndGet();
            return Map.of();
        })).isEmpty();
        assertThat(cache.get(() -> {
            loads.incrementAndGet();
            return null;
        })).isEmpty();
        assertThat(cache.get(() -> {
            loads.incrementAndGet();
            return Map.of("EUR/USD", EUR_USD);
        })).containsEntry("EUR/USD", EUR_USD);

        assertThat(loads).hasValue(3);
    }

    @Test
    void retriesAfterAFailedLoad() {
        LiveRateCache cache = new LiveRateCache(Duration.ofMinutes(1), Ticker.systemTicker());

        assertThatThrownBy(() -> cache.get(() -> {
            throw new IllegalStateException("provider down");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(cache.get(() -> Map.of("EUR/USD", EUR_USD))).containsEntry("EUR/USD", EUR_USD);
    }

    @Test
    void concurrentMissesShareOneLoad() throws Exception {
        LiveRateCache cache = new LiveRateCache(Duration.ofMinutes(1), Ticker.systemTicker());
        AtomicInteger loads = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            Future<?>[] futures = new Future<?>[8];
            for (int index = 0; index < futures.length; index++) {
                futures[index] = executor.submit(() -> cache.get(() -> {
                    loads.incrementAndGet();
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    return Map.of("EUR/USD", EUR_USD);
                }));
            }
            for (Future<?> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(Map.of("EUR/USD", EUR_USD));
            }
            assertThat(loads).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsANonPositiveTtl() {
        assertThatThrownBy(() -> new LiveRateCache(Duration.ZERO, Ticker.systemTicker()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("TTL");
    }

    private static final class MutableTicker implements Ticker {
        private long nanos;

        void advance(Duration duration) {
            nanos += duration.toNanos();
        }

        @Override
        public long read() {
            return nanos;
        }
    }
}
