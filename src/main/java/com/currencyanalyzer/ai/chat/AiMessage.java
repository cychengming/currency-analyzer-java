package com.currencyanalyzer.ai.chat;

import java.time.Instant;
import java.util.UUID;

public record AiMessage(UUID id, String role, String content, Instant createdAt) {
}
