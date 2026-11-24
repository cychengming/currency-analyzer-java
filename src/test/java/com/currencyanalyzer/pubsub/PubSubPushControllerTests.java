package com.currencyanalyzer.pubsub;

import com.currencyanalyzer.CurrencyAnalyzerApplication;
import com.currencyanalyzer.alerts.AlertMonitoringRepository;
import com.currencyanalyzer.alerts.AlertMonitoringRepository.AlertEntry;
import com.currencyanalyzer.marketdata.LiveRate;
import com.currencyanalyzer.marketdata.MarketDataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    classes = CurrencyAnalyzerApplication.class,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:pubsub-push-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
        "app.rag.admin-users=",
        "app.pubsub.alert-cooldown-seconds=3600"
    }
)
@AutoConfigureMockMvc
class PubSubPushControllerTests {

    private static final String VALID_BEARER = "Bearer valid-oidc-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AlertMonitoringRepository repository;

    @MockitoBean
    private PubSubOidcVerifier oidcVerifier;

    @MockitoBean
    private MarketDataService marketDataService;

    @MockitoBean
    private AlertEventPublisher eventPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void enforcesOidcAuthRunsMonitoringTickWithCooldownAndDeliversIdempotently() throws Exception {
        when(oidcVerifier.verifyAuthorizationHeader(VALID_BEARER))
            .thenReturn(Optional.of("pubsub-invoker@project-3806d7a4-0bd7-416f-8c4.iam.gserviceaccount.com"));
        when(marketDataService.configuredPairs()).thenReturn(List.of("NDX/USD"));
        when(marketDataService.fetchLiveRates()).thenReturn(Map.of(
            "NDX/USD", new LiveRate(27200.0, 600.0, 2.26)
        ));

        // 1. Anonymous and invalid-token requests are rejected with 403
        mockMvc.perform(post("/internal/pubsub/monitoring-tick"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/internal/pubsub/monitoring-tick")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid"))
            .andExpect(status().isForbidden());

        // 2. Register admin session and prove browser session cookies cannot access /internal/pubsub/**
        MockHttpSession adminSession = registerAndLogin("owner");
        mockMvc.perform(post("/internal/pubsub/monitoring-tick").session(adminSession))
            .andExpect(status().isForbidden());

        // 3. Configure price_threshold rule on NDX/USD and enable monitoring
        mockMvc.perform(post("/api/alerts/preferences").session(adminSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "pair": "NDX/USD",
                      "enabled": true,
                      "alert_type": "price_threshold",
                      "price_high": 27000.0,
                      "trigger_type": "above"
                    }
                    """))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/monitoring/start").session(adminSession).with(csrf()))
            .andExpect(status().isOk());

        // 4. First valid OIDC monitoring tick triggers 1 alert and publishes event
        String tickBody = """
            {
              "message": {
                "data": "eyJzb3VyY2UiOiJjbG91ZC1zY2hlZHVsZXIifQ==",
                "messageId": "tick-1"
              },
              "subscription": "projects/demo/subscriptions/monitoring-ticks-push"
            }
            """;
        mockMvc.perform(post("/internal/pubsub/monitoring-tick")
                .header(HttpHeaders.AUTHORIZATION, VALID_BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(tickBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.evaluated").value(1))
            .andExpect(jsonPath("$.triggered").value(1))
            .andExpect(jsonPath("$.alerts[0].pair").value("NDX/USD"));

        verify(eventPublisher, times(1)).publish(any(AlertTriggeredEvent.class));

        // 5. Immediate second tick is suppressed by monitoring_state cooldown
        mockMvc.perform(post("/internal/pubsub/monitoring-tick")
                .header(HttpHeaders.AUTHORIZATION, VALID_BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(tickBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.triggered").value(0));

        List<AlertEntry> storedAlerts = repository.listAlerts(10);
        assertThat(storedAlerts).hasSize(1);
        AlertEntry saved = storedAlerts.getFirst();
        assertThat(saved.emailSent()).isFalse();

        // 6. Phase 1B push notification delivers once and marks email_sent=true; redelivery skips idempotently
        String encodedEvent = Base64.getEncoder().encodeToString(
            objectMapper.writeValueAsString(AlertTriggeredEvent.from(saved)).getBytes(StandardCharsets.UTF_8)
        );
        String notifyBody = """
            {
              "message": {
                "data": "%s",
                "messageId": "notify-1"
              },
              "subscription": "projects/demo/subscriptions/currency-alerts-notify-push"
            }
            """.formatted(encodedEvent);

        mockMvc.perform(post("/internal/pubsub/alert-notify")
                .header(HttpHeaders.AUTHORIZATION, VALID_BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(notifyBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("delivered"))
            .andExpect(jsonPath("$.alertId").value(saved.id()));

        assertThat(repository.findAlertById(saved.id()).orElseThrow().emailSent()).isTrue();

        mockMvc.perform(post("/internal/pubsub/alert-notify")
                .header(HttpHeaders.AUTHORIZATION, VALID_BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(notifyBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("skipped"))
            .andExpect(jsonPath("$.reason").value("already_delivered"));

        // 7. Malformed push payloads return 400 Bad Request, and empty tick body is accepted
        mockMvc.perform(post("/internal/pubsub/monitoring-tick")
                .header(HttpHeaders.AUTHORIZATION, VALID_BEARER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"));
        mockMvc.perform(post("/internal/pubsub/alert-notify")
                .header(HttpHeaders.AUTHORIZATION, VALID_BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":{\"data\":\"not-base64!!!\"}}"))
            .andExpect(status().isBadRequest());
        String badJsonBase64 = Base64.getEncoder().encodeToString("{bad-json".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(post("/internal/pubsub/alert-notify")
                .header(HttpHeaders.AUTHORIZATION, VALID_BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":{\"data\":\"" + badJsonBase64 + "\"}}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/internal/pubsub/alert-notify")
                .header(HttpHeaders.AUTHORIZATION, VALID_BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    private MockHttpSession registerAndLogin(String username) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"secure-password\"}";
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }
}
