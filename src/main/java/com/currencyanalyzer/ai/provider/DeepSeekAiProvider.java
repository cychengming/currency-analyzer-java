package com.currencyanalyzer.ai.provider;

import com.currencyanalyzer.ai.provider.AiProvider.Completion;
import com.currencyanalyzer.ai.provider.AiProvider.ToolCall;
import com.currencyanalyzer.ai.provider.AiProvider.ToolDefinition;
import com.currencyanalyzer.ai.provider.AiProvider.Turn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        @Value("${app.ai.max-output-tokens:1200}") int maxOutputTokens
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
        if (apiKey.isBlank()) {
            throw new AiProviderException("DeepSeek API key is not configured");
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("stream", true);
            body.put("max_tokens", maxOutputTokens);
            body.put("thinking", Map.of("type", "enabled"));
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

                for (String line : (Iterable<String>) lines::iterator) {
                    if (isCancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring("data:".length()).trim();
                    if ("[DONE]".equals(data)) {
                        return;
                    }
                    emitTextDelta(data, onText, onReasoning);
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
        JsonNode message = objectMapper.readTree(body).path("choices").path(0).path("message");
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

    private void emitTextDelta(String data, Consumer<String> onText, Consumer<String> onReasoning) {
        try {
            JsonNode delta = objectMapper.readTree(data).path("choices").path(0).path("delta");
            JsonNode reasoning = delta.path("reasoning_content");
            if (onReasoning != null && reasoning.isTextual() && !reasoning.asText().isEmpty()) {
                onReasoning.accept(reasoning.asText());
            }
            JsonNode content = delta.path("content");
            if (content.isTextual() && !content.asText().isEmpty()) {
                onText.accept(content.asText());
            }
        } catch (IOException exception) {
            throw new AiProviderException("DeepSeek returned an invalid streaming response", exception);
        }
    }

    private static String withoutTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
