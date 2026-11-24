package com.currencyanalyzer.pubsub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class PubSubConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PubSubConfiguration.class);

    @Bean
    public AlertEventPublisher alertEventPublisher(
        RestClient.Builder restClientBuilder,
        @Value("${app.pubsub.enabled:false}") boolean enabled,
        @Value("${app.pubsub.project-id:}") String projectId,
        @Value("${app.pubsub.alerts-topic:}") String alertsTopic,
        @Value("${app.pubsub.credentials-path:}") String credentialsPath
    ) {
        String normalizedProject = projectId == null ? "" : projectId.trim();
        String normalizedTopic = alertsTopic == null ? "" : alertsTopic.trim();
        if (enabled && !normalizedProject.isEmpty() && !normalizedTopic.isEmpty()) {
            log.info("Configured Pub/Sub alert publisher for projects/{}/topics/{}", normalizedProject, normalizedTopic);
            return new GcpPubSubRestAlertEventPublisher(
                restClientBuilder,
                normalizedProject,
                normalizedTopic,
                credentialsPath
            );
        }
        return event -> log.debug(
            "Pub/Sub alert publishing disabled; skipping alertId={} pair={}",
            event == null ? null : event.alertId(),
            event == null ? null : event.pair()
        );
    }
}
