package com.currencyanalyzer.ai.chat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
        UsageTotals totals = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(request_count), 0),
                       COALESCE(SUM(COALESCE(input_tokens, 0) + COALESCE(output_tokens, 0)), 0)
                FROM ai_usage
                WHERE username = ? AND created_at >= ?
                """,
            (resultSet, rowNumber) -> new UsageTotals(resultSet.getInt(1), resultSet.getInt(2)),
            username,
            OffsetDateTime.ofInstant(since, ZoneOffset.UTC)
        );
        return totals == null ? new UsageTotals(0, 0) : totals;
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
        jdbcTemplate.update("""
                INSERT INTO ai_usage (
                    id, username, conversation_id, model,
                    input_tokens, output_tokens, request_count, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            UUID.randomUUID(),
            username,
            conversationId,
            model,
            inputTokens,
            outputTokens,
            requestCount,
            OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        );
    }

    public record UsageTotals(int requests, int tokens) {
    }
}
