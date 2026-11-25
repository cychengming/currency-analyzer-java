package com.currencyanalyzer.ai.springai;

import com.currencyanalyzer.ai.chat.AiMetrics;
import com.currencyanalyzer.ai.provider.AiProviderException;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

@Component
public class SpringAiChatRunner {

    private static final Logger log = LoggerFactory.getLogger(SpringAiChatRunner.class);
    private static final int MAX_TOOL_ROUNDS = 3;
    private static final int MAX_TOOL_CALLS_PER_ROUND = 4;
    private static final String ANSWER_NUDGE = "Write the final answer now. Do not leave it blank.";
    private static final String CONTINUE_NUDGE =
        "The answer was cut off. Continue from the exact stopping point and finish the conclusion. Do not repeat the text already written.";

    private final ObjectProvider<ChatModel> chatModels;
    private final SpringAiMarketTools marketTools;
    private final AiMetrics aiMetrics;
    private final ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();
    private final String model;
    private final int maxOutputTokens;
    private volatile List<ToolCallback> toolCallbacks;

    @Autowired
    public SpringAiChatRunner(
        ObjectProvider<ChatModel> chatModels,
        SpringAiMarketTools marketTools,
        AiMetrics aiMetrics,
        @Value("${app.ai.model:deepseek-flash}") String model,
        @Value("${app.ai.max-output-tokens:8192}") int maxOutputTokens
    ) {
        this.chatModels = chatModels;
        this.marketTools = marketTools;
        this.aiMetrics = aiMetrics == null ? AiMetrics.noop() : aiMetrics;
        this.model = model;
        this.maxOutputTokens = maxOutputTokens;
    }

    public SpringAiChatRunner(
        ObjectProvider<ChatModel> chatModels,
        SpringAiMarketTools marketTools,
        String model,
        int maxOutputTokens
    ) {
        this(chatModels, marketTools, AiMetrics.noop(), model, maxOutputTokens);
    }

    public boolean available() {
        return chatModels.getIfAvailable() != null;
    }

