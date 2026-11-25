package com.currencyanalyzer.ai.springai;

import com.currencyanalyzer.ai.chat.AiMetrics;
import com.currencyanalyzer.ai.provider.AiProviderException;
import com.currencyanalyzer.ai.tools.AssistantTools;
import com.currencyanalyzer.marketdata.MarketDataService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void retriesWhenReasoningUsesTheWholeAnswerBudget() {
        ReasoningOnlyThenAnswer model = new ReasoningOnlyThenAnswer();
        SpringAiChatRunner runner = runner(model);
        List<String> events = new ArrayList<>();

        runner.answer("Be brief.", List.of(new SpringAiChatRunner.PriorMessage("user", "Explain the Kelly formula.", null)),
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
                }

                @Override
                public void onToolFinished(String name, String argumentsJson, String result, int durationMs) {
                }
            },
            () -> false
        );

        assertEquals(List.of(
            DeepSeekApi.ChatCompletionRequest.Thinking.Type.ENABLED,
            DeepSeekApi.ChatCompletionRequest.Thinking.Type.DISABLED
        ), model.thinking);
        assertEquals("Write the final answer now. Do not leave it blank.", model.nudge);
        assertEquals(List.of("reasoning:The token budget was spent here."), events.stream()
            .filter(event -> event.startsWith("reasoning:")).toList());
        assertEquals(List.of("text:Bet a fraction of the bankroll."), events.stream()
            .filter(event -> event.startsWith("text:")).toList());
    }

    @Test
    void continuesAnAnswerCutOffByTheOutputLimit() {
        CutOffThenConclusion model = new CutOffThenConclusion();
        SpringAiChatRunner runner = new SpringAiChatRunner(provider(model), marketTools(), "deepseek-flash", 8192);
        List<String> events = new ArrayList<>();

        runner.answer("Be brief.", List.of(new SpringAiChatRunner.PriorMessage("user", "Explain position sizing.", null)),
            new SpringAiChatRunner.StreamListener() {
                @Override
                public void onText(String delta) {
                    events.add("text:" + delta);
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
            },
            () -> false
        );

        assertEquals(List.of(8192, 8192), model.maxTokens);
        assertEquals(List.of(
            DeepSeekApi.ChatCompletionRequest.Thinking.Type.ENABLED,
            DeepSeekApi.ChatCompletionRequest.Thinking.Type.DISABLED
        ), model.thinking);
        assertEquals("Section a is done. Section b", model.partial);
        assertEquals(
            "The answer was cut off. Continue from the exact stopping point and finish the conclusion. Do not repeat the text already written.",
            model.nudge
        );
        assertEquals(
            List.of("text:Section a is done. Section b", "text: concludes here."),
            events.stream().filter(event -> event.startsWith("text:")).toList()
        );
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

    @Test
    void recoversAndExecutesLeakedDsmlToolCallsWithoutStreamingMarkup() {
        LeakedDsmlChatModel model = new LeakedDsmlChatModel();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SpringAiChatRunner runner = new SpringAiChatRunner(
            provider(model),
            marketTools(),
            new AiMetrics(meterRegistry),
            "deepseek-flash",
            200
        );
        List<String> events = new ArrayList<>();
        StringBuilder visible = new StringBuilder();
        AtomicReference<String> capturedResponseId = new AtomicReference<>();

        runner.answer(
            "Be brief.",
            List.of(
                new SpringAiChatRunner.PriorMessage(
                    "assistant",
                    "Earlier reply.\n<｜｜DSML｜｜ calls>\n<｜｜DSML｜｜ invoke name=\"get_live_rates\"></｜｜DSML｜｜ invoke>\n</｜｜DSML｜｜ calls>",
                    "Old historical reasoning that should be omitted"
                ),
                new SpringAiChatRunner.PriorMessage("user", "Check rates and settings now.", null)
            ),
            new SpringAiChatRunner.StreamListener() {
                @Override
                public void onText(String delta) {
                    visible.append(delta);
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

                @Override
                public void onResponseId(String responseId) {
                    capturedResponseId.set(responseId);
                }
            },
            () -> false
        );

        assertTrue(events.contains("started:get_live_rates"));
        assertTrue(events.contains("finished:get_live_rates"));
        assertTrue(events.contains("started:get_settings"));
        assertTrue(events.contains("finished:get_settings"));
        assertEquals("Live rates and settings loaded.", visible.toString());
        assertEquals("Earlier reply.", model.firstRoundPriorAssistantText);
        assertNull(model.firstRoundPriorAssistantReasoning);
        assertEquals("auto", model.firstRoundToolChoice);
        assertEquals("chatcmpl-dsml-2", capturedResponseId.get());
        assertEquals(2.0, meterRegistry.get("currency_analyzer_ai_dsml_recoveries_total").counter().count());
    }

    @Test
    void setsToolChoiceNoneWhenRetryingIncompleteAnswer() {
        ReasoningOnlyThenAnswer model = new ReasoningOnlyThenAnswer();
        SpringAiChatRunner runner = runner(model);

        runner.answer(
            "Be brief.",
            List.of(new SpringAiChatRunner.PriorMessage("user", "Explain risk.", null)),
            quiet(),
            () -> false
        );

        assertEquals(List.of("auto", "none"), model.toolChoices);
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

    @Test
    void reportsProviderTokenUsageFromTheStream() {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                DeepSeekAssistantMessage message = DeepSeekAssistantMessage.builder().content("Noted.").build();
                ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                    .usage(new DefaultUsage(15, 3))
                    .build();
                return Flux.just(new ChatResponse(List.of(new Generation(message)), metadata));
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return DeepSeekChatOptions.builder().model("deepseek-flash").build();
            }
        };
        SpringAiChatRunner runner = new SpringAiChatRunner(provider(model), marketTools(), "deepseek-flash", 200);
        AtomicInteger input = new AtomicInteger();
        AtomicInteger output = new AtomicInteger();
        runner.answer("Be brief.", List.of(new SpringAiChatRunner.PriorMessage("user", "Hello", null)),
            new SpringAiChatRunner.StreamListener() {
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

                @Override
                public void onUsage(int inputTokens, int outputTokens) {
                    input.set(inputTokens);
                    output.set(outputTokens);
                }
            },
            () -> false
        );
        assertEquals(15, input.get());
        assertEquals(3, output.get());
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

    private static final class ReasoningOnlyThenAnswer implements ChatModel {
        private final List<DeepSeekApi.ChatCompletionRequest.Thinking.Type> thinking = new ArrayList<>();
        private final List<Object> toolChoices = new ArrayList<>();
        private String nudge;
        private int calls;

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            calls++;
            DeepSeekChatOptions options = (DeepSeekChatOptions) prompt.getOptions();
            thinking.add(options.getThinking().type());
            toolChoices.add(options.getToolChoice());
            if (calls == 1) {
                DeepSeekAssistantMessage message = DeepSeekAssistantMessage.builder()
                    .content("")
                    .reasoningContent("The token budget was spent here.")
                    .build();
                Generation generation = new Generation(
                    message,
                    ChatGenerationMetadata.builder().finishReason("LENGTH").build()
                );
                return Flux.just(new ChatResponse(List.of(generation)));
            }
            List<Message> instructions = prompt.getInstructions();
            nudge = instructions.getLast().getText();
            DeepSeekAssistantMessage message = DeepSeekAssistantMessage.builder()
                .content("Bet a fraction of the bankroll.")
                .build();
            Generation generation = new Generation(
                message,
                ChatGenerationMetadata.builder().finishReason("STOP").build()
            );
            return Flux.just(new ChatResponse(List.of(generation)));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return DeepSeekChatOptions.builder().model("deepseek-flash").build();
        }
    }

    private static final class CutOffThenConclusion implements ChatModel {
        private final List<DeepSeekApi.ChatCompletionRequest.Thinking.Type> thinking = new ArrayList<>();
        private final List<Integer> maxTokens = new ArrayList<>();
        private String partial;
        private String nudge;
        private int calls;

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            calls++;
            DeepSeekChatOptions options = (DeepSeekChatOptions) prompt.getOptions();
            thinking.add(options.getThinking().type());
            maxTokens.add(options.getMaxTokens());
            if (calls == 1) {
                DeepSeekAssistantMessage message = DeepSeekAssistantMessage.builder()
                    .content("Section a is done. Section b")
                    .reasoningContent("The sections still need a conclusion.")
                    .build();
                return Flux.just(new ChatResponse(List.of(new Generation(
                    message,
                    ChatGenerationMetadata.builder().finishReason("LENGTH").build()
                ))));
            }
            List<Message> instructions = prompt.getInstructions();
            partial = instructions.get(instructions.size() - 2).getText();
            nudge = instructions.getLast().getText();
            DeepSeekAssistantMessage message = DeepSeekAssistantMessage.builder()
                .content(" concludes here.")
                .build();
            return Flux.just(new ChatResponse(List.of(new Generation(
                message,
                ChatGenerationMetadata.builder().finishReason("STOP").build()
            ))));
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

    private static final class LeakedDsmlChatModel implements ChatModel {
        private int calls;
        private String firstRoundPriorAssistantText;
        private String firstRoundPriorAssistantReasoning;
        private Object firstRoundToolChoice;

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            calls++;
            if (calls == 1) {
                Message priorAssistant = prompt.getInstructions().get(1);
                firstRoundPriorAssistantText = priorAssistant.getText();
                if (priorAssistant instanceof DeepSeekAssistantMessage deepSeek) {
                    firstRoundPriorAssistantReasoning = deepSeek.getReasoningContent();
                }
                firstRoundToolChoice = ((DeepSeekChatOptions) prompt.getOptions()).getToolChoice();
                return Flux.just(
                    textChunk("<｜", null),
                    textChunk("｜DSML｜｜ calls>\n<｜｜DSML｜｜ invoke name=\"get_live_rates\">\n</｜｜DSML｜｜ invoke>\n", null),
                    textChunk("<｜｜DSML｜｜ invoke name=\"get_settings\"></｜｜DSML｜｜ invoke>\n</｜｜DSML｜｜ calls>", "chatcmpl-dsml-1")
                );
            }
            return Flux.just(textChunk("Live rates and settings loaded.", "chatcmpl-dsml-2"));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return DeepSeekChatOptions.builder().model("deepseek-flash").build();
        }

        private static ChatResponse textChunk(String text, String id) {
            DeepSeekAssistantMessage message = DeepSeekAssistantMessage.builder().content(text).build();
            if (id == null) {
                return new ChatResponse(List.of(new Generation(message)));
            }
            ChatResponseMetadata metadata = ChatResponseMetadata.builder().id(id).build();
            return new ChatResponse(List.of(new Generation(message)), metadata);
        }
    }
}
