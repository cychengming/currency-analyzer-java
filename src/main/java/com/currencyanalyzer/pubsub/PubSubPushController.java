package com.currencyanalyzer.pubsub;

import com.currencyanalyzer.alerts.AlertEvaluationService;
import com.currencyanalyzer.alerts.AlertEvaluationService.EvaluationSummary;
import com.currencyanalyzer.alerts.AlertNotificationService;
import com.currencyanalyzer.alerts.AlertNotificationService.DeliveryResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PubSubPushController(
        AlertEvaluationService evaluationService,
        AlertNotificationService notificationService
    ) {
        this.evaluationService = evaluationService;
        this.notificationService = notificationService;
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

    private AlertTriggeredEvent decodeEvent(String base64Data) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Data.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Pub/Sub message data is not valid base64", exception);
        }
        try {
            return objectMapper.readValue(new String(decoded, StandardCharsets.UTF_8), AlertTriggeredEvent.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Pub/Sub message data is not a valid alert event", exception);
        }
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
