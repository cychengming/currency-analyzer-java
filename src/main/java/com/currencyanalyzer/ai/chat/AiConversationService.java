package com.currencyanalyzer.ai.chat;

import com.currencyanalyzer.ai.knowledge.AssistantPrompt;
import com.currencyanalyzer.ai.knowledge.KnowledgeRetriever;
import com.currencyanalyzer.ai.provider.AiProvider;
import com.currencyanalyzer.ai.provider.AiProvider.Completion;
import com.currencyanalyzer.ai.provider.AiProvider.ToolCall;
import com.currencyanalyzer.ai.provider.AiProvider.Turn;
import com.currencyanalyzer.ai.provider.AiProviderException;
import com.currencyanalyzer.ai.tools.AssistantTools;
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
    private final KnowledgeRetriever knowledgeRetriever;
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
        KnowledgeRetriever knowledgeRetriever,
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
        this.knowledgeRetriever = knowledgeRetriever;
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
        try {
            sendEvent(activeResponse.emitter, "message_started", Map.of(
                "user_message_id", userMessageId.toString()
            ));

            List<AiProvider.Message> history = conversationRepository.findRecentMessages(conversationId).stream()
                .map(message -> new AiProvider.Message(message.role(), message.content()))
                .toList();
            List<Turn> prompt = new ArrayList<>(history.size() + 1);
            prompt.add(Turn.text("system", systemMessage(latestUserMessage(history))));
            history.forEach(message -> prompt.add(Turn.text(message.role(), message.content())));
            List<ToolTrace> toolTraces = new ArrayList<>();
            boolean usedTools = answerWithTools(conversationId, activeResponse, prompt, responseText, toolTraces);
            if (!usedTools && responseText.isEmpty() && !activeResponse.cancelled.get()) {
                aiProvider.stream(historyWithSystem(prompt), delta -> appendDelta(activeResponse, responseText, delta),
                    activeResponse.cancelled::get);
            }

            if (!activeResponse.cancelled.get() && !responseText.isEmpty()) {
                AiMessage assistantMessage = conversationRepository.addAssistantMessage(
                    conversationId, responseText.toString(), clock.instant()
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

    private boolean answerWithTools(
        UUID conversationId,
        ActiveResponse activeResponse,
        List<Turn> prompt,
        StringBuilder responseText,
        List<ToolTrace> toolTraces
    ) {
        boolean usedTools = false;
        for (int round = 0; round < MAX_TOOL_ROUNDS && !activeResponse.cancelled.get(); round++) {
            boolean forceAnswer = usedTools && round == MAX_TOOL_ROUNDS - 1;
            Completion completion = aiProvider.complete(
                prompt,
                forceAnswer ? List.of() : assistantTools.definitions(),
                activeResponse.cancelled::get
            );
            appendDelta(activeResponse, responseText, completion.content());
            if (!completion.hasToolCalls() || forceAnswer) {
                return usedTools || responseText.length() > 0;
            }
            usedTools = true;
            List<ToolCall> calls = completion.toolCalls();
            int accepted = Math.min(calls.size(), MAX_TOOL_CALLS_PER_ROUND);
            prompt.add(Turn.toolRequest(completion.content(), calls.subList(0, accepted)));
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
            Completion completion = aiProvider.complete(prompt, List.of(), activeResponse.cancelled::get);
            appendDelta(activeResponse, responseText, completion.content());
        }
        return usedTools || responseText.length() > 0;
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

    private String systemMessage(String userMessage) {
        if (!knowledgeEnabled) {
            return AssistantPrompt.systemMessage(List.of(), 0);
        }
        return AssistantPrompt.systemMessage(
            knowledgeRetriever.retrieve(userMessage, knowledgeMaxNotes),
            knowledgeMaxChars
        );
    }

    private static String latestUserMessage(List<AiProvider.Message> history) {
        for (int index = history.size() - 1; index >= 0; index--) {
            AiProvider.Message message = history.get(index);
            if ("user".equals(message.role())) {
                return message.content();
            }
        }
        return "";
    }

    private static void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write the assistant response stream", exception);
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
