package com.currencyanalyzer.ai.springai;

import com.currencyanalyzer.ai.provider.AiProviderException;
import com.currencyanalyzer.ai.tools.AssistantTools;
import com.currencyanalyzer.marketdata.MarketDataService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringAiChatRunnerTests {

    @Test
    void streamsReasoningAndRunsASpringAiTool() {
        MarketDataService marketDataService = mock(MarketDataService.class);
        when(marketDataService.fetchLiveRates()).thenReturn(Map.of());
        AssistantTools tools = new AssistantTools(
            marketDataService,
            mock(JdbcTemplate.class),
            Clock.systemUTC(),
            60,
            null
        );
        ScriptedChatModel model = new ScriptedChatModel();
        SpringAiChatRunner runner = new SpringAiChatRunner(provider(model), new SpringAiMarketTools(tools), "deepseek-flash", 200);
        List<String> events = new ArrayList<>();

        runner.answer("Be brief.", List.of(new SpringAiChatRunner.PriorMessage("user", "What is EUR/USD?", null)),
            new SpringAiChatRunner.StreamListener() {
                @Override
                public void onText(String delta) {
                    events.add("text:" + delta);
                }

                @Override
                public void onReasoning(String delta) {
                    events.add("reasoning:" + delta);
                }

                @Override
                public void onToolStarted(String name) {
                    events.add("started:" + name);
                }

                @Override
                public void onToolFinished(String name, String argumentsJson, String result, int durationMs) {
                    events.add("finished:" + name);
                }
            },
            () -> false
        );

        assertTrue(events.contains("reasoning:Need the live quote."));
        assertTrue(events.contains("started:get_live_rates"));
        assertTrue(events.contains("finished:get_live_rates"));
        assertTrue(events.contains("text:From the live feed."));
        assertTrue(events.contains("reasoning:The quote is in."));
    }

    @Test
    void reportsAMissingKeyAndKeepsACancelledFailureQuiet() {
        SpringAiChatRunner missing = new SpringAiChatRunner(provider(null), marketTools(), "deepseek-flash", 200);
        AiProviderException missingKey = assertThrows(AiProviderException.class, () ->
            missing.answer("Be brief.", List.of(), quiet(), () -> false)
        );
        assertEquals("DeepSeek API key is not configured", missingKey.getMessage());

        SpringAiChatRunner cancelled = runner(new FailingChatModel(new IllegalStateException("stopped")));
        int[] cancellationChecks = {0};
        cancelled.answer(
            "Be brief.",
            List.of(new SpringAiChatRunner.PriorMessage("user", "Price?", null)),
            quiet(),
            () -> cancellationChecks[0]++ > 0
        );

        SpringAiChatRunner providerFailure = runner(new FailingChatModel(new AiProviderException("rejected")));
        AiProviderException rejected = assertThrows(AiProviderException.class, () ->
            providerFailure.answer("Be brief.", List.of(), quiet(), () -> false)
        );
        assertEquals("rejected", rejected.getMessage());

        SpringAiChatRunner transportFailure = runner(new FailingChatModel(new IllegalStateException("down")));
        AiProviderException wrapped = assertThrows(AiProviderException.class, () ->
            transportFailure.answer("Be brief.", List.of(), quiet(), () -> false)
        );
        assertEquals("Unable to communicate with DeepSeek", wrapped.getMessage());
    }

    @Test
    void assemblesAToolCallThatArrivesInPieces() {
        SpringAiChatRunner runner = runner(new ChunkedToolChatModel());
        List<String> events = new ArrayList<>();
        runner.answer(
            "Be brief.",
            List.of(
                new SpringAiChatRunner.PriorMessage("assistant", "Earlier.", "Because of the trend."),
                new SpringAiChatRunner.PriorMessage("user", "What is EUR/USD?", null)
            ),
            new SpringAiChatRunner.StreamListener() {
                @Override
                public void onText(String delta) {
                    events.add(delta);
                }

                @Override
                public void onReasoning(String delta) {
                }

                @Override
                public void onToolStarted(String name) {
                    events.add("started:" + name);
                }

                @Override
                public void onToolFinished(String name, String argumentsJson, String result, int durationMs) {
                    events.add("finished:" + name);
                }
            },
            () -> false
        );

        assertTrue(events.contains("started:get_live_rates"));
        assertTrue(events.contains("started:get_settings"));
        assertTrue(events.contains("Quoted."));
    }

    private static SpringAiChatRunner runner(ChatModel model) {
        return new SpringAiChatRunner(provider(model), marketTools(), "deepseek-flash", 200);
    }

    private static SpringAiMarketTools marketTools() {
        MarketDataService marketDataService = mock(MarketDataService.class);
        when(marketDataService.fetchLiveRates()).thenReturn(Map.of());
        AssistantTools tools = new AssistantTools(
            marketDataService,
            mock(JdbcTemplate.class),
            Clock.systemUTC(),
            60,
            null
        );
        return new SpringAiMarketTools(tools);
    }

    private static SpringAiChatRunner.StreamListener quiet() {
        return new SpringAiChatRunner.StreamListener() {
            @Override
            public void onText(String delta) {
            }

            @Override
            public void onReasoning(String delta) {
            }

            @Override
            public void onToolStarted(String name) {
            }

            @Override
            public void onToolFinished(String name, String argumentsJson, String result, int durationMs) {
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ChatModel> provider(ChatModel model) {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        return provider;
    }

    private static final class ScriptedChatModel implements ChatModel {
        private int calls;

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            calls++;
            if (calls == 1) {
                DeepSeekAssistantMessage message = DeepSeekAssistantMessage.builder()
                    .content("")
                    .reasoningContent("Need the live quote.")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "get_live_rates", "{}")))
                    .build();
                return Flux.just(new ChatResponse(List.of(new Generation(message))));
            }
            DeepSeekAssistantMessage message = DeepSeekAssistantMessage.builder()
                .content("From the live feed.")
                .reasoningContent("The quote is in.")
                .build();
            return Flux.just(new ChatResponse(List.of(new Generation(message))));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return DeepSeekChatOptions.builder().model("deepseek-flash").build();
        }
    }

    private static final class FailingChatModel implements ChatModel {
        private final RuntimeException failure;

        private FailingChatModel(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.error(failure);
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return DeepSeekChatOptions.builder().model("deepseek-flash").build();
        }
    }

    private static final class ChunkedToolChatModel implements ChatModel {
        private int calls;

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            calls++;
            if (calls == 1) {
                return Flux.just(
                    chunk("", "get_live_rates", "{}"),
                    chunk("call-9", "", "{"),
                    chunk("call-9", "get_settings", "}")
                );
            }
            if (calls == 2) {
                return Flux.just(chunkAnswer(""));
            }
            return Flux.just(chunkAnswer("Quoted."));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return DeepSeekChatOptions.builder().model("deepseek-flash").build();
        }

        private static ChatResponse chunk(String id, String name, String arguments) {
            DeepSeekAssistantMessage message = DeepSeekAssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, arguments)))
                .build();
            return new ChatResponse(List.of(new Generation(message)));
        }

        private static ChatResponse chunkAnswer(String text) {
            DeepSeekAssistantMessage message = DeepSeekAssistantMessage.builder().content(text).build();
            return new ChatResponse(List.of(new Generation(message)));
        }
    }
}
