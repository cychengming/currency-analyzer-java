package com.currencyanalyzer.ai.chat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/ai/conversations")
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true")
public class AiConversationController {

    private final AiConversationService conversationService;
    private final long streamTimeoutMillis;

    public AiConversationController(
        AiConversationService conversationService,
        @Value("${app.ai.request-timeout-seconds:60}") int requestTimeoutSeconds
    ) {
        this.conversationService = conversationService;
        this.streamTimeoutMillis = (requestTimeoutSeconds + 10L) * 1000;
    }

    @PostMapping
    public ResponseEntity<AiConversation> create(Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(conversationService.create(authentication.getName()));
    }

    @GetMapping
    public List<AiConversation> list(
        Authentication authentication,
        @RequestParam(defaultValue = "20") int limit
    ) {
        return conversationService.list(authentication.getName(), limit);
    }

    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<List<AiMessage>> messages(
        Authentication authentication,
        @PathVariable UUID conversationId
    ) {
        Optional<List<AiMessage>> messages = conversationService.messages(authentication.getName(), conversationId);
        return messages.isEmpty()
            ? ResponseEntity.notFound().build()
            : ResponseEntity.ok(messages.get());
    }

    @PostMapping(
        value = "/{conversationId}/messages",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public ResponseEntity<?> sendMessage(
        Authentication authentication,
        @PathVariable UUID conversationId,
        @RequestBody(required = false) SendMessageRequest request
    ) {
        if (!conversationService.ownsConversation(authentication.getName(), conversationId)) {
            return ResponseEntity.notFound().build();
        }
        SseEmitter emitter = new SseEmitter(streamTimeoutMillis);
        conversationService.streamMessage(
            authentication.getName(),
            conversationId,
            request == null ? null : request.message(),
            emitter
        );
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(emitter);
    }

    @PostMapping("/{conversationId}/cancel")
    public ResponseEntity<Void> cancel(
        Authentication authentication,
        @PathVariable UUID conversationId
    ) {
        if (!conversationService.cancel(authentication.getName(), conversationId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Map<String, Object>> delete(
        Authentication authentication,
        @PathVariable UUID conversationId
    ) {
        if (!conversationService.delete(authentication.getName(), conversationId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "success", false,
                "error", "Conversation not found"
            ));
        }
        return ResponseEntity.noContent().build();
    }

    public record SendMessageRequest(String message) {
    }
}
