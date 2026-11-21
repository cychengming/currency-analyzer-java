package com.currencyanalyzer.ai.provider;

import com.currencyanalyzer.ai.provider.AiProvider.Completion;
import com.currencyanalyzer.ai.provider.AiProvider.ToolCall;
import com.currencyanalyzer.ai.provider.AiProvider.ToolDefinition;
import com.currencyanalyzer.ai.provider.AiProvider.Turn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@Component
public class DeepSeekAiProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekAiProvider.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration requestTimeout;
    private final int maxOutputTokens;

    public DeepSeekAiProvider(
        @Value("${app.ai.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
        @Value("${app.ai.deepseek.api-key:}") String apiKey,
        @Value("${app.ai.model:deepseek-flash}") String model,
        @Value("${app.ai.request-timeout-seconds:60}") int requestTimeoutSeconds,
        @Value("${app.ai.max-output-tokens:8192}") int maxOutputTokens
    ) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.baseUrl = withoutTrailingSlashes(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override
    public void stream(
        List<Message> messages,
        Consumer<String> onText,
        Consumer<String> onReasoning,
        BooleanSupplier isCancelled
    ) {
        stream(messages, onText, onReasoning, isCancelled, true);
    }

    @Override
    public void stream(
        List<Message> messages,
        Consumer<String> onText,
        Consumer<String> onReasoning,
        BooleanSupplier isCancelled,
        boolean thinking
    ) {
        if (apiKey.isBlank()) {
            throw new AiProviderException("DeepSeek API key is not configured");
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("stream", true);
            body.put("max_tokens", maxOutputTokens);
            body.put("thinking", Map.of("type", thinking ? "enabled" : "disabled"));
            String payload = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

            HttpResponse<java.util.stream.Stream<String>> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            try (java.util.stream.Stream<String> lines = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new AiProviderException(
                        "DeepSeek request failed with HTTP " + response.statusCode()
                    );
                }

                boolean sawText = false;
                String finishReason = null;
                for (String line : (Iterable<String>) lines::iterator) {
                    if (isCancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring("data:".length()).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    StreamPiece piece = emitTextDelta(data, onText, onReasoning);
                    sawText = sawText || piece.text();
                    if (piece.finishReason() != null) {
                        finishReason = piece.finishReason();
                    }
                }
                if (!sawText && !isCancelled.getAsBoolean()) {
                    log.warn(
                        "DeepSeek returned no answer text (finish_reason={})",
                        finishReason == null ? "unknown" : finishReason
                    );
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (!isCancelled.getAsBoolean()) {
                throw new AiProviderException("DeepSeek request was interrupted", exception);
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new AiProviderException("Unable to communicate with DeepSeek", exception);
        }
    }

    @Override
    public Completion complete(
        List<Turn> messages,
        List<ToolDefinition> tools,
        BooleanSupplier isCancelled
    ) {
        if (isCancelled.getAsBoolean()) {
            return Completion.NONE;
        }
        if (apiKey.isBlank()) {
            throw new AiProviderException("DeepSeek API key is not configured");
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages.stream().map(DeepSeekAiProvider::messageBody).toList());
            body.put("stream", false);
            body.put("max_tokens", maxOutputTokens);
            body.put("thinking", Map.of("type", "enabled"));
            if (!tools.isEmpty()) {
                body.put("tools", tools.stream().map(DeepSeekAiProvider::toolBody).toList());
                body.put("tool_choice", "auto");
            }
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AiProviderException("DeepSeek request failed with HTTP " + response.statusCode());
            }
            return parseCompletion(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (isCancelled.getAsBoolean()) {
                return Completion.NONE;
            }
            throw new AiProviderException("DeepSeek request was interrupted", exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw new AiProviderException("Unable to communicate with DeepSeek", exception);
        }
    }

    private Completion parseCompletion(String body) throws IOException {
        JsonNode choice = objectMapper.readTree(body).path("choices").path(0);
        JsonNode message = choice.path("message");
        String content = message.path("content").isTextual() ? message.path("content").asText() : null;
        String reasoning = message.path("reasoning_content").isTextual()
            ? message.path("reasoning_content").asText()
            : null;
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            String id = call.path("id").asText("");
            JsonNode function = call.path("function");
            String name = function.path("name").asText("");
            JsonNode arguments = function.path("arguments");
            String argumentsJson = arguments.isTextual() ? arguments.asText() : arguments.toString();
            if (!name.isBlank()) {
                toolCalls.add(new ToolCall(id.isBlank() ? "call-" + toolCalls.size() : id, name, argumentsJson));
            }
        }
        if ((content == null || content.isBlank()) && toolCalls.isEmpty()) {
            log.warn(
                "DeepSeek completion returned no answer text (finish_reason={})",
                finishReason(choice)
            );
        }
        return new Completion(content, reasoning, toolCalls);
    }

    private static Map<String, Object> messageBody(Turn message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("role", message.role());
        body.put("content", message.content());
        if (message.toolCallId() != null) {
            body.put("tool_call_id", message.toolCallId());
        }
        if ("assistant".equals(message.role()) && message.reasoning() != null && !message.reasoning().isBlank()) {
            body.put("reasoning_content", message.reasoning());
        }
        if (!message.toolCalls().isEmpty()) {
            body.put("tool_calls", message.toolCalls().stream().map(call -> Map.of(
                "id", call.id(),
                "type", "function",
                "function", Map.of(
                    "name", call.name(),
                    "arguments", call.argumentsJson() == null ? "{}" : call.argumentsJson()
                )
            )).toList());
        }
        return body;
    }

    private static Map<String, Object> toolBody(ToolDefinition tool) {
        return Map.of(
            "type", "function",
            "function", Map.of(
                "name", tool.name(),
                "description", tool.description(),
                "parameters", tool.parameters()
            )
        );
    }

    private StreamPiece emitTextDelta(String data, Consumer<String> onText, Consumer<String> onReasoning) {
        try {
            JsonNode choice = objectMapper.readTree(data).path("choices").path(0);
            JsonNode delta = choice.path("delta");
            JsonNode reasoning = delta.path("reasoning_content");
            if (onReasoning != null && reasoning.isTextual() && !reasoning.asText().isEmpty()) {
                onReasoning.accept(reasoning.asText());
            }
            JsonNode content = delta.path("content");
            boolean text = content.isTextual() && !content.asText().isEmpty();
            if (text) {
                onText.accept(content.asText());
            }
            return new StreamPiece(text, textual(choice.path("finish_reason")));
        } catch (IOException exception) {
            throw new AiProviderException("DeepSeek returned an invalid streaming response", exception);
        }
    }

    private static String finishReason(JsonNode choice) {
        String reason = textual(choice.path("finish_reason"));
        return reason == null ? "unknown" : reason;
    }

    private static String textual(JsonNode node) {
        if (!node.isTextual() || node.asText().isBlank() || "null".equals(node.asText())) {
            return null;
        }
        return node.asText();
    }

    private record StreamPiece(boolean text, String finishReason) {
    }

    private static String withoutTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
