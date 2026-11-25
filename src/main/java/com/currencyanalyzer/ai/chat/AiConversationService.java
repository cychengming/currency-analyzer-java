package com.currencyanalyzer.ai.chat;

import com.currencyanalyzer.ai.knowledge.AssistantPrompt;
import com.currencyanalyzer.ai.knowledge.KnowledgeRetriever;
import com.currencyanalyzer.ai.knowledge.RetrievalQueryRewriter;
import com.currencyanalyzer.ai.provider.AiProvider;
import com.currencyanalyzer.ai.provider.AiProvider.Completion;
import com.currencyanalyzer.ai.provider.AiProvider.ModelUsage;
import com.currencyanalyzer.ai.provider.AiProvider.ToolCall;
import com.currencyanalyzer.ai.provider.AiProvider.Turn;
import com.currencyanalyzer.ai.provider.AiProviderException;
import com.currencyanalyzer.ai.springai.DsmlToolCallSanitizer;
import com.currencyanalyzer.ai.springai.SpringAiChatRunner;
import com.currencyanalyzer.ai.tools.AssistantTools;
import com.currencyanalyzer.ai.tools.ToolUserContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AiConversationService {

    private static final Logger log = LoggerFactory.getLogger(AiConversationService.class);

    private static final int MAX_TOOL_ROUNDS = 3;
    private static final int MAX_TOOL_CALLS_PER_ROUND = 4;

    private final AiConversationRepository conversationRepository;
    private final AiProvider aiProvider;
    private final AssistantTools assistantTools;
    private final SpringAiChatRunner springAiChatRunner;
    private final KnowledgeRetriever knowledgeRetriever;
    private final AiUsageService usageService;
    private final Clock clock;
    private final String provider;
    private final String model;
    private final int maxInputChars;
    private final boolean knowledgeEnabled;
    private final int knowledgeMaxNotes;
    private final int knowledgeMaxChars;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<UUID, ActiveResponse> activeResponses = new ConcurrentHashMap<>();

    public AiConversationService(
        AiConversationRepository conversationRepository,
        AiProvider aiProvider,
        AssistantTools assistantTools,
        SpringAiChatRunner springAiChatRunner,
        KnowledgeRetriever knowledgeRetriever,
        AiUsageService usageService,
        Clock clock,
        @Value("${app.ai.provider:deepseek}") String provider,
        @Value("${app.ai.model:deepseek-flash}") String model,
        @Value("${app.ai.max-input-chars:8000}") int maxInputChars,
        @Value("${app.ai.knowledge.enabled:true}") boolean knowledgeEnabled,
        @Value("${app.ai.knowledge.max-notes:4}") int knowledgeMaxNotes,
        @Value("${app.ai.knowledge.max-chars:4000}") int knowledgeMaxChars
    ) {
        this.conversationRepository = conversationRepository;
        this.aiProvider = aiProvider;
        this.assistantTools = assistantTools;
        this.springAiChatRunner = springAiChatRunner;
        this.knowledgeRetriever = knowledgeRetriever;
        this.usageService = usageService;
        this.clock = clock;
        this.provider = provider;
        this.model = model;
        this.maxInputChars = maxInputChars;
        this.knowledgeEnabled = knowledgeEnabled;
        this.knowledgeMaxNotes = knowledgeMaxNotes;
        this.knowledgeMaxChars = knowledgeMaxChars;
    }

    @Transactional
    public AiConversation create(String username) {
        return conversationRepository.create(username, provider, model, clock.instant());
    }

    @Transactional(readOnly = true)
    public List<AiConversation> list(String username, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return conversationRepository.findByUsername(username, limit);
    }

    @Transactional
    public boolean delete(String username, UUID conversationId) {
        ActiveResponse activeResponse = activeResponses.get(conversationId);
        if (activeResponse != null) {
            cancel(username, conversationId);
        }
        return conversationRepository.deleteByIdAndUsername(conversationId, username);
    }

    public boolean ownsConversation(String username, UUID conversationId) {
        return conversationRepository.existsByIdAndUsername(conversationId, username);
    }

    public boolean streamMessage(
        String username,
        UUID conversationId,
        String content,
        SseEmitter emitter
    ) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (content.length() > maxInputChars) {
            throw new IllegalArgumentException("message exceeds the maximum length of " + maxInputChars);
        }
        if (!ownsConversation(username, conversationId)) {
            return false;
        }
        usageService.ensureWithinDailyLimit(username);

        ActiveResponse activeResponse = new ActiveResponse(username, emitter);
        if (activeResponses.putIfAbsent(conversationId, activeResponse) != null) {
            throw new AiResponseInProgressException();
        }

        try {
            AiMessage userMessage = conversationRepository.addUserMessage(conversationId, content, clock.instant());
            emitter.onCompletion(() -> finishResponse(conversationId, activeResponse));
            emitter.onTimeout(() -> cancelActiveResponse(conversationId, activeResponse));
            emitter.onError(exception -> cancelActiveResponse(conversationId, activeResponse));
            activeResponse.future = executor.submit(() ->
                generateResponse(conversationId, activeResponse, userMessage.id())
            );
            return true;
        } catch (RuntimeException exception) {
            activeResponses.remove(conversationId, activeResponse);
            throw exception;
        }
    }

    public boolean cancel(String username, UUID conversationId) {
        if (!ownsConversation(username, conversationId)) {
            return false;
        }
        ActiveResponse activeResponse = activeResponses.get(conversationId);
        if (activeResponse == null || !activeResponse.username.equals(username)) {
            return false;
        }
        cancelActiveResponse(conversationId, activeResponse);
        return true;
    }

    @Transactional(readOnly = true)
    public Optional<List<AiMessage>> messages(String username, UUID conversationId) {
        if (!ownsConversation(username, conversationId)) {
            return Optional.empty();
        }
        return Optional.of(conversationRepository.findConversationMessages(conversationId));
    }

    @PreDestroy
    void stopExecutor() {
        executor.shutdownNow();
    }

    private void generateResponse(UUID conversationId, ActiveResponse activeResponse, UUID userMessageId) {
        StringBuilder responseText = new StringBuilder();
        StringBuilder reasoningText = new StringBuilder();
        UsageLedger usage = new UsageLedger();
        ToolUserContext.bind(activeResponse.username);
        try {
            sendEvent(activeResponse.emitter, "message_started", Map.of(
                "user_message_id", userMessageId.toString()
            ));

            List<AiConversationRepository.AiProviderMessage> history =
                conversationRepository.findRecentMessages(conversationId);
            String referenceContext = referenceContext(history);
            List<ToolTrace> toolTraces = new ArrayList<>();
            if (springAiChatRunner.available()) {
                answerWithSpringAi(history, referenceContext, activeResponse, responseText, reasoningText, toolTraces, usage);
            } else {
                List<Turn> prompt = promptWithContext(history, referenceContext);
                boolean usedTools = answerWithTools(
                    conversationId, activeResponse, prompt, responseText, reasoningText, toolTraces, usage
                );
                if (!usedTools && responseText.isEmpty() && !activeResponse.cancelled.get()) {
                    consumeTracked(prompt, activeResponse, responseText, reasoningText, usage);
                }
                if (responseText.isEmpty() && !activeResponse.cancelled.get()) {
                    retryWithoutThinking(activeResponse, prompt, responseText, reasoningText);
                }
            }

            String persistedContent = DsmlToolCallSanitizer.stripDsml(responseText.toString());
            if (!activeResponse.cancelled.get() && !persistedContent.isEmpty()) {
                AiMessage assistantMessage = conversationRepository.addAssistantMessage(
                    conversationId, persistedContent, reasoningText.toString(), clock.instant()
                );
                for (ToolTrace toolTrace : toolTraces) {
                    conversationRepository.addToolInvocation(
                        assistantMessage.id(),
                        toolTrace.name(),
                        toolTrace.argumentsJson(),
                        toolTrace.summaryJson(),
                        toolTrace.status(),
                        toolTrace.durationMs(),
                        clock.instant()
                    );
                }
                sendEvent(activeResponse.emitter, "message_completed", Map.of(
                    "message_id", assistantMessage.id().toString(),
                    "finish_reason", "completed"
                ));
            } else if (!activeResponse.cancelled.get()) {
                sendEvent(activeResponse.emitter, "error", Map.of(
                    "message", "The AI provider returned an empty response"
                ));
            }
        } catch (RuntimeException exception) {
            if (!activeResponse.cancelled.get()) {
                log.warn(
                    "AI response failed for conversation {} ({})",
                    conversationId,
                    exception.getClass().getSimpleName()
                );
                String message = exception instanceof AiProviderException
                    ? exception.getMessage()
                    : "Unable to complete the AI response";
                try {
                    sendEvent(activeResponse.emitter, "error", Map.of("message", message));
                } catch (RuntimeException sendException) {
                    log.debug("Unable to send AI error event for conversation {}", conversationId);
                }
            }
        } finally {
            ToolUserContext.clear();
            try {
                usageService.record(
                    activeResponse.username,
                    conversationId,
                    model,
                    usage.inputTokens,
                    usage.outputTokens,
                    usage.requests
                );
            } catch (RuntimeException exception) {
                log.warn("Unable to record AI usage for conversation {}", conversationId);
            }
            activeResponses.remove(conversationId, activeResponse);
            activeResponse.emitter.complete();
        }
    }

    private void cancelActiveResponse(UUID conversationId, ActiveResponse activeResponse) {
        activeResponse.cancelled.set(true);
        Future<?> future = activeResponse.future;
        if (future != null) {
            future.cancel(true);
        }
        finishResponse(conversationId, activeResponse);
    }

    private void finishResponse(UUID conversationId, ActiveResponse activeResponse) {
        activeResponses.remove(conversationId, activeResponse);
    }

    private void retryWithoutThinking(
        ActiveResponse activeResponse,
        List<Turn> prompt,
        StringBuilder responseText,
        StringBuilder reasoningText
    ) {
        log.warn("DeepSeek produced no answer text. Retrying with thinking disabled.");
        List<AiProvider.Message> retry = new ArrayList<>(historyWithSystem(prompt));
        retry.add(new AiProvider.Message("user", "Write the final answer now. Do not leave it blank."));
        aiProvider.stream(
            retry,
            delta -> appendDelta(activeResponse, responseText, delta),
            delta -> appendReasoning(activeResponse, reasoningText, delta),
            activeResponse.cancelled::get,
            false
        );
    }

    private void answerWithSpringAi(
        List<AiConversationRepository.AiProviderMessage> history,
        String referenceContext,
        ActiveResponse activeResponse,
        StringBuilder responseText,
        StringBuilder reasoningText,
        List<ToolTrace> toolTraces,
        UsageLedger usage
    ) {
        List<SpringAiChatRunner.PriorMessage> prior = priorWithContext(history, referenceContext);
        springAiChatRunner.answer(
            AssistantPrompt.systemMessage(),
            prior,
            new SpringAiChatRunner.StreamListener() {
                @Override
                public void onText(String delta) {
                    appendDelta(activeResponse, responseText, delta);
                }

                @Override
                public void onReasoning(String delta) {
                    appendReasoning(activeResponse, reasoningText, delta);
                }

                @Override
                public void onToolStarted(String name) {
                    sendEvent(activeResponse.emitter, "tool_started", Map.of("tool", name));
                }

                @Override
                public void onUsage(int inputTokens, int outputTokens) {
                    usage.add(new ModelUsage(inputTokens, outputTokens));
                }

                @Override
                public void onToolFinished(String name, String argumentsJson, String result, int durationMs) {
                    boolean failed = result != null && result.startsWith("{\"error\"");
                    toolTraces.add(new ToolTrace(
                        name,
                        truncate(argumentsJson, 2000),
                        failed ? "error" : "ok",
                        durationMs,
                        failed ? "{\"status\":\"error\"}" : "{\"status\":\"ok\"}"
                    ));
                    sendEvent(activeResponse.emitter, "tool_completed", Map.of(
                        "tool", name,
                        "status", failed ? "error" : "ok"
                    ));
                }
            },
            activeResponse.cancelled::get
        );
    }

    private boolean answerWithTools(
        UUID conversationId,
        ActiveResponse activeResponse,
        List<Turn> prompt,
        StringBuilder responseText,
        StringBuilder reasoningText,
        List<ToolTrace> toolTraces,
        UsageLedger usage
    ) {
        boolean usedTools = false;
        for (int round = 0; round < MAX_TOOL_ROUNDS && !activeResponse.cancelled.get(); round++) {
            boolean forceAnswer = usedTools && round == MAX_TOOL_ROUNDS - 1;
            Completion completion = completeTracked(
                prompt,
                forceAnswer ? List.of() : assistantTools.definitions(),
                activeResponse,
                usage
            );
            appendReasoning(activeResponse, reasoningText, completion.reasoning());
            String cleanContent = DsmlToolCallSanitizer.stripDsml(completion.content());
            appendDelta(activeResponse, responseText, cleanContent);
            if (!completion.hasToolCalls() || forceAnswer) {
                return usedTools || responseText.length() > 0;
            }
            usedTools = true;
            List<ToolCall> calls = completion.toolCalls();
            int accepted = Math.min(calls.size(), MAX_TOOL_CALLS_PER_ROUND);
            prompt.add(Turn.toolRequest(cleanContent, completion.reasoning(), calls.subList(0, accepted)));
            for (int index = 0; index < calls.size(); index++) {
                ToolCall call = calls.get(index);
                if (index >= accepted) {
                    prompt.add(Turn.toolResult(call.id(), "{\"error\":\"Too many tool calls in one step.\"}"));
                    continue;
                }
                prompt.add(executeTool(conversationId, activeResponse, call, toolTraces));
            }
        }
        if (usedTools && responseText.isEmpty() && !activeResponse.cancelled.get()) {
            Completion completion = completeTracked(prompt, List.of(), activeResponse, usage);
            appendReasoning(activeResponse, reasoningText, completion.reasoning());
            appendDelta(activeResponse, responseText, DsmlToolCallSanitizer.stripDsml(completion.content()));
        }
        return usedTools || responseText.length() > 0;
    }

    private void consumeTracked(
        List<Turn> prompt,
        ActiveResponse activeResponse,
        StringBuilder responseText,
        StringBuilder reasoningText,
        UsageLedger usage
    ) {
        ModelUsage consumed = ModelUsage.NONE;
        try {
            consumed = aiProvider.consume(
                historyWithSystem(prompt),
                delta -> appendDelta(activeResponse, responseText, delta),
                delta -> appendReasoning(activeResponse, reasoningText, delta),
                activeResponse.cancelled::get,
                true
            );
        } finally {
            usage.add(consumed);
        }
    }

    private Completion completeTracked(
        List<Turn> prompt,
        List<AiProvider.ToolDefinition> tools,
        ActiveResponse activeResponse,
        UsageLedger usage
    ) {
        try {
            Completion completion = aiProvider.complete(prompt, tools, activeResponse.cancelled::get);
            usage.add(completion.usage());
            return completion;
        } catch (RuntimeException exception) {
            usage.add(ModelUsage.NONE);
            throw exception;
        }
    }

    private Turn executeTool(
        UUID conversationId,
        ActiveResponse activeResponse,
        ToolCall call,
        List<ToolTrace> toolTraces
    ) {
        sendEvent(activeResponse.emitter, "tool_started", Map.of("tool", call.name()));
        long startedAt = System.nanoTime();
        String result = assistantTools.execute(call.name(), call.argumentsJson());
        int durationMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedAt) / 1_000_000);
        boolean failed = result.startsWith("{\"error\"");
        toolTraces.add(new ToolTrace(
            call.name(),
            truncate(call.argumentsJson(), 2000),
            failed ? "error" : "ok",
            durationMs,
            failed ? "{\"status\":\"error\"}" : "{\"status\":\"ok\"}"
        ));
        sendEvent(activeResponse.emitter, "tool_completed", Map.of(
            "tool", call.name(),
            "status", failed ? "error" : "ok"
        ));
        log.debug("Tool {} finished for conversation {} in {} ms", call.name(), conversationId, durationMs);
        return Turn.toolResult(call.id(), result);
    }

    private void appendReasoning(ActiveResponse activeResponse, StringBuilder reasoningText, String delta) {
        if (activeResponse.cancelled.get() || delta == null || delta.isEmpty()) {
            return;
        }
        reasoningText.append(delta);
        sendEvent(activeResponse.emitter, "reasoning_delta", Map.of("text", delta));
    }

    private static Turn historyTurn(AiConversationRepository.AiProviderMessage message) {
        String content = "assistant".equals(message.role())
            ? DsmlToolCallSanitizer.stripDsml(message.content())
            : message.content();
        if ("assistant".equals(message.role()) && message.reasoning() != null && !message.reasoning().isBlank()) {
            return Turn.assistant(content, message.reasoning());
        }
        return Turn.text(message.role(), content);
    }

    private void appendDelta(ActiveResponse activeResponse, StringBuilder responseText, String delta) {
        if (activeResponse.cancelled.get() || delta == null || delta.isEmpty()) {
            return;
        }
        responseText.append(delta);
        sendEvent(activeResponse.emitter, "text_delta", Map.of("text", delta));
    }

    private static List<AiProvider.Message> historyWithSystem(List<Turn> prompt) {
        return prompt.stream()
            .filter(turn -> turn.toolCalls().isEmpty() && turn.toolCallId() == null)
            .map(turn -> new AiProvider.Message(turn.role(), turn.content()))
            .toList();
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "{}" : value;
        }
        return value.substring(0, maxChars);
    }

    private String referenceContext(List<AiConversationRepository.AiProviderMessage> history) {
        if (!knowledgeEnabled) {
            return "";
        }
        String query = RetrievalQueryRewriter.rewrite(history.stream()
            .map(message -> new RetrievalQueryRewriter.SpokenTurn(message.role(), message.content()))
            .toList());
        if (query.isBlank()) {
            return "";
        }
        return AssistantPrompt.referenceContext(
            knowledgeRetriever.retrieve(query, knowledgeMaxNotes),
            knowledgeMaxChars
        );
    }

    private static List<Turn> promptWithContext(
        List<AiConversationRepository.AiProviderMessage> history,
        String referenceContext
    ) {
        List<Turn> prompt = new ArrayList<>(history.size() + 2);
        prompt.add(Turn.text("system", AssistantPrompt.systemMessage()));
        int lastUser = lastUserIndex(history);
        for (int index = 0; index < history.size(); index++) {
            if (index == lastUser && referenceContext != null && !referenceContext.isBlank()) {
                prompt.add(Turn.text("user", referenceContext));
            }
            prompt.add(historyTurn(history.get(index)));
        }
        return prompt;
    }

    private static List<SpringAiChatRunner.PriorMessage> priorWithContext(
        List<AiConversationRepository.AiProviderMessage> history,
        String referenceContext
    ) {
        List<SpringAiChatRunner.PriorMessage> prior = new ArrayList<>(history.size() + 1);
        int lastUser = lastUserIndex(history);
        for (int index = 0; index < history.size(); index++) {
            if (index == lastUser && referenceContext != null && !referenceContext.isBlank()) {
                prior.add(new SpringAiChatRunner.PriorMessage("user", referenceContext, null));
            }
            AiConversationRepository.AiProviderMessage message = history.get(index);
            prior.add(new SpringAiChatRunner.PriorMessage(message.role(), message.content(), message.reasoning()));
        }
        return prior;
    }

    private static int lastUserIndex(List<AiConversationRepository.AiProviderMessage> history) {
        for (int index = history.size() - 1; index >= 0; index--) {
            if ("user".equals(history.get(index).role())) {
                return index;
            }
        }
        return -1;
    }

    private static void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write the assistant response stream", exception);
        }
    }

    private static final class UsageLedger {
        private int inputTokens;
        private int outputTokens;
        private int requests;

        private void add(ModelUsage usage) {
            requests++;
            if (usage == null) {
                return;
            }
            inputTokens += usage.inputTokens();
            outputTokens += usage.outputTokens();
        }
    }

    private record ToolTrace(
        String name,
        String argumentsJson,
        String status,
        int durationMs,
        String summaryJson
    ) {
    }

    private static final class ActiveResponse {
        private final String username;
        private final SseEmitter emitter;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Future<?> future;

        private ActiveResponse(String username, SseEmitter emitter) {
            this.username = username;
            this.emitter = emitter;
        }
    }
}
