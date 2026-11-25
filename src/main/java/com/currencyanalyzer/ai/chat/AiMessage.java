package com.currencyanalyzer.ai.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AiMessage(
    UUID id,
    String role,
    String content,
    Instant createdAt,
    String reasoning,
    @JsonProperty("tool_calls") List<AiToolCall> toolCalls
) {
    public AiMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public AiMessage(UUID id, String role, String content, Instant createdAt, String reasoning) {
        this(id, role, content, createdAt, reasoning, List.of());
    }
}
