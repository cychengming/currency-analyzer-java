package com.currencyanalyzer.pubsub;

import com.currencyanalyzer.ai.rag.RagDocumentService;
import com.currencyanalyzer.ai.rag.RagDocumentService.StorageEventResult;
import com.currencyanalyzer.ai.rag.RagDocumentService.StorageObjectEvent;
import com.currencyanalyzer.alerts.AlertEvaluationService;
import com.currencyanalyzer.alerts.AlertEvaluationService.EvaluationSummary;
import com.currencyanalyzer.alerts.AlertNotificationService;
import com.currencyanalyzer.alerts.AlertNotificationService.DeliveryResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/internal/pubsub")
public class PubSubPushController {

    private static final Logger log = LoggerFactory.getLogger(PubSubPushController.class);

    private final AlertEvaluationService evaluationService;
    private final AlertNotificationService notificationService;
    private final ObjectProvider<RagDocumentService> ragDocumentServiceProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PubSubPushController(
        AlertEvaluationService evaluationService,
        AlertNotificationService notificationService,
        ObjectProvider<RagDocumentService> ragDocumentServiceProvider
    ) {
        this.evaluationService = evaluationService;
        this.notificationService = notificationService;
        this.ragDocumentServiceProvider = ragDocumentServiceProvider;
    }

    @PostMapping("/monitoring-tick")
    public EvaluationSummary handleMonitoringTick(
        @RequestBody(required = false) PubSubPushEnvelope envelope
    ) {
        String messageId = envelope != null && envelope.message() != null ? envelope.message().messageId() : "none";
        log.info("Received Pub/Sub monitoring tick messageId={} subscription={}",
            messageId, envelope == null ? "none" : envelope.subscription());
        return evaluationService.evaluateTick();
    }

    @PostMapping("/alert-notify")
    public DeliveryResult handleAlertNotify(
        @RequestBody(required = false) PubSubPushEnvelope envelope
    ) {
        if (envelope == null || envelope.message() == null
            || envelope.message().data() == null || envelope.message().data().isBlank()) {
            throw new IllegalArgumentException("Pub/Sub push message data is required");
        }
        AlertTriggeredEvent event = decodeEvent(envelope.message().data());
        log.info("Received Pub/Sub alert notification messageId={} alertId={} pair={}",
            envelope.message().messageId(), event.alertId(), event.pair());
        return notificationService.deliver(event);
    }

    @PostMapping("/gcs-rag-sync")
    public StorageEventResult handleGcsRagSync(
        @RequestBody(required = false) PubSubPushEnvelope envelope
    ) {
        if (envelope == null || envelope.message() == null) {
            throw new IllegalArgumentException("Pub/Sub push message is required");
        }
        StorageObjectEvent event = parseStorageEvent(envelope.message());
        RagDocumentService ragService = ragDocumentServiceProvider.getIfAvailable();
        if (ragService == null) {
            return new StorageEventResult("skipped", "rag_disabled", event.objectKey(), null);
        }
        log.info("Received GCS Pub/Sub event messageId={} eventType={} bucket={} objectKey={}",
            envelope.message().messageId(), event.eventType(), event.bucket(), event.objectKey());
        return ragService.handleStorageObjectEvent(event);
    }

    private StorageObjectEvent parseStorageEvent(PubSubMessage message) {
        Map<String, String> attrs = message.attributes() == null ? Map.of() : message.attributes();
        String eventType = trim(attrs.get("eventType"));
        String bucket = trim(attrs.get("bucketId"));
        String objectKey = trim(attrs.get("objectId"));
        String generation = trim(attrs.get("objectGeneration"));
        String overwrittenBy = trim(attrs.get("overwrittenByGeneration"));

        if (needsPayloadFallback(bucket, objectKey, generation)
            && message.data() != null && !message.data().isBlank()) {
            JsonNode payload = decodeJsonNode(message.data());
            bucket = firstNonEmpty(bucket, payload.path("bucket").asText(""));
            objectKey = firstNonEmpty(objectKey, payload.path("name").asText(""));
            generation = firstNonEmpty(generation, payload.path("generation").asText(""));
        }

        if (eventType.isEmpty() || objectKey.isEmpty()) {
            throw new IllegalArgumentException("GCS Pub/Sub notification requires eventType and objectId");
        }
        return new StorageObjectEvent(
            eventType,
            bucket,
            objectKey,
            emptyToNull(generation),
            emptyToNull(overwrittenBy)
        );
    }

    private static boolean needsPayloadFallback(String bucket, String objectKey, String generation) {
        return bucket.isEmpty() || objectKey.isEmpty() || generation.isEmpty();
    }

    private static String firstNonEmpty(String primary, String fallback) {
        return primary.isEmpty() ? trim(fallback) : primary;
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private AlertTriggeredEvent decodeEvent(String base64Data) {
        String json = decodeBase64(base64Data);
        try {
            return objectMapper.readValue(json, AlertTriggeredEvent.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Pub/Sub message data is not a valid alert event", exception);
        }
    }

    private JsonNode decodeJsonNode(String base64Data) {
        String json = decodeBase64(base64Data);
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Pub/Sub message data is not valid JSON", exception);
        }
    }

    private static String decodeBase64(String base64Data) {
        try {
            byte[] decoded = Base64.getDecoder().decode(base64Data.trim());
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Pub/Sub message data is not valid base64", exception);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record PubSubPushEnvelope(
        PubSubMessage message,
        String subscription
    ) {
    }

    public record PubSubMessage(
        String data,
        String messageId,
        String publishTime,
        Map<String, String> attributes
    ) {
    }
}
