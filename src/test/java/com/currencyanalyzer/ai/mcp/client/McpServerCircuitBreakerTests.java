package com.currencyanalyzer.ai.mcp.client;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerCircuitBreakerTests {

    @Test
    void tripsAtThresholdRejectsDuringOpenAllowsSingleHalfOpenProbeAndRecoversOrReopens() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-28T10:00:00Z"));
        McpServerCircuitBreaker breaker = new McpServerCircuitBreaker(3, Duration.ofSeconds(30), clock);

        assertEquals(McpServerCircuitBreaker.State.CLOSED, breaker.state());
        assertTrue(breaker.tryAcquirePermission());

        breaker.recordFailure();
        breaker.recordFailure();
        assertEquals(McpServerCircuitBreaker.State.CLOSED, breaker.state());
        assertEquals(2, breaker.consecutiveFailures());
        assertTrue(breaker.tryAcquirePermission());

        // Third consecutive failure trips the circuit OPEN
        breaker.recordFailure();
        assertEquals(McpServerCircuitBreaker.State.OPEN, breaker.state());
        assertEquals(3, breaker.consecutiveFailures());
        assertFalse(breaker.tryAcquirePermission());

        // Still before openDuration -> remains rejected
        clock.advance(Duration.ofSeconds(29));
        assertFalse(breaker.tryAcquirePermission());
        assertEquals(McpServerCircuitBreaker.State.OPEN, breaker.state());

        // Past openDuration -> single HALF_OPEN probe allowed
        clock.advance(Duration.ofSeconds(1));
        assertTrue(breaker.tryAcquirePermission());
        assertEquals(McpServerCircuitBreaker.State.HALF_OPEN, breaker.state());
        assertFalse(breaker.tryAcquirePermission());

        // Probe failure immediately re-opens circuit
        breaker.recordFailure();
        assertEquals(McpServerCircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.tryAcquirePermission());

        // After another cooldown, probe succeeds -> CLOSED and counters reset
        clock.advance(Duration.ofSeconds(30));
        assertTrue(breaker.tryAcquirePermission());
        breaker.recordSuccess();
        assertEquals(McpServerCircuitBreaker.State.CLOSED, breaker.state());
        assertEquals(0, breaker.consecutiveFailures());
        assertTrue(breaker.tryAcquirePermission());
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        private MutableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        private void advance(Duration delta) {
            current.updateAndGet(now -> now.plus(delta));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
