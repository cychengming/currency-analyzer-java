package com.currencyanalyzer.pubsub;

import com.currencyanalyzer.alerts.AlertDeliveryException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class GcpPubSubRestAlertEventPublisher implements AlertEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(GcpPubSubRestAlertEventPublisher.class);
    private static final String PUBLISH_URI_TEMPLATE =
        "https://pubsub.googleapis.com/v1/projects/{projectId}/topics/{topic}:publish";

    private final RestClient restClient;
    private final String projectId;
    private final String topic;
    private final Supplier<String> accessTokenSupplier;
    private final ObjectMapper objectMapper;

    public GcpPubSubRestAlertEventPublisher(
        RestClient.Builder restClientBuilder,
        String projectId,
        String topic,
        String credentialsPath
    ) {
        this(
            GoogleHttpSupport.buildRestClient(restClientBuilder),
            projectId,
            topic,
            new CredentialsTokenSupplier(credentialsPath),
            new ObjectMapper()
        );
    }

    GcpPubSubRestAlertEventPublisher(
        RestClient restClient,
        String projectId,
        String topic,
        Supplier<String> accessTokenSupplier,
        ObjectMapper objectMapper
    ) {
        this.restClient = restClient;
        this.projectId = projectId.trim();
        this.topic = topic.trim();
        this.accessTokenSupplier = accessTokenSupplier;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(AlertTriggeredEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("AlertTriggeredEvent is required");
        }
        String encodedData = encodeEvent(event);
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("alertId", Long.toString(event.alertId()));
        attributes.put("pair", event.pair() == null ? "" : event.pair());
        attributes.put("alertType", event.alertType() == null ? "" : event.alertType());

        Map<String, Object> body = Map.of(
            "messages", List.of(Map.of(
                "data", encodedData,
                "attributes", attributes
            ))
        );

        String token = accessTokenSupplier.get();
        try {
            restClient.post()
                .uri(PUBLISH_URI_TEMPLATE, projectId, topic)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
            log.info("Published alertId={} pair={} to Pub/Sub topic projects/{}/topics/{}",
                event.alertId(), event.pair(), projectId, topic);
        } catch (RestClientException exception) {
            throw new AlertDeliveryException("Failed to publish alert event to Pub/Sub", exception);
        }
    }

    private String encodeEvent(AlertTriggeredEvent event) {
        try {
            byte[] json = objectMapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
            return Base64.getEncoder().encodeToString(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize AlertTriggeredEvent", exception);
        }
    }

    static final class CredentialsTokenSupplier implements Supplier<String> {

        private final CredentialsLoader credentialsLoader;
        private final Object lock = new Object();
        private GoogleCredentials credentials;

        CredentialsTokenSupplier(String credentialsPath) {
            this(() -> GoogleHttpSupport.loadPubSubCredentials(credentialsPath));
        }

        CredentialsTokenSupplier(CredentialsLoader credentialsLoader) {
            this.credentialsLoader = credentialsLoader;
        }

        @Override
        public String get() {
            synchronized (lock) {
                try {
                    if (credentials == null) {
                        credentials = credentialsLoader.load();
                    }
                    credentials.refreshIfExpired();
                    AccessToken token = credentials.getAccessToken();
                    if (token == null || token.getTokenValue() == null || token.getTokenValue().isBlank()) {
                        token = credentials.refreshAccessToken();
                    }
                    return token.getTokenValue();
                } catch (IOException exception) {
                    throw new AlertDeliveryException("Unable to acquire Google OAuth2 token for Pub/Sub", exception);
                }
            }
        }
    }

    @FunctionalInterface
    interface CredentialsLoader {
        GoogleCredentials load() throws IOException;
    }
}
