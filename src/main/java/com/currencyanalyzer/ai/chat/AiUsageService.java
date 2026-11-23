package com.currencyanalyzer.ai.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class AiUsageService {

    private final AiUsageRepository usageRepository;
    private final Clock clock;
    private final int dailyRequestLimit;
    private final int dailyTokenLimit;

    public AiUsageService(
        AiUsageRepository usageRepository,
        Clock clock,
        @Value("${app.ai.usage.daily-request-limit:0}") int dailyRequestLimit,
        @Value("${app.ai.usage.daily-token-limit:0}") int dailyTokenLimit
    ) {
        this.usageRepository = usageRepository;
        this.clock = clock;
        this.dailyRequestLimit = dailyRequestLimit;
        this.dailyTokenLimit = dailyTokenLimit;
    }

    public void ensureWithinDailyLimit(String username) {
        if (dailyRequestLimit <= 0 && dailyTokenLimit <= 0) {
            return;
        }
        AiUsageRepository.UsageTotals totals = usageRepository.totalsSince(username, startOfUtcDay());
        if (dailyRequestLimit > 0 && totals.requests() >= dailyRequestLimit) {
            throw new AiUsageLimitException("Daily AI request limit reached");
        }
        if (dailyTokenLimit > 0 && totals.tokens() >= dailyTokenLimit) {
            throw new AiUsageLimitException("Daily AI token limit reached");
        }
    }

    public void record(
        String username,
        UUID conversationId,
        String model,
        int inputTokens,
        int outputTokens,
        int requestCount
    ) {
        if (requestCount < 1) {
            return;
        }
        usageRepository.record(
            username,
            conversationId,
            model == null || model.isBlank() ? "unknown" : model,
            Math.max(0, inputTokens),
            Math.max(0, outputTokens),
            requestCount,
            clock.instant()
        );
    }

    private Instant startOfUtcDay() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC);
    }
}
