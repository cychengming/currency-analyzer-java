package com.currencyanalyzer.ai.chat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Repository
public class AiUsageRepository {

    private final JdbcTemplate jdbcTemplate;

    public AiUsageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UsageTotals totalsSince(String username, Instant since) {
        return jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(request_count), 0),
                       COALESCE(SUM(COALESCE(input_tokens, 0) + COALESCE(output_tokens, 0)), 0),
                       COALESCE(SUM(COALESCE(estimated_cost_usd, 0)), 0)
                FROM ai_usage
                WHERE username = ? AND created_at >= ?
                """,
            (resultSet, rowNumber) -> new UsageTotals(
                resultSet.getInt(1),
                resultSet.getInt(2),
                nonNegative(resultSet.getBigDecimal(3))
            ),
            username,
            OffsetDateTime.ofInstant(since, ZoneOffset.UTC)
        );
    }

    public BigDecimal globalCostSince(Instant since) {
        BigDecimal total = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(COALESCE(estimated_cost_usd, 0)), 0)
                FROM ai_usage
                WHERE created_at >= ?
                """,
            BigDecimal.class,
            OffsetDateTime.ofInstant(since, ZoneOffset.UTC)
        );
        return nonNegative(total);
    }

    public void record(
        String username,
        UUID conversationId,
        String model,
        int inputTokens,
        int outputTokens,
        int requestCount,
        Instant now
    ) {
        record(username, conversationId, model, inputTokens, outputTokens, requestCount, BigDecimal.ZERO, now);
    }

    public void record(
        String username,
        UUID conversationId,
        String model,
        int inputTokens,
        int outputTokens,
        int requestCount,
        BigDecimal estimatedCostUsd,
        Instant now
    ) {
        jdbcTemplate.update("""
                INSERT INTO ai_usage (
                    id, username, conversation_id, model,
                    input_tokens, output_tokens, estimated_cost_usd, request_count, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            UUID.randomUUID(),
            username,
            conversationId,
            model,
            inputTokens,
            outputTokens,
            nonNegative(estimatedCostUsd),
            requestCount,
            OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        );
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    public record UsageTotals(int requests, int tokens, BigDecimal costUsd) {
        public UsageTotals(int requests, int tokens) {
            this(requests, tokens, BigDecimal.ZERO);
        }

        public UsageTotals {
            costUsd = nonNegative(costUsd);
        }
    }
}
