package com.currencyanalyzer.auth;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginAttemptLimiterTests {

    @Test
    void blocksAfterMaxFailuresResetsOnSuccessAndExpiresAfterWindow() {
        AtomicLong nanos = new AtomicLong();
        Ticker ticker = nanos::get;
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(3, 60, ticker);

        assertFalse(limiter.isBlocked("10.0.0.1"));
        limiter.recordFailure("10.0.0.1");
        limiter.recordFailure("10.0.0.1");
        assertFalse(limiter.isBlocked("10.0.0.1"));

        limiter.recordFailure("10.0.0.1");
        assertTrue(limiter.isBlocked("10.0.0.1"));
        assertFalse(limiter.isBlocked("10.0.0.2"));

        limiter.recordSuccess("10.0.0.1");
        assertFalse(limiter.isBlocked("10.0.0.1"));

        limiter.recordFailure("10.0.0.1");
        limiter.recordFailure("10.0.0.1");
        limiter.recordFailure("10.0.0.1");
        assertTrue(limiter.isBlocked("10.0.0.1"));

        nanos.addAndGet(Duration.ofSeconds(61).toNanos());
        assertFalse(limiter.isBlocked("10.0.0.1"));
    }

    @Test
    void ignoresBlankKeysAndDisabledThresholds() {
        LoginAttemptLimiter disabled = new LoginAttemptLimiter(0, 60, Ticker.systemTicker());
        disabled.recordFailure("10.0.0.1");
        assertFalse(disabled.isBlocked("10.0.0.1"));

        LoginAttemptLimiter active = new LoginAttemptLimiter(1, 60, Ticker.systemTicker());
        active.recordFailure(null);
        active.recordFailure("   ");
        active.recordSuccess(" ");
        assertFalse(active.isBlocked(null));
        assertFalse(active.isBlocked(" "));
    }
}
