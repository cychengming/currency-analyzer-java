package com.currencyanalyzer.marketdata;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Process-local snapshot of live quotes. Caffeine supplies the TTL; a refresh lock
 * collapses concurrent misses onto one upstream load. Empty snapshots are not stored,
 * so a provider outage is retried on the next request.
 */
final class LiveRateCache {

    private static final String KEY = "live-rates";

    private final Cache<String, Map<String, LiveRate>> cache;
    private final Object refreshLock = new Object();

    LiveRateCache(Duration ttl, Ticker ticker) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("live rate cache TTL must be positive");
        }
        if (ticker == null) {
            throw new IllegalArgumentException("live rate cache ticker is required");
        }
        this.cache = Caffeine.newBuilder()
            .expireAfterWrite(ttl)
            .maximumSize(1)
            .ticker(ticker)
            .build();
    }

    Map<String, LiveRate> get(Supplier<Map<String, LiveRate>> loader) {
        Map<String, LiveRate> cached = cache.getIfPresent(KEY);
        if (cached != null) {
            return cached;
        }
        synchronized (refreshLock) {
            cached = cache.getIfPresent(KEY);
            if (cached != null) {
                return cached;
            }
            Map<String, LiveRate> fresh = loader.get();
            if (fresh == null || fresh.isEmpty()) {
                return Map.of();
            }
            Map<String, LiveRate> snapshot = Map.copyOf(fresh);
            cache.put(KEY, snapshot);
            return snapshot;
        }
    }
}
