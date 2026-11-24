package com.currencyanalyzer.alerts;

import com.currencyanalyzer.alerts.AlertMonitoringRepository.AlertEntry;
import com.currencyanalyzer.pubsub.AlertTriggeredEvent;
import com.currencyanalyzer.pubsub.GoogleHttpSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class AlertNotificationService {

    private static final Logger log = LoggerFactory.getLogger(AlertNotificationService.class);
    private static final String RESEND_EMAILS_URL = "https://api.resend.com/emails";
    private static final String STATUS_SKIPPED = "skipped";
    private static final String KEY_SUCCESS = "success";

    private final AlertMonitoringRepository repository;
    private final AlertMonitoringService monitoringService;
    private final RestClient restClient;
    private final String webhookUrl;
    private final String resendApiKey;
    private final String resendFromEmail;

    @Autowired
    public AlertNotificationService(
        AlertMonitoringRepository repository,
        AlertMonitoringService monitoringService,
        RestClient.Builder restClientBuilder,
        @Value("${app.alerts.webhook-url:}") String webhookUrl,
        @Value("${app.alerts.resend-api-key:}") String resendApiKey,
        @Value("${app.alerts.resend-from-email:onboarding@resend.dev}") String resendFromEmail
    ) {
        this(
            repository,
            monitoringService,
            GoogleHttpSupport.buildRestClient(restClientBuilder),
            webhookUrl,
            resendApiKey,
            resendFromEmail
        );
    }

    AlertNotificationService(
        AlertMonitoringRepository repository,
        AlertMonitoringService monitoringService,
        RestClient restClient,
        String webhookUrl,
        String resendApiKey,
        String resendFromEmail
    ) {
        this.repository = repository;
        this.monitoringService = monitoringService;
        this.restClient = restClient;
        this.webhookUrl = trim(webhookUrl);
        this.resendApiKey = trim(resendApiKey);
        this.resendFromEmail = trim(resendFromEmail).isEmpty() ? "onboarding@resend.dev" : trim(resendFromEmail);
    }

    public DeliveryResult deliver(AlertTriggeredEvent event) {
        if (event == null || event.alertId() <= 0 || event.pair() == null || event.pair().isBlank()) {
            throw new IllegalArgumentException("Valid alert event with alertId and pair is required");
        }

        Optional<AlertEntry> stored = repository.findAlertById(event.alertId());
        if (stored.isEmpty()) {
            return new DeliveryResult(STATUS_SKIPPED, "alert_not_found", event.alertId());
        }
        if (stored.get().emailSent()) {
            return new DeliveryResult(STATUS_SKIPPED, "already_delivered", event.alertId());
        }

        String summary = formatSummary(event);
        log.info(
            "event=CURRENCY_ALERT_TRIGGERED alertId={} pair={} alertType={} oldRate={} newRate={} percentChange={} threshold={}",
            event.alertId(),
            event.pair(),
            event.alertType(),
            event.oldRate(),
            event.newRate(),
            event.percentChange(),
            event.thresholdValue()
        );

        dispatchWebhook(summary, event);
        dispatchEmail(
            "Currency Analyzer Alert: " + event.pair(),
            summary,
            configuredAlertEmail()
        );

        repository.markAlertDelivered(event.alertId());
        return new DeliveryResult("delivered", null, event.alertId());
    }

    public Map<String, Object> sendTestNotification() {
        String recipient = configuredAlertEmail();
        boolean canSendWebhook = !webhookUrl.isEmpty();
        boolean canSendEmail = !resendApiKey.isEmpty() && !recipient.isEmpty();

        if (!canSendWebhook && !canSendEmail) {
            return Map.of(
                KEY_SUCCESS, false,
                "error", "Configure ALERT_WEBHOOK_URL or RESEND_API_KEY with an Alert Email address first."
            );
        }

        String summary = "Currency Analyzer test alert notification — outbound delivery is configured and working.";
        dispatchWebhook(summary, Map.of("type", "test_notification"));
        dispatchEmail("Currency Analyzer Test Alert", summary, recipient);

        return Map.of(
            KEY_SUCCESS, true,
            "message", "Test alert notification sent successfully."
        );
    }

    private void dispatchWebhook(String summary, Object payload) {
        if (webhookUrl.isEmpty()) {
            return;
        }
        try {
            restClient.post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                    "text", summary,
                    "content", summary,
                    "event", payload
                ))
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientException exception) {
            throw new AlertDeliveryException("Webhook alert delivery failed", exception);
        }
    }

    private void dispatchEmail(String subject, String body, String recipient) {
        if (resendApiKey.isEmpty() || recipient.isEmpty()) {
            return;
        }
        try {
            restClient.post()
                .uri(RESEND_EMAILS_URL)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + resendApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                    "from", resendFromEmail,
                    "to", List.of(recipient),
                    "subject", subject,
                    "text", body
                ))
                .retrieve()
                .toBodilessEntity();
        } catch (RestClientException exception) {
            throw new AlertDeliveryException("Email alert delivery failed", exception);
        }
    }

    private String configuredAlertEmail() {
        Object value = monitoringService.getSettings().get("alert_email");
        return value == null ? "" : value.toString().trim();
    }

    static String formatSummary(AlertTriggeredEvent event) {
        double pct = event.percentChange() == null ? 0.0 : event.percentChange();
        String sign = pct >= 0 ? "+" : "";
        return String.format(
            Locale.US,
            "[Currency Analyzer] %s (%s): %s%.2f%% (%s -> %s)",
            event.pair(),
            event.alertType(),
            sign,
            pct,
            event.oldRate(),
            event.newRate()
        );
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record DeliveryResult(
        String status,
        String reason,
        long alertId
    ) {
    }
}
