package com.currencyanalyzer.ai.provider;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekAiProviderTests {

    @Test
    void streamsOpenAiCompatibleTextDeltasFromDeepSeek() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                data: {"choices":[{"delta":{"content":"Hello "}}]}

                data: {"choices":[{"delta":{"content":"there."}}]}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            DeepSeekAiProvider provider = new DeepSeekAiProvider(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-secret",
                "deepseek-flash",
                5,
                500
            );
            List<String> deltas = new ArrayList<>();

            provider.stream(
                List.of(new AiProvider.Message("user", "Say hello")),
                deltas::add,
                () -> false
            );

            assertEquals("Bearer test-secret", authorization.get());
            assertTrue(requestBody.get().contains("\"model\":\"deepseek-flash\""));
            assertTrue(requestBody.get().contains("\"stream\":true"));
            assertEquals(List.of("Hello ", "there."), deltas);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failsClearlyWhenApiKeyIsMissing() {
        DeepSeekAiProvider provider = new DeepSeekAiProvider(
            "https://api.deepseek.com",
            "",
            "deepseek-flash",
            5,
            500
        );

        AiProviderException exception = assertThrows(
            AiProviderException.class,
            () -> provider.stream(List.of(), ignored -> { }, () -> false)
        );

        assertEquals("DeepSeek API key is not configured", exception.getMessage());
    }
}
