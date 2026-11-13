package com.currencyanalyzer.ai.chat;

import com.currencyanalyzer.ai.provider.AiProvider;
import com.currencyanalyzer.ai.provider.AiProviderException;
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

    private final AiConversationRepository conversationRepository;
    private final AiProvider aiProvider;
    private final Clock clock;
    private final String provider;
    private final String model;
    private final int maxInputChars;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<UUID, ActiveResponse> activeResponses = new ConcurrentHashMap<>();

    public AiConversationService(
        AiConversationRepository conversationRepository,
        AiProvider aiProvider,
        Clock clock,
        @Value("${app.ai.provider:deepseek}") String provider,
        @Value("${app.ai.model:deepseek-flash}") String model,
        @Value("${app.ai.max-input-chars:8000}") int maxInputChars
    ) {
        this.conversationRepository = conversationRepository;
        this.aiProvider = aiProvider;
        this.clock = clock;
        this.provider = provider;
        this.model = model;
        this.maxInputChars = maxInputChars;
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
            aiProvider.stream(history, delta -> {
                if (activeResponse.cancelled.get() || delta.isEmpty()) {
                    return;
                }
                responseText.append(delta);
                sendEvent(activeResponse.emitter, "text_delta", Map.of("text", delta));
            }, activeResponse.cancelled::get);

            if (!activeResponse.cancelled.get() && !responseText.isEmpty()) {
                AiMessage assistantMessage = conversationRepository.addAssistantMessage(
                    conversationId, responseText.toString(), clock.instant()
                );
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

    private static void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write the assistant response stream", exception);
        }
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
