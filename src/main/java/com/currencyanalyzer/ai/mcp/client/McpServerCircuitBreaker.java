package com.currencyanalyzer.ai.mcp.client;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe sliding circuit breaker isolating external MCP server failures from AskAI turns.
 */
public final class McpServerCircuitBreaker {

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final int failureThreshold;
    private final Duration openDuration;
    private final Clock clock;
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicReference<Instant> openedAt = new AtomicReference<>(Instant.EPOCH);
    private final AtomicBoolean halfOpenProbeInFlight = new AtomicBoolean(false);

    public McpServerCircuitBreaker(int failureThreshold, Duration openDuration, Clock clock) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDuration = openDuration == null || openDuration.isNegative() ? Duration.ofSeconds(30) : openDuration;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public boolean tryAcquirePermission() {
        State current = state.get();
        if (current == State.CLOSED) {
            return true;
        }
        if (current == State.OPEN) {
            Instant openStart = openedAt.get();
            if (!clock.instant().isBefore(openStart.plus(openDuration))
                && halfOpenProbeInFlight.compareAndSet(false, true)) {
                state.compareAndSet(State.OPEN, State.HALF_OPEN);
                return true;
            }
            return false;
        }
        return false;
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        halfOpenProbeInFlight.set(false);
        state.set(State.CLOSED);
    }

    public void recordFailure() {
        State current = state.get();
        halfOpenProbeInFlight.set(false);
        if (current == State.HALF_OPEN || consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openedAt.set(clock.instant());
            state.set(State.OPEN);
        }
    }

    public State state() {
        return state.get();
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }
}
