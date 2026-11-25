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
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    private final AiMetrics metrics;
    private final String provider;
    private final String model;
    private final int maxInputChars;
    private final int maxConcurrentPerUser;
    private final boolean knowledgeEnabled;
    private final int knowledgeMaxNotes;
    private final int knowledgeMaxChars;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<UUID, ActiveResponse> activeResponses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> userActiveStreams = new ConcurrentHashMap<>();
    private final Semaphore globalConcurrency;

    public AiConversationService(
        AiConversationRepository conversationRepository,
        AiProvider aiProvider,
        AssistantTools assistantTools,
        SpringAiChatRunner springAiChatRunner,
        KnowledgeRetriever knowledgeRetriever,
        AiUsageService usageService,
        AiRuntimeSupport runtimeSupport
    ) {
        this.conversationRepository = conversationRepository;
        this.aiProvider = aiProvider;
        this.assistantTools = assistantTools;
        this.springAiChatRunner = springAiChatRunner;
        this.knowledgeRetriever = knowledgeRetriever;
        this.usageService = usageService;
        this.clock = runtimeSupport.clock();
        this.metrics = runtimeSupport.metrics();
        AiAssistantProperties props = runtimeSupport.properties();
        this.provider = props.getProvider();
        this.model = props.getModel();
        this.maxInputChars = props.getMaxInputChars();
        this.maxConcurrentPerUser = props.getMaxConcurrentPerUser();
        this.globalConcurrency = new Semaphore(props.getMaxConcurrentGlobal());
        this.knowledgeEnabled = props.getKnowledge().isEnabled();
        this.knowledgeMaxNotes = props.getKnowledge().getMaxNotes();
        this.knowledgeMaxChars = props.getKnowledge().getMaxChars();
        this.metrics.registerActiveStreamsGauge(map -> ((Map<?, ?>) map).size(), activeResponses);
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
        if (activeResponses.containsKey(conversationId)) {
            throw new AiResponseInProgressException();
        }
        int inFlightBeforeThis = acquireConcurrencySlots(username);
        ActiveResponse activeResponse = new ActiveResponse(username, emitter, () -> releaseConcurrencySlots(username));

        try {
            usageService.ensureWithinDailyLimit(username, inFlightBeforeThis);
            if (activeResponses.putIfAbsent(conversationId, activeResponse) != null) {
                throw new AiResponseInProgressException();
            }
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
            activeResponse.releaseSlots();
            throw exception;
        }
    }

    private int acquireConcurrencySlots(String username) {
        if (!globalConcurrency.tryAcquire()) {
            metrics.recordRateLimited("global_concurrency");
            throw new AiUsageLimitException("AI assistant is temporarily at capacity; please retry shortly");
        }
        int[] reserved = {-1};
        userActiveStreams.compute(username, (ignored, counter) -> {
            int current = counter == null ? 0 : counter.get();
            if (current >= maxConcurrentPerUser) {
                return counter;
            }
            AtomicInteger target = counter == null ? new AtomicInteger(0) : counter;
            target.set(current + 1);
            reserved[0] = current + 1;
            return target;
        });
        if (reserved[0] < 0) {
            globalConcurrency.release();
            metrics.recordRateLimited("user_concurrency");
            throw new AiResponseInProgressException("Too many concurrent AI responses in progress");
        }
        return reserved[0] - 1;
    }

    private void releaseConcurrencySlots(String username) {
        userActiveStreams.computeIfPresent(username, (ignored, counter) ->
            counter.decrementAndGet() <= 0 ? null : counter
        );
        globalConcurrency.release();
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
        TurnState turn = new TurnState(activeResponse, System.nanoTime());
        String outcome = "error";
        ToolUserContext.bind(activeResponse.username);
        try {
            sendEvent(activeResponse.emitter, "message_started", Map.of(
                "user_message_id", userMessageId.toString()
            ));
            executeModelTurn(conversationId, turn);
            outcome = persistCompletedTurn(conversationId, turn);
        } catch (RuntimeException exception) {
            outcome = handleTurnFailure(conversationId, activeResponse, exception);
        } finally {
            finalizeTurn(conversationId, activeResponse, turn, outcome);
        }
    }

    private void executeModelTurn(UUID conversationId, TurnState turn) {
        List<AiConversationRepository.AiProviderMessage> history =
            conversationRepository.findRecentMessages(conversationId);
        String referenceContext = referenceContext(history);
        if (springAiChatRunner.available()) {
            answerWithSpringAi(history, referenceContext, turn);
            return;
        }
        List<Turn> prompt = promptWithContext(history, referenceContext);
        boolean usedTools = answerWithTools(conversationId, prompt, turn);
        if (!usedTools && turn.responseText.isEmpty() && !turn.active.cancelled.get()) {
            consumeTracked(prompt, turn);
        }
        if (turn.responseText.isEmpty() && !turn.active.cancelled.get()) {
            retryWithoutThinking(prompt, turn);
        }
    }

    private String persistCompletedTurn(UUID conversationId, TurnState turn) {
        if (turn.active.cancelled.get()) {
            return "cancelled";
        }
        String persistedContent = DsmlToolCallSanitizer.stripDsml(turn.responseText.toString());
        if (persistedContent.isEmpty()) {
            sendEvent(turn.active.emitter, "error", Map.of(
                "message", "The AI provider returned an empty response"
            ));
            return "empty";
        }
        AiMessage assistantMessage = conversationRepository.addAssistantMessage(
            conversationId,
            persistedContent,
            turn.reasoningText.toString(),
            AssistantPrompt.PROMPT_VERSION,
            turn.providerResponseId.get(),
            clock.instant()
        );
        for (ToolTrace toolTrace : turn.toolTraces) {
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
        sendEvent(turn.active.emitter, "message_completed", Map.of(
            "message_id", assistantMessage.id().toString(),
            "finish_reason", "completed"
        ));
        return "completed";
    }

    private String handleTurnFailure(
        UUID conversationId,
        ActiveResponse activeResponse,
        RuntimeException exception
    ) {
        if (activeResponse.cancelled.get()) {
            return "cancelled";
        }
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
        return "error";
    }

    private void finalizeTurn(
        UUID conversationId,
        ActiveResponse activeResponse,
        TurnState turn,
        String outcome
    ) {
        ToolUserContext.clear();
        try {
            usageService.record(
                activeResponse.username,
                conversationId,
                model,
                turn.usage.inputTokens,
                turn.usage.outputTokens,
                turn.usage.requests
            );
        } catch (RuntimeException exception) {
            log.warn("Unable to record AI usage for conversation {}", conversationId);
        }
        metrics.recordTurn(model, outcome, System.nanoTime() - turn.startedAtNanos);
        finishResponse(conversationId, activeResponse);
        activeResponse.emitter.complete();
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
        activeResponse.releaseSlots();
    }

    private void retryWithoutThinking(List<Turn> prompt, TurnState turn) {
        log.warn("DeepSeek produced no answer text. Retrying with thinking disabled.");
        List<AiProvider.Message> retry = new ArrayList<>(historyWithSystem(prompt));
        retry.add(new AiProvider.Message("user", "Write the final answer now. Do not leave it blank."));
        aiProvider.stream(
            retry,
            delta -> appendDelta(turn, delta),
            delta -> appendReasoning(turn, delta),
            turn.active.cancelled::get,
            false
        );
    }

    private void answerWithSpringAi(
        List<AiConversationRepository.AiProviderMessage> history,
        String referenceContext,
        TurnState turn
    ) {
        List<SpringAiChatRunner.PriorMessage> prior = priorWithContext(history, referenceContext);
        springAiChatRunner.answer(
            AssistantPrompt.systemMessage(),
            prior,
            new SpringAiChatRunner.StreamListener() {
                @Override
                public void onText(String delta) {
                    appendDelta(turn, delta);
                }

                @Override
                public void onReasoning(String delta) {
                    appendReasoning(turn, delta);
                }

                @Override
                public void onToolStarted(String name) {
                    sendEvent(turn.active.emitter, "tool_started", Map.of("tool", name));
                }

                @Override
                public void onUsage(int inputTokens, int outputTokens) {
                    turn.usage.add(new ModelUsage(inputTokens, outputTokens));
                }

                @Override
                public void onResponseId(String responseId) {
                    if (responseId != null && !responseId.isBlank()) {
                        turn.providerResponseId.set(responseId);
                    }
                }

                @Override
                public void onToolFinished(String name, String argumentsJson, String result, int durationMs) {
                    recordToolCompletion(turn, name, argumentsJson, result, durationMs);
                }
            },
            turn.active.cancelled::get
        );
    }

    private boolean answerWithTools(UUID conversationId, List<Turn> prompt, TurnState turn) {
        boolean usedTools = false;
        for (int round = 0; round < MAX_TOOL_ROUNDS && !turn.active.cancelled.get(); round++) {
            boolean forceAnswer = usedTools && round == MAX_TOOL_ROUNDS - 1;
            Completion completion = completeTracked(
                prompt,
                forceAnswer ? List.of() : assistantTools.definitions(),
                turn
            );
            if (completion.responseId() != null) {
                turn.providerResponseId.set(completion.responseId());
            }
            appendReasoning(turn, completion.reasoning());
            String cleanContent = DsmlToolCallSanitizer.stripDsml(completion.content());
            appendDelta(turn, cleanContent);
            if (!completion.hasToolCalls() || forceAnswer) {
                return usedTools || turn.responseText.length() > 0;
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
                prompt.add(executeTool(conversationId, turn, call));
            }
        }
        if (usedTools && turn.responseText.isEmpty() && !turn.active.cancelled.get()) {
            Completion completion = completeTracked(prompt, List.of(), turn);
            if (completion.responseId() != null) {
                turn.providerResponseId.set(completion.responseId());
            }
            appendReasoning(turn, completion.reasoning());
            appendDelta(turn, DsmlToolCallSanitizer.stripDsml(completion.content()));
        }
        return usedTools || turn.responseText.length() > 0;
    }

    private void consumeTracked(List<Turn> prompt, TurnState turn) {
        ModelUsage consumed = ModelUsage.NONE;
        try {
            consumed = aiProvider.consume(
                historyWithSystem(prompt),
                delta -> appendDelta(turn, delta),
                delta -> appendReasoning(turn, delta),
                turn.active.cancelled::get,
                true
            );
        } finally {
            turn.usage.add(consumed);
        }
    }

    private Completion completeTracked(
        List<Turn> prompt,
        List<AiProvider.ToolDefinition> tools,
        TurnState turn
    ) {
        try {
            Completion completion = aiProvider.complete(prompt, tools, turn.active.cancelled::get);
            turn.usage.add(completion.usage());
            return completion;
        } catch (RuntimeException exception) {
            turn.usage.add(ModelUsage.NONE);
            throw exception;
        }
    }

    private Turn executeTool(UUID conversationId, TurnState turn, ToolCall call) {
        sendEvent(turn.active.emitter, "tool_started", Map.of("tool", call.name()));
        long startedAt = System.nanoTime();
        String result = assistantTools.execute(call.name(), call.argumentsJson());
        int durationMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedAt) / 1_000_000);
        recordToolCompletion(turn, call.name(), call.argumentsJson(), result, durationMs);
        log.debug("Tool {} finished for conversation {} in {} ms", call.name(), conversationId, durationMs);
        return Turn.toolResult(call.id(), result);
    }

    private void recordToolCompletion(
        TurnState turn,
        String name,
        String argumentsJson,
        String result,
        int durationMs
    ) {
        boolean failed = result != null && result.startsWith("{\"error\"");
        String status = failed ? "error" : "ok";
        String summaryJson = ToolResultSummarizer.summarize(name, result);
        turn.toolTraces.add(new ToolTrace(
            name,
            truncate(argumentsJson, 2000),
            status,
            durationMs,
            summaryJson
        ));
        metrics.recordToolCall(name, status, durationMs);
        sendEvent(turn.active.emitter, "tool_completed", Map.of(
            "tool", name,
            "status", status
        ));
    }

    private void appendReasoning(TurnState turn, String delta) {
        if (turn.active.cancelled.get() || delta == null || delta.isEmpty()) {
            return;
        }
        recordFirstTokenIfNeeded(turn);
        turn.reasoningText.append(delta);
        sendEvent(turn.active.emitter, "reasoning_delta", Map.of("text", delta));
    }

    private static Turn historyTurn(AiConversationRepository.AiProviderMessage message) {
        String content = "assistant".equals(message.role())
            ? DsmlToolCallSanitizer.stripDsml(message.content())
            : message.content();
        // Omit historical reasoning_content across completed turns to keep prompt token usage compact.
        return Turn.text(message.role(), content);
    }

    private void appendDelta(TurnState turn, String delta) {
        if (turn.active.cancelled.get() || delta == null || delta.isEmpty()) {
            return;
        }
        recordFirstTokenIfNeeded(turn);
        turn.responseText.append(delta);
        sendEvent(turn.active.emitter, "text_delta", Map.of("text", delta));
    }

    private void recordFirstTokenIfNeeded(TurnState turn) {
        if (turn.firstTokenRecorded.compareAndSet(false, true)) {
            metrics.recordFirstToken(model, System.nanoTime() - turn.startedAtNanos);
        }
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
            prior.add(new SpringAiChatRunner.PriorMessage(message.role(), message.content(), null));
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

    private static final class TurnState {
        private final ActiveResponse active;
        private final long startedAtNanos;
        private final StringBuilder responseText = new StringBuilder();
        private final StringBuilder reasoningText = new StringBuilder();
        private final List<ToolTrace> toolTraces = new ArrayList<>();
        private final UsageLedger usage = new UsageLedger();
        private final AtomicBoolean firstTokenRecorded = new AtomicBoolean();
        private final AtomicReference<String> providerResponseId = new AtomicReference<>();

        private TurnState(ActiveResponse active, long startedAtNanos) {
            this.active = active;
            this.startedAtNanos = startedAtNanos;
        }
    }

    private static final class ActiveResponse {
        private final String username;
        private final SseEmitter emitter;
        private final Runnable releaseAction;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();
        private volatile Future<?> future;

        private ActiveResponse(String username, SseEmitter emitter, Runnable releaseAction) {
            this.username = username;
            this.emitter = emitter;
            this.releaseAction = releaseAction;
        }

        private void releaseSlots() {
            if (released.compareAndSet(false, true)) {
                releaseAction.run();
            }
        }
    }
}
