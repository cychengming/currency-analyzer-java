package com.currencyanalyzer.ai.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AiToolCall(
    String tool,
    String arguments,
    String summary,
    String status,
    @JsonProperty("duration_ms") Integer durationMs
) {
}