    public void answer(
        String system,
        List<PriorMessage> history,
        StreamListener listener,
        BooleanSupplier cancelled
    ) {
        ChatModel chatModel = chatModels.getIfAvailable();
        if (chatModel == null) {
            throw new AiProviderException("DeepSeek API key is not configured");
        }
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(system == null ? "" : system));
        for (PriorMessage prior : history) {
            messages.add(toMessage(prior));
        }
        boolean usedTools = false;
        TextCount textCount = new TextCount();
        StreamListener counting = textCount.wrap(listener);
        Round lastRound = null;
        for (int round = 0; round < MAX_TOOL_ROUNDS && !cancelled.getAsBoolean(); round++) {
            boolean forceAnswer = usedTools && round == MAX_TOOL_ROUNDS - 1;
            lastRound = streamRound(chatModel, messages, !forceAnswer, true, counting, cancelled);
            if (!lastRound.calledTool()) {
                break;
            }
            usedTools = true;
        }
        if (lastRound != null && !cancelled.getAsBoolean() && needsAnotherPass(textCount, lastRound)) {
            finishIncompleteAnswer(chatModel, messages, lastRound, counting, cancelled, textCount);
        }
    }

    private static boolean needsAnotherPass(TextCount textCount, Round lastRound) {
        return textCount.chars == 0 || lengthLimited(lastRound.finishReason());
    }

    private static boolean lengthLimited(String finishReason) {
        return finishReason != null && finishReason.equalsIgnoreCase("length");
    }

    private void finishIncompleteAnswer(
        ChatModel chatModel,
        List<Message> messages,
        Round previous,
        StreamListener listener,
        BooleanSupplier cancelled,
        TextCount textCount
    ) {
        boolean clipped = textCount.chars > 0;
        if (clipped) {
            log.warn(
                "DeepSeek cut the answer off at the output limit (finish_reason={}, answer_chars={}). Continuing with thinking disabled.",
                shown(previous.finishReason()),
                previous.answer().length()
            );
            if (!previous.calledTool() && !previous.answer().isBlank()) {
                messages.add(DeepSeekAssistantMessage.builder().content(previous.answer()).build());
            }
            messages.add(new UserMessage(CONTINUE_NUDGE));
        } else {
            log.warn(
                "DeepSeek returned no answer text (finish_reason={}, reasoning_chars={}). Retrying with thinking disabled.",
                shown(previous.finishReason()),
                previous.reasoning().length()
            );
            messages.add(new UserMessage(ANSWER_NUDGE));
        }
        Round retry = streamRound(chatModel, messages, false, false, listener, cancelled);
        if (!cancelled.getAsBoolean() && (textCount.chars == 0 || lengthLimited(retry.finishReason()))) {
            log.warn(
                "DeepSeek continuation still did not finish the answer (finish_reason={}, answer_chars={})",
                shown(retry.finishReason()),
                textCount.chars
            );
        }
    }

    private Round streamRound(
        ChatModel chatModel,
        List<Message> messages,
        boolean includeTools,
        boolean thinking,
        StreamListener listener,
        BooleanSupplier cancelled
    ) {
        DeepSeekChatOptions options = options(includeTools, thinking);
        Prompt prompt = new Prompt(messages, options);
        StringBuilder rawText = new StringBuilder();
        StringBuilder visibleText = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        DsmlToolCallSanitizer.StreamFilter streamFilter = new DsmlToolCallSanitizer.StreamFilter();
        ToolCallBuffer toolCalls = new ToolCallBuffer();
        AtomicReference<String> finishReason = new AtomicReference<>();
        AtomicReference<String> responseId = new AtomicReference<>();
        int[] usage = new int[2];
        try {
            chatModel.stream(prompt)
                .takeWhile(chunk -> !cancelled.getAsBoolean())
                .doOnNext(chunk -> {
                    absorb(chunk, rawText, visibleText, reasoning, streamFilter, toolCalls, listener);
                    String reason = finishReason(chunk);
                    if (reason != null) {
                        finishReason.set(reason);
                    }
                    String chunkId = readResponseId(chunk);
                    if (chunkId != null) {
                        responseId.set(chunkId);
                    }
                    int[] chunkUsage = readUsage(chunk);
                    if (chunkUsage != null) {
                        usage[0] = chunkUsage[0];
                        usage[1] = chunkUsage[1];
                    }
                })
                .blockLast();
        } catch (RuntimeException exception) {
            if (cancelled.getAsBoolean()) {
                listener.onUsage(usage[0], usage[1]);
                return new Round(false, reasoning.toString(), visibleText.toString(), finishReason.get());
            }
            listener.onUsage(usage[0], usage[1]);
            if (exception instanceof AiProviderException providerException) {
                throw providerException;
            }
            throw new AiProviderException("Unable to communicate with DeepSeek", exception);
        }
        streamFilter.finish(clean -> emitVisible(clean, visibleText, listener));
        listener.onUsage(usage[0], usage[1]);
        if (responseId.get() != null) {
            listener.onResponseId(responseId.get());
        }
        if (cancelled.getAsBoolean() || !includeTools) {
            return new Round(false, reasoning.toString(), visibleText.toString(), finishReason.get());
        }
        List<AssistantMessage.ToolCall> calls = toolCalls.toList();
        if (calls.isEmpty() && DsmlToolCallSanitizer.containsDsml(rawText.toString())) {
            calls = DsmlToolCallSanitizer.extractToolCalls(rawText.toString(), registeredToolNames());
            if (!calls.isEmpty()) {
                aiMetrics.recordDsmlRecovery(model, calls.size());
                log.warn("Recovered {} tool call(s) from leaked DeepSeek DSML text output.", calls.size());
            }
        }
        if (calls.isEmpty()) {
            return new Round(false, reasoning.toString(), visibleText.toString(), finishReason.get());
        }
        if (calls.size() > MAX_TOOL_CALLS_PER_ROUND) {
            calls = List.copyOf(calls.subList(0, MAX_TOOL_CALLS_PER_ROUND));
        }
        String sanitizedAssistantText = DsmlToolCallSanitizer.stripDsml(rawText.toString());
        DeepSeekAssistantMessage assistant = DeepSeekAssistantMessage.builder()
            .content(sanitizedAssistantText)
            .reasoningContent(reasoning.isEmpty() ? null : reasoning.toString())
            .toolCalls(calls)
            .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(assistant)));
        ToolActivity.bind(new ToolActivity.Listener() {
            @Override
            public void started(String name) {
                listener.onToolStarted(name, "{}");
            }

            @Override
            public void started(String name, String argumentsJson) {
                listener.onToolStarted(name, argumentsJson);
            }

            @Override
            public void finished(String name, String argumentsJson, String result, int durationMs) {
                listener.onToolFinished(name, argumentsJson, result, durationMs);
            }
        });
        try {
            ToolExecutionResult execution = toolCallingManager.executeToolCalls(prompt, response);
            messages.clear();
            messages.addAll(execution.conversationHistory());
        } finally {
            ToolActivity.clear();
        }
        return new Round(true, reasoning.toString(), visibleText.toString(), finishReason.get());
    }

    private static String readResponseId(ChatResponse chunk) {
        if (chunk == null || chunk.getMetadata() == null) {
            return null;
        }
        String id = chunk.getMetadata().getId();
        return id == null || id.isBlank() ? null : id.trim();
    }

    private static int[] readUsage(ChatResponse chunk) {
        if (chunk == null || chunk.getMetadata() == null || chunk.getMetadata().getUsage() == null) {
            return null;
        }
        Usage usage = chunk.getMetadata().getUsage();
        int input = usage.getPromptTokens();
        int output = usage.getCompletionTokens();
        if (input <= 0 && output <= 0) {
            return null;
        }
        return new int[] {input, output};
    }

    private static String finishReason(ChatResponse chunk) {
        if (chunk.getResult() == null || chunk.getResult().getMetadata() == null) {
            return null;
        }
        String reason = chunk.getResult().getMetadata().getFinishReason();
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return reason;
    }

    private static String shown(String finishReason) {
        return finishReason == null || finishReason.isBlank() ? "unknown" : finishReason;
    }

    private void absorb(
        ChatResponse chunk,
        StringBuilder rawText,
        StringBuilder visibleText,
        StringBuilder reasoning,
        DsmlToolCallSanitizer.StreamFilter streamFilter,
        ToolCallBuffer toolCalls,
        StreamListener listener
    ) {
        if (chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return;
        }
        AssistantMessage output = chunk.getResult().getOutput();
        String delta = output.getText();
        if (delta != null && !delta.isEmpty()) {
            rawText.append(delta);
            streamFilter.accept(delta, clean -> emitVisible(clean, visibleText, listener));
        }
        if (output instanceof DeepSeekAssistantMessage deepSeek
            && deepSeek.getReasoningContent() != null
            && !deepSeek.getReasoningContent().isEmpty()) {
            reasoning.append(deepSeek.getReasoningContent());
            listener.onReasoning(deepSeek.getReasoningContent());
        }
        toolCalls.absorb(output.getToolCalls());
    }

    private static void emitVisible(String clean, StringBuilder visibleText, StreamListener listener) {
        if (clean != null && !clean.isEmpty()) {
            visibleText.append(clean);
            listener.onText(clean);
        }
    }

    private DeepSeekChatOptions options(boolean includeTools, boolean thinking) {
        DeepSeekChatOptions.Builder builder = DeepSeekChatOptions.builder()
            .model(model)
            .maxTokens(maxOutputTokens)
            .toolCallbacks(toolCallbacks())
            .toolChoice(includeTools ? "auto" : "none");
        if (thinking) {
            builder.enableThinking();
        } else {
            builder.disableThinking();
        }
        return builder.build();
    }

    private Set<String> registeredToolNames() {
        return toolCallbacks().stream()
            .map(callback -> callback.getToolDefinition().name())
            .collect(Collectors.toUnmodifiableSet());
    }

    private List<ToolCallback> toolCallbacks() {
        List<ToolCallback> existing = toolCallbacks;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (toolCallbacks == null) {
                ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(marketTools)
                    .build()
                    .getToolCallbacks();
                toolCallbacks = List.copyOf(Arrays.asList(callbacks));
            }
            return toolCallbacks;
        }
    }

    private static Message toMessage(PriorMessage prior) {
        String content = prior.content() == null ? "" : prior.content();
        if ("assistant".equals(prior.role())) {
            String cleanContent = DsmlToolCallSanitizer.stripDsml(content);
            // Completed historical turns omit reasoning_content per DeepSeek spec to save prompt tokens.
            return DeepSeekAssistantMessage.builder().content(cleanContent).build();
        }
        return new UserMessage(content);
    }

    public record PriorMessage(String role, String content, String reasoning) {
    }

    private record Round(boolean calledTool, String reasoning, String answer, String finishReason) {
    }

    public interface StreamListener {
        void onText(String delta);

        void onReasoning(String delta);

        void onToolStarted(String name);

        default void onToolStarted(String name, String argumentsJson) {
            onToolStarted(name);
        }

        void onToolFinished(String name, String argumentsJson, String result, int durationMs);

        default void onUsage(int inputTokens, int outputTokens) {
        }

        default void onResponseId(String responseId) {
        }
    }

    private static final class TextCount {
        private int chars;

        private StreamListener wrap(StreamListener listener) {
            return new StreamListener() {
                @Override
                public void onText(String delta) {
                    chars += delta.length();
                    listener.onText(delta);
                }

                @Override
                public void onReasoning(String delta) {
                    listener.onReasoning(delta);
                }

                @Override
                public void onToolStarted(String name) {
                    listener.onToolStarted(name);
                }

                @Override
                public void onToolStarted(String name, String argumentsJson) {
                    listener.onToolStarted(name, argumentsJson);
                }

                @Override
                public void onToolFinished(String name, String argumentsJson, String result, int durationMs) {
                    listener.onToolFinished(name, argumentsJson, result, durationMs);
                }

                @Override
                public void onUsage(int inputTokens, int outputTokens) {
                    listener.onUsage(inputTokens, outputTokens);
                }

                @Override
                public void onResponseId(String responseId) {
                    listener.onResponseId(responseId);
                }
            };
        }
    }

    private static final class ToolCallBuffer {
        private final List<Call> calls = new ArrayList<>();

        private void absorb(List<AssistantMessage.ToolCall> incoming) {
            if (incoming == null || incoming.isEmpty()) {
                return;
            }
            for (AssistantMessage.ToolCall call : incoming) {
                String id = call.id();
                String name = call.name();
                String arguments = call.arguments();
                if (!id.isBlank() && (calls.isEmpty() || !id.equals(calls.getLast().id))) {
                    calls.add(new Call(id, name, new StringBuilder(arguments)));
                } else if (!calls.isEmpty()) {
                    Call last = calls.getLast();
                    if (!name.isBlank()) {
                        last.name = name;
                    }
                    last.arguments.append(arguments);
                } else if (!name.isBlank()) {
                    calls.add(new Call("call-" + calls.size(), name, new StringBuilder(arguments)));
                }
            }
        }

        private List<AssistantMessage.ToolCall> toList() {
            List<AssistantMessage.ToolCall> result = new ArrayList<>();
            for (Call call : calls) {
                String arguments = call.arguments.isEmpty() ? "{}" : call.arguments.toString();
                result.add(new AssistantMessage.ToolCall(call.id, "function", call.name, arguments));
            }
            return result;
        }
    }

    private static final class Call {
        private final String id;
        private String name;
        private final StringBuilder arguments;

        private Call(String id, String name, StringBuilder arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
        }
    }
}
