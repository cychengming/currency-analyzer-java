package com.currencyanalyzer.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks consecutive failed login attempts per client key inside a bounded sliding window
 * to mitigate password brute-force attacks without unbounded memory growth.
 */
@Service
public class LoginAttemptLimiter {

    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private final int maxAttempts;
    private final Cache<String, AtomicInteger> failedAttempts;

    @Autowired
    public LoginAttemptLimiter(
        @Value("${app.auth.login-max-attempts:10}") int maxAttempts,
        @Value("${app.auth.login-window-seconds:900}") long windowSeconds
    ) {
        this(maxAttempts, windowSeconds, Ticker.systemTicker());
    }

    LoginAttemptLimiter(int maxAttempts, long windowSeconds, Ticker ticker) {
        this.maxAttempts = maxAttempts;
        this.failedAttempts = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_CLIENTS)
            .expireAfterWrite(Duration.ofSeconds(Math.max(1L, windowSeconds)))
            .ticker(ticker)
            .build();
    }

    public boolean isBlocked(String key) {
        String normalized = normalize(key);
        if (maxAttempts <= 0 || normalized.isEmpty()) {
            return false;
        }
        AtomicInteger count = failedAttempts.getIfPresent(normalized);
        return count != null && count.get() >= maxAttempts;
    }

    public void recordFailure(String key) {
        String normalized = normalize(key);
        if (maxAttempts <= 0 || normalized.isEmpty()) {
            return;
        }
        AtomicInteger count = failedAttempts.get(normalized, ignored -> new AtomicInteger());
        if (count != null) {
            count.incrementAndGet();
        }
    }

    public void recordSuccess(String key) {
        String normalized = normalize(key);
        if (!normalized.isEmpty()) {
            failedAttempts.invalidate(normalized);
        }
    }

    private static String normalize(String key) {
        return key == null ? "" : key.trim();
    }
}
