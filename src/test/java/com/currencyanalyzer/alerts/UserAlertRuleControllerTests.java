package com.currencyanalyzer.alerts;

import com.currencyanalyzer.CurrencyAnalyzerApplication;
import com.currencyanalyzer.marketdata.LiveRate;
import com.currencyanalyzer.marketdata.MarketDataService;
import com.currencyanalyzer.pubsub.AlertEventPublisher;
import com.currencyanalyzer.pubsub.AlertTriggeredEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    classes = CurrencyAnalyzerApplication.class,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:user-alert-rules-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
        "app.rag.admin-users="
    }
)
@AutoConfigureMockMvc
class UserAlertRuleControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AlertEvaluationService evaluationService;

    @MockitoBean
    private MarketDataService marketDataService;

    @MockitoBean
    private AlertEventPublisher eventPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void enforcesPerUserIsolationMultiRuleCrudCrossingTriggerAndInboxLifecycle() throws Exception {
        // 1. Unauthenticated access rejected with 401
        mockMvc.perform(get("/api/alert-rules")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/alert-inbox")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/alert-inbox/profile")).andExpect(status().isUnauthorized());

        MockHttpSession alice = registerAndLogin("alice");
        MockHttpSession bob = registerAndLogin("bob");

        // Enable global monitoring as alice (admin)
        mockMvc.perform(post("/api/monitoring/start").session(alice).with(csrf()))
            .andExpect(status().isOk());

        when(marketDataService.fetchLiveRates()).thenReturn(Map.of(
            "GOLD/USD", new LiveRate(3619.0, -20.0, -0.55),
            "EUR/USD", new LiveRate(1.0971, 0.004, 0.37)
        ));

        // 2. Both alice and bob (non-admin!) save their own personal email & favorites
        mockMvc.perform(put("/api/alert-inbox/profile").session(alice).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"alert_email\":\"alice@fx.io\",\"favorites\":[\"gold/usd\",\"EUR/USD\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.alert_email").value("alice@fx.io"))
            .andExpect(jsonPath("$.favorites", hasSize(2)));

        mockMvc.perform(put("/api/alert-inbox/profile").session(bob).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"alert_email\":\"bob@macro.dev\",\"favorites\":[\"EUR/USD\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.alert_email").value("bob@macro.dev"))
            .andExpect(jsonPath("$.favorites", hasSize(1)));

        mockMvc.perform(get("/api/alert-inbox/profile").session(alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.alert_email").value("alice@fx.io"));

        // 3. Alice creates TWO rules on GOLD/USD (support + breakout), defaulting to ONCE
        MvcResult createBreakout = mockMvc.perform(post("/api/alert-rules").session(alice).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "pair": "GOLD/USD",
                      "nickname": "Gold Breakout",
                      "condition_type": "cross_above",
                      "target_value": 3680.0
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.pair").value("GOLD/USD"))
            .andExpect(jsonPath("$.frequency").value("ONCE"))
            .andExpect(jsonPath("$.status").value("ARMED"))
            .andExpect(jsonPath("$.spot_rate").value(3619.0))
            .andExpect(jsonPath("$.distance_pct").value(1.69))
            .andReturn();
        String aliceBreakoutId = objectMapper.readTree(createBreakout.getResponse().getContentAsString()).get("id").asText();

        MvcResult createSupport = mockMvc.perform(post("/api/alert-rules").session(alice).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "pair": "GOLD/USD",
                      "nickname": "Gold Floor",
                      "condition_type": "cross_below",
                      "target_value": 3550.0,
                      "frequency": "RECURRING",
                      "cooldown_seconds": 3600,
                      "notify_email": false
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.frequency").value("RECURRING"))
            .andExpect(jsonPath("$.notify_email").value(false))
            .andReturn();
        String aliceSupportId = objectMapper.readTree(createSupport.getResponse().getContentAsString()).get("id").asText();

        // Bob creates his own rule on EUR/USD
        mockMvc.perform(post("/api/alert-rules").session(bob).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "pair": "EUR/USD",
                      "nickname": "Bob EUR",
                      "condition_type": "cross_above",
                      "target_value": 1.1200
                    }
                    """))
            .andExpect(status().isCreated());

        // 4. Tenant isolation: alice sees 2 rules, bob sees 1 rule, bob cannot edit/delete alice's rule
        mockMvc.perform(get("/api/alert-rules").session(alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get("/api/alert-rules").session(bob))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].pair").value("EUR/USD"));

        mockMvc.perform(put("/api/alert-rules/" + aliceBreakoutId).session(bob).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"PAUSED\"}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/alert-rules/" + aliceBreakoutId).session(bob).with(csrf()))
            .andExpect(status().isNotFound());

        // 5. End-to-end crossing evaluation:
        // Tick 1: GOLD=3650 (still below 3680) -> 0 triggered
        when(marketDataService.fetchLiveRates()).thenReturn(Map.of(
            "GOLD/USD", new LiveRate(3650.0, 31.0, 0.86),
            "EUR/USD", new LiveRate(1.1000, 0.003, 0.27)
        ));
        assertThat(evaluationService.evaluateTick().triggered()).isZero();

        // Tick 2: GOLD crosses above 3680 -> 3690 -> Alice's ONCE rule fires & auto-disarms!
        when(marketDataService.fetchLiveRates()).thenReturn(Map.of(
            "GOLD/USD", new LiveRate(3690.0, 40.0, 1.10),
            "EUR/USD", new LiveRate(1.1020, 0.002, 0.18)
        ));
        assertThat(evaluationService.evaluateTick().triggered()).isEqualTo(1);

        ArgumentCaptor<AlertTriggeredEvent> eventCaptor = ArgumentCaptor.forClass(AlertTriggeredEvent.class);
        verify(eventPublisher, times(1)).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().pair()).isEqualTo("GOLD/USD");
        assertThat(eventCaptor.getValue().ruleName()).isEqualTo("Gold Breakout");
        assertThat(eventCaptor.getValue().notifyEmail()).isTrue();
        assertThat(eventCaptor.getValue().userId()).isNotNull();

        // Tick 3: GOLD stays above 3680 at 3710 -> ONCE rule is TRIGGERED (off), so 0 triggered!
        when(marketDataService.fetchLiveRates()).thenReturn(Map.of(
            "GOLD/USD", new LiveRate(3710.0, 20.0, 0.54),
            "EUR/USD", new LiveRate(1.1030, 0.001, 0.09)
        ));
        assertThat(evaluationService.evaluateTick().triggered()).isZero();

        // 6. Inbox isolation: Alice has 1 unread alert, Bob has 0
        MvcResult aliceInboxRes = mockMvc.perform(get("/api/alert-inbox").session(alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unread_count").value(1))
            .andExpect(jsonPath("$.alert_email").value("alice@fx.io"))
            .andExpect(jsonPath("$.alerts", hasSize(1)))
            .andExpect(jsonPath("$.alerts[0].rule_name").value("Gold Breakout"))
            .andReturn();
        JsonNode inboxJson = objectMapper.readTree(aliceInboxRes.getResponse().getContentAsString());
        long aliceAlertId = inboxJson.get("alerts").get(0).get("id").asLong();

        mockMvc.perform(get("/api/alert-inbox").session(bob))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unread_count").value(0))
            .andExpect(jsonPath("$.alerts", hasSize(0)));

        // Bob cannot mark Alice's alert read or clear Alice's inbox
        mockMvc.perform(post("/api/alert-inbox/" + aliceAlertId + "/read").session(bob).with(csrf()))
            .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/alert-inbox/clear").session(bob).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deleted").value(0));

        // Alice marks read, marks all read, re-arms her triggered rule, deletes support rule, clears her inbox
        mockMvc.perform(post("/api/alert-inbox/" + aliceAlertId + "/read").session(alice).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(get("/api/alert-inbox").session(alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unread_count").value(0));
        mockMvc.perform(post("/api/alert-inbox/read-all").session(alice).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(put("/api/alert-rules/" + aliceBreakoutId).session(alice).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ARMED\",\"target_value\":3750.0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ARMED"))
            .andExpect(jsonPath("$.target_value").value(3750.0));

        mockMvc.perform(delete("/api/alert-rules/" + aliceSupportId).session(alice).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(get("/api/alert-rules").session(alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)));

        mockMvc.perform(delete("/api/alert-inbox/clear").session(alice).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deleted").value(1));

        // Personal test email returns graceful failure when RESEND_API_KEY is not set
        mockMvc.perform(post("/api/alert-inbox/test-email").session(alice).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(false));

        // Invalid request payload returns 400
        mockMvc.perform(post("/api/alert-rules").session(alice).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pair\":\"GOLD/USD\",\"condition_type\":\"cross_above\",\"target_value\":-5}"))
            .andExpect(status().isBadRequest());

        // Phase B static shell & Alerts 2.0 assets are served cleanly
        mockMvc.perform(get("/dashboard.html"))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(org.hamcrest.Matchers.containsString("alertRuleModal")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(org.hamcrest.Matchers.containsString("markets_alerts.js")));
        mockMvc.perform(get("/static/pages/markets_alerts.js"))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(org.hamcrest.Matchers.containsString("openAlertRuleModal")));
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
