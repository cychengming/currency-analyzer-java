package com.currencyanalyzer.ai.springai;

import com.currencyanalyzer.ai.provider.AiProviderException;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

@Component
public class SpringAiChatRunner {

    private static final int MAX_TOOL_ROUNDS = 3;
    private static final int MAX_TOOL_CALLS_PER_ROUND = 4;

    private final ObjectProvider<ChatModel> chatModels;
    private final SpringAiMarketTools marketTools;
    private final ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();
    private final String model;
    private final int maxOutputTokens;
    private volatile List<ToolCallback> toolCallbacks;

    public SpringAiChatRunner(
        ObjectProvider<ChatModel> chatModels,
        SpringAiMarketTools marketTools,
        @Value("${app.ai.model:deepseek-flash}") String model,
        @Value("${app.ai.max-output-tokens:1200}") int maxOutputTokens
    ) {
        this.chatModels = chatModels;
        this.marketTools = marketTools;
        this.model = model;
        this.maxOutputTokens = maxOutputTokens;
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
        for (int round = 0; round < MAX_TOOL_ROUNDS && !cancelled.getAsBoolean(); round++) {
            boolean forceAnswer = usedTools && round == MAX_TOOL_ROUNDS - 1;
            boolean calledTool = streamRound(chatModel, messages, !forceAnswer, counting, cancelled);
            if (!calledTool) {
                break;
            }
            usedTools = true;
        }
        if (usedTools && textCount.chars == 0 && !cancelled.getAsBoolean()) {
            streamRound(chatModel, messages, false, counting, cancelled);
        }
    }

    private boolean streamRound(
        ChatModel chatModel,
        List<Message> messages,
        boolean includeTools,
        StreamListener listener,
        BooleanSupplier cancelled
    ) {
        DeepSeekChatOptions options = options(includeTools);
        Prompt prompt = new Prompt(messages, options);
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        ToolCallBuffer toolCalls = new ToolCallBuffer();
        try {
            chatModel.stream(prompt)
                .takeWhile(chunk -> !cancelled.getAsBoolean())
                .doOnNext(chunk -> absorb(chunk, text, reasoning, toolCalls, listener))
                .blockLast();
        } catch (RuntimeException exception) {
            if (cancelled.getAsBoolean()) {
                return false;
            }
            if (exception instanceof AiProviderException providerException) {
                throw providerException;
            }
            throw new AiProviderException("Unable to communicate with DeepSeek", exception);
        }
        if (cancelled.getAsBoolean() || !includeTools) {
            return false;
        }
        List<AssistantMessage.ToolCall> calls = toolCalls.toList();
        if (calls.isEmpty()) {
            return false;
        }
        if (calls.size() > MAX_TOOL_CALLS_PER_ROUND) {
            calls = List.copyOf(calls.subList(0, MAX_TOOL_CALLS_PER_ROUND));
        }
        DeepSeekAssistantMessage assistant = DeepSeekAssistantMessage.builder()
            .content(text.toString())
            .reasoningContent(reasoning.isEmpty() ? null : reasoning.toString())
            .toolCalls(calls)
            .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(assistant)));
        ToolActivity.bind(new ToolActivity.Listener() {
            @Override
            public void started(String name) {
                listener.onToolStarted(name);
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
        return true;
    }

    private void absorb(
        ChatResponse chunk,
        StringBuilder text,
        StringBuilder reasoning,
        ToolCallBuffer toolCalls,
        StreamListener listener
    ) {
        if (chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return;
        }
        AssistantMessage output = chunk.getResult().getOutput();
        String delta = output.getText();
        if (delta != null && !delta.isEmpty()) {
            text.append(delta);
            listener.onText(delta);
        }
        if (output instanceof DeepSeekAssistantMessage deepSeek
            && deepSeek.getReasoningContent() != null
            && !deepSeek.getReasoningContent().isEmpty()) {
            reasoning.append(deepSeek.getReasoningContent());
            listener.onReasoning(deepSeek.getReasoningContent());
        }
        toolCalls.absorb(output.getToolCalls());
    }

    private DeepSeekChatOptions options(boolean includeTools) {
        DeepSeekChatOptions.Builder builder = DeepSeekChatOptions.builder()
            .model(model)
            .maxTokens(maxOutputTokens)
            .enableThinking();
        if (includeTools) {
            builder.toolCallbacks(toolCallbacks());
        }
        return builder.build();
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
            DeepSeekAssistantMessage.Builder builder = DeepSeekAssistantMessage.builder().content(content);
            if (prior.reasoning() != null && !prior.reasoning().isBlank()) {
                builder.reasoningContent(prior.reasoning());
            }
            return builder.build();
        }
        return new UserMessage(content);
    }

    public record PriorMessage(String role, String content, String reasoning) {
    }

    public interface StreamListener {
        void onText(String delta);

        void onReasoning(String delta);

        void onToolStarted(String name);

        void onToolFinished(String name, String argumentsJson, String result, int durationMs);
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
                public void onToolFinished(String name, String argumentsJson, String result, int durationMs) {
                    listener.onToolFinished(name, argumentsJson, result, durationMs);
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
