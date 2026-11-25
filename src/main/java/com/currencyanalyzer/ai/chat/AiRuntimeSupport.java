package com.currencyanalyzer.ai.chat;

import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Bundles cross-cutting runtime dependencies for {@link AiConversationService}
 * (clock, metrics, and bound configuration properties) to keep constructor arity compact.
 */
@Component
public class AiRuntimeSupport {

    private final Clock clock;
    private final AiMetrics metrics;
    private final AiAssistantProperties properties;

    public AiRuntimeSupport(
        Clock clock,
        AiMetrics metrics,
        AiAssistantProperties properties
    ) {
        this.clock = clock;
        this.metrics = metrics;
        this.properties = properties;
    }

    public Clock clock() {
        return clock;
    }

    public AiMetrics metrics() {
        return metrics;
    }

    public AiAssistantProperties properties() {
        return properties;
    }
}
