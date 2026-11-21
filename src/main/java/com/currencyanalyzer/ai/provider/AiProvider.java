package com.currencyanalyzer.ai.provider;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public interface AiProvider {

    void stream(
        List<Message> messages,
        Consumer<String> onText,
        BooleanSupplier isCancelled
    );

    default Completion complete(
        List<Turn> messages,
        List<ToolDefinition> tools,
        BooleanSupplier isCancelled
    ) {
        return Completion.NONE;
    }

    record Message(String role, String content) {
    }

    record ToolCall(String id, String name, String argumentsJson) {
    }

    record ToolDefinition(String name, String description, Map<String, Object> parameters) {
    }

    record Turn(String role, String content, String toolCallId, List<ToolCall> toolCalls) {
        public Turn {
            content = content == null ? "" : content;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public static Turn text(String role, String content) {
            return new Turn(role, content, null, List.of());
        }

        public static Turn toolResult(String toolCallId, String content) {
            return new Turn("tool", content, toolCallId, List.of());
        }

        public static Turn toolRequest(String content, List<ToolCall> toolCalls) {
            return new Turn("assistant", content, null, toolCalls);
        }
    }

    record Completion(String content, List<ToolCall> toolCalls) {
        public static final Completion NONE = new Completion(null, List.of());

        public Completion {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }
    }
}
