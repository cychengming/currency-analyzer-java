package com.currencyanalyzer.ai.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class AiConversationRepository {

    private static final int MAX_CONTEXT_MESSAGES = 20;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    public AiConversationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AiConversation create(String username, String provider, String model, Instant now) {
        UUID id = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        jdbcTemplate.update("""
                INSERT INTO ai_conversations
                    (id, username, provider, model, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
            id, username, provider, model, createdAt, createdAt
        );
        return new AiConversation(id, null, now, now);
    }

    public List<AiConversation> findByUsername(String username, int limit) {
        return jdbcTemplate.query("""
                SELECT id, title, created_at, updated_at
                FROM ai_conversations
                WHERE username = ? AND deleted_at IS NULL
                ORDER BY updated_at DESC, id
                LIMIT ?
                """,
            (resultSet, rowNumber) -> mapConversation(resultSet),
            username, limit
        );
    }

    public boolean deleteByIdAndUsername(UUID id, String username) {
        int deleted = jdbcTemplate.update("""
                DELETE FROM ai_conversations
                WHERE id = ? AND username = ?
                """,
            id, username
        );
        return deleted > 0;
    }

    public boolean existsByIdAndUsername(UUID id, String username) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ai_conversations
                WHERE id = ? AND username = ? AND deleted_at IS NULL
                """,
            Integer.class, id, username
        );
        return count != null && count > 0;
    }

    public AiMessage addUserMessage(UUID conversationId, String content, Instant now) {
        UUID messageId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        jdbcTemplate.update("""
                INSERT INTO ai_messages (id, conversation_id, role, content, created_at)
                VALUES (?, ?, 'user', ?, ?)
                """,
            messageId, conversationId, content, createdAt
        );
        jdbcTemplate.update("""
                UPDATE ai_conversations
                SET title = COALESCE(title, ?), updated_at = ?
                WHERE id = ?
                """,
            titleFrom(content), createdAt, conversationId
        );
        return new AiMessage(messageId, "user", content, now, null);
    }

    public AiMessage addAssistantMessage(UUID conversationId, String content, String reasoning, Instant now) {
        return addAssistantMessage(conversationId, content, reasoning, null, null, now);
    }

    public AiMessage addAssistantMessage(
        UUID conversationId,
        String content,
        String reasoning,
        String promptVersion,
        String providerResponseId,
        Instant now
    ) {
        UUID messageId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        jdbcTemplate.update("""
                INSERT INTO ai_messages (
                    id, conversation_id, role, content, reasoning_content,
                    prompt_version, provider_response_id, created_at
                )
                VALUES (?, ?, 'assistant', ?, ?, ?, ?, ?)
                """,
            messageId,
            conversationId,
            content,
            blankToNull(reasoning),
            truncateNullable(promptVersion, 32),
            truncateNullable(providerResponseId, 128),
            createdAt
        );
        jdbcTemplate.update(
            "UPDATE ai_conversations SET updated_at = ? WHERE id = ?",
            createdAt, conversationId
        );
        return new AiMessage(messageId, "assistant", content, now, blankToNull(reasoning));
    }

    public void addToolInvocation(
        UUID messageId,
        String toolName,
        String argumentsJson,
        String resultSummaryJson,
        String status,
        int durationMs,
        Instant now
    ) {
        jdbcTemplate.update("""
                INSERT INTO ai_tool_invocations (
                    id, message_id, tool_name, arguments_json, result_summary,
                    status, duration_ms, created_at
                )
                VALUES (?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), ?, ?, ?)
                """,
            UUID.randomUUID(),
            messageId,
            toolName,
            argumentsJson,
            resultSummaryJson,
            status,
            durationMs,
            OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        );
    }

    public List<AiProviderMessage> findRecentMessages(UUID conversationId) {
        // Historical reasoning_content is excluded on replay to save 30-60% prompt tokens;
        // full reasoning_content remains stored in ai_messages for UI rendering via findConversationMessages.
        return jdbcTemplate.query("""
                SELECT role, content
                FROM (
                    SELECT role, content, created_at, id
                    FROM ai_messages
                    WHERE conversation_id = ?
                    ORDER BY created_at DESC, id DESC
                    LIMIT ?
                ) recent
                ORDER BY created_at, id
                """,
            (resultSet, rowNumber) -> new AiProviderMessage(
                resultSet.getString("role"),
                resultSet.getString("content"),
                null
            ),
            conversationId, MAX_CONTEXT_MESSAGES
        );
    }

    public List<AiMessage> findConversationMessages(UUID conversationId) {
        List<AiMessage> rawMessages = jdbcTemplate.query("""
                SELECT id, role, content, reasoning_content, created_at
                FROM ai_messages
                WHERE conversation_id = ?
                ORDER BY created_at, id
                """,
            (resultSet, rowNumber) -> new AiMessage(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("role"),
                resultSet.getString("content"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getString("reasoning_content")
            ),
            conversationId
        );
        if (rawMessages.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<AiToolCall>> toolCallsByMessage = loadToolCallsByConversation(conversationId);
        if (toolCallsByMessage.isEmpty()) {
            return rawMessages;
        }
        List<AiMessage> enriched = new ArrayList<>(rawMessages.size());
        for (AiMessage message : rawMessages) {
            List<AiToolCall> toolCalls = toolCallsByMessage.getOrDefault(message.id(), List.of());
            enriched.add(new AiMessage(
                message.id(),
                message.role(),
                message.content(),
                message.createdAt(),
                message.reasoning(),
                toolCalls
            ));
        }
        return List.copyOf(enriched);
    }

    private Map<UUID, List<AiToolCall>> loadToolCallsByConversation(UUID conversationId) {
        Map<UUID, List<AiToolCall>> byMessage = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT t.message_id,
                       t.tool_name,
                       CAST(t.arguments_json AS VARCHAR) AS arguments_json,
                       CAST(t.result_summary AS VARCHAR) AS result_summary,
                       t.status,
                       t.duration_ms
                FROM ai_tool_invocations t
                JOIN ai_messages m ON m.id = t.message_id
                WHERE m.conversation_id = ?
                ORDER BY t.created_at, t.id
                """,
            resultSet -> {
                UUID messageId = resultSet.getObject("message_id", UUID.class);
                if (messageId == null) {
                    return;
                }
                AiToolCall call = new AiToolCall(
                    resultSet.getString("tool_name"),
                    normalizeJsonColumn(resultSet.getString("arguments_json")),
                    normalizeJsonColumn(resultSet.getString("result_summary")),
                    resultSet.getString("status"),
                    resultSet.getObject("duration_ms", Integer.class)
                );
                byMessage.computeIfAbsent(messageId, ignored -> new ArrayList<>()).add(call);
            },
            conversationId
        );
        return byMessage;
    }

    static String normalizeJsonColumn(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            try {
                JsonNode node = OBJECT_MAPPER.readTree(trimmed);
                if (node != null && node.isTextual()) {
                    return node.asText();
                }
            } catch (JsonProcessingException ignored) {
                return trimmed;
            }
        }
        return trimmed;
    }

    private static String titleFrom(String content) {
        String title = content.replaceAll("\\s+", " ").trim();
        return title.length() <= 255 ? title : title.substring(0, 252) + "...";
    }

    private static AiConversation mapConversation(ResultSet resultSet) throws SQLException {
        return new AiConversation(
            resultSet.getObject("id", UUID.class),
            resultSet.getString("title"),
            resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
            resultSet.getObject("updated_at", OffsetDateTime.class).toInstant()
        );
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String truncateNullable(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    public record AiProviderMessage(String role, String content, String reasoning) {
    }
}
