package com.currencyanalyzer.ai.chat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class AiUsageService {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000L);
    private static final BigDecimal DEFAULT_INPUT_COST_PER_MILLION = new BigDecimal("0.27");
    private static final BigDecimal DEFAULT_OUTPUT_COST_PER_MILLION = new BigDecimal("1.10");

    private final AiUsageRepository usageRepository;
    private final Clock clock;
    private final AiMetrics aiMetrics;
    private final int dailyRequestLimit;
    private final int dailyTokenLimit;
    private final BigDecimal dailyUserCostLimitUsd;
    private final BigDecimal dailyGlobalCostLimitUsd;
    private final BigDecimal inputCostPerMillionUsd;
    private final BigDecimal outputCostPerMillionUsd;

    @Autowired
    public AiUsageService(
        AiUsageRepository usageRepository,
        Clock clock,
        AiMetrics aiMetrics,
        AiAssistantProperties properties
    ) {
        this.usageRepository = usageRepository;
        this.clock = clock;
        this.aiMetrics = aiMetrics == null ? AiMetrics.noop() : aiMetrics;
        AiAssistantProperties.Usage usage = properties == null
            ? new AiAssistantProperties.Usage()
            : properties.getUsage();
        this.dailyRequestLimit = usage.getDailyRequestLimit();
        this.dailyTokenLimit = usage.getDailyTokenLimit();
        this.dailyUserCostLimitUsd = usage.getDailyUserCostLimitUsd();
        this.dailyGlobalCostLimitUsd = usage.getDailyGlobalCostLimitUsd();
        this.inputCostPerMillionUsd = usage.getInputCostPerMillionUsd();
        this.outputCostPerMillionUsd = usage.getOutputCostPerMillionUsd();
    }

    public AiUsageService(
        AiUsageRepository usageRepository,
        Clock clock,
        int dailyRequestLimit,
        int dailyTokenLimit
    ) {
        this.usageRepository = usageRepository;
        this.clock = clock;
        this.aiMetrics = AiMetrics.noop();
        this.dailyRequestLimit = Math.max(0, dailyRequestLimit);
        this.dailyTokenLimit = Math.max(0, dailyTokenLimit);
        this.dailyUserCostLimitUsd = BigDecimal.ZERO;
        this.dailyGlobalCostLimitUsd = BigDecimal.ZERO;
        this.inputCostPerMillionUsd = DEFAULT_INPUT_COST_PER_MILLION;
        this.outputCostPerMillionUsd = DEFAULT_OUTPUT_COST_PER_MILLION;
    }

    public void ensureWithinDailyLimit(String username) {
        ensureWithinDailyLimit(username, 0);
    }

    public void ensureWithinDailyLimit(String username, int inFlightUserRequests) {
        Instant since = startOfUtcDay();
        if (dailyGlobalCostLimitUsd.signum() > 0) {
            BigDecimal globalCost = usageRepository.globalCostSince(since);
            if (globalCost.compareTo(dailyGlobalCostLimitUsd) >= 0) {
                aiMetrics.recordRateLimited("global_cost");
                throw new AiUsageLimitException("Global daily AI cost budget reached");
            }
        }
        if (dailyRequestLimit <= 0 && dailyTokenLimit <= 0 && dailyUserCostLimitUsd.signum() <= 0) {
            return;
        }
        AiUsageRepository.UsageTotals totals = usageRepository.totalsSince(username, since);
        int effectiveRequests = totals.requests() + Math.max(0, inFlightUserRequests);
        if (dailyRequestLimit > 0 && effectiveRequests >= dailyRequestLimit) {
            aiMetrics.recordRateLimited("user_requests");
            throw new AiUsageLimitException("Daily AI request limit reached");
        }
        if (dailyTokenLimit > 0 && totals.tokens() >= dailyTokenLimit) {
            aiMetrics.recordRateLimited("user_tokens");
            throw new AiUsageLimitException("Daily AI token limit reached");
        }
        if (dailyUserCostLimitUsd.signum() > 0 && totals.costUsd().compareTo(dailyUserCostLimitUsd) >= 0) {
            aiMetrics.recordRateLimited("user_cost");
            throw new AiUsageLimitException("Daily AI cost limit reached");
        }
    }

    public BigDecimal estimateCostUsd(int inputTokens, int outputTokens) {
        int safeIn = Math.max(0, inputTokens);
        int safeOut = Math.max(0, outputTokens);
        BigDecimal inputCost = BigDecimal.valueOf(safeIn).multiply(inputCostPerMillionUsd);
        BigDecimal outputCost = BigDecimal.valueOf(safeOut).multiply(outputCostPerMillionUsd);
        return inputCost.add(outputCost).divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
    }

    public BigDecimal record(
        String username,
        UUID conversationId,
        String model,
        int inputTokens,
        int outputTokens,
        int requestCount
    ) {
        if (requestCount < 1) {
            return BigDecimal.ZERO;
        }
        String resolvedModel = model == null || model.isBlank() ? "unknown" : model;
        int safeInput = Math.max(0, inputTokens);
        int safeOutput = Math.max(0, outputTokens);
        BigDecimal costUsd = estimateCostUsd(safeInput, safeOutput);
        usageRepository.record(
            username,
            conversationId,
            resolvedModel,
            safeInput,
            safeOutput,
            requestCount,
            costUsd,
            clock.instant()
        );
        aiMetrics.recordTokensAndCost(resolvedModel, safeInput, safeOutput, costUsd);
        return costUsd;
    }

    private Instant startOfUtcDay() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC);
    }
}
