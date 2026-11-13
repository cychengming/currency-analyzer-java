package com.currencyanalyzer.ai.provider;

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
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.model = model;
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override
    public void stream(
        List<Message> messages,
        Consumer<String> onText,
        BooleanSupplier isCancelled
    ) {
        if (apiKey.isBlank()) {
            throw new AiProviderException("DeepSeek API key is not configured");
        }

        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                "model", model,
                "messages", messages,
                "stream", true,
                "max_tokens", maxOutputTokens
            ));
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
                    emitTextDelta(data, onText);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (!isCancelled.getAsBoolean()) {
                throw new AiProviderException("DeepSeek request was interrupted", exception);
            }
        } catch (AiProviderException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw new AiProviderException("Unable to communicate with DeepSeek", exception);
        }
    }

    private void emitTextDelta(String data, Consumer<String> onText) {
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode content = root.path("choices").path(0).path("delta").path("content");
            if (content.isTextual() && !content.asText().isEmpty()) {
                onText.accept(content.asText());
            }
        } catch (IOException exception) {
            throw new AiProviderException("DeepSeek returned an invalid streaming response", exception);
        }
    }
}
