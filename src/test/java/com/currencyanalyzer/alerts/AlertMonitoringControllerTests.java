package com.currencyanalyzer.alerts;

import com.currencyanalyzer.CurrencyAnalyzerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    classes = CurrencyAnalyzerApplication.class,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:alert-monitoring-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
        "app.rag.admin-users="
    }
)
@AutoConfigureMockMvc
class AlertMonitoringControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void enforcesAuthReadsAndAdminOnlyMutationsAcrossAlertsMonitoringAndSettings() throws Exception {
        mockMvc.perform(get("/api/alerts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/monitoring/status")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/settings")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/alerts/preferences")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/alerts/conditions")).andExpect(status().isUnauthorized());

        register("alice", "secure-password");
        register("bob", "secure-password");

        MockHttpSession alice = login("alice", "secure-password");
        MockHttpSession bob = login("bob", "secure-password");

        mockMvc.perform(get("/api/alerts").session(bob)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/monitoring/status").session(bob)).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mockMvc.perform(get("/api/settings").session(bob)).andExpect(status().isOk()).andExpect(jsonPath("$.trend_threshold").value(2.0));
        mockMvc.perform(get("/api/alerts/preferences").session(bob))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$['NDX/USD'].pair").value("NDX/USD"))
            .andExpect(jsonPath("$['EUR/USD'].enabled").value(true));
        mockMvc.perform(get("/api/alerts/conditions").session(bob))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.percentage_change.name").value("Percentage Change"))
            .andExpect(jsonPath("$.price_threshold.name").value("Price Threshold"));

        mockMvc.perform(post("/api/monitoring/start").session(bob).with(csrf())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/monitoring/stop").session(bob).with(csrf())).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/alerts/clear").session(bob).with(csrf())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/settings").session(bob).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"trend_threshold\":3.5}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/alerts/preferences").session(bob).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pair\":\"EUR/USD\",\"enabled\":false,\"alert_type\":\"percentage_change\"}"))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/monitoring/start").session(alice).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.active").value(true));
        mockMvc.perform(get("/api/monitoring/status").session(alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(true));
        mockMvc.perform(post("/api/monitoring/stop").session(alice).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.active").value(false));
        mockMvc.perform(get("/api/monitoring/status").session(alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(false));

        jdbcTemplate.update("""
                INSERT INTO alerts (pair, percent_change, old_rate, new_rate, timestamp, email_sent, alert_type, trigger_value, threshold_value)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "NDX/USD", 6.86, 25028.98, 26746.10,
            OffsetDateTime.of(2026, 11, 24, 10, 0, 0, 0, ZoneOffset.UTC),
            true, "percentage_change", 6.86, 2.0
        );

        mockMvc.perform(get("/api/alerts").session(alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].pair").value("NDX/USD"))
            .andExpect(jsonPath("$[0].percent_change").value(6.86))
            .andExpect(jsonPath("$[0].old_rate").value(25028.98))
            .andExpect(jsonPath("$[0].new_rate").value(26746.10))
            .andExpect(jsonPath("$[0].timestamp").value("2026-11-24T10:00:00Z"))
            .andExpect(jsonPath("$[0].email_sent").value(true));

        mockMvc.perform(delete("/api/alerts/clear").session(alice).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(get("/api/alerts").session(alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(post("/api/settings").session(alice).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "trend_threshold": "3.5",
                      "detection_period": "14",
                      "check_interval": 300,
                      "alert_email": "trader@example.com",
                      "enable_alerts": false,
                      "enable_sound": "false",
                      "daily_risk_limit_pct": 2.5
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/settings").session(alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.trend_threshold").value(3.5))
            .andExpect(jsonPath("$.detection_period").value(14))
            .andExpect(jsonPath("$.check_interval").value(300))
            .andExpect(jsonPath("$.alert_email").value("trader@example.com"))
            .andExpect(jsonPath("$.enable_alerts").value(false))
            .andExpect(jsonPath("$.enable_sound").value(false))
            .andExpect(jsonPath("$.daily_risk_limit_pct").value(2.5));

        expectBadRequest(alice, "/api/settings", null);
        expectBadRequest(alice, "/api/settings", "{\"trend_threshold\": -1}");
        expectBadRequest(alice, "/api/settings", "{\"trend_threshold\": \"not-a-number\"}");
        expectBadRequest(alice, "/api/settings", "{\"detection_period\": 1.5}");
        expectBadRequest(alice, "/api/settings", "{\"detection_period\": \"bad-int\"}");
        expectBadRequest(alice, "/api/settings", "{\"check_interval\": 10}");
        expectBadRequest(alice, "/api/settings", "{\"enable_alerts\": \"maybe\"}");
        expectBadRequest(alice, "/api/settings", "{\"alert_email\": \"" + "a".repeat(260) + "\"}");

        mockMvc.perform(post("/api/settings").session(alice).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"alert_email\": null}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(post("/api/alerts/preferences").session(alice).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "pair": "ndx/usd",
                      "enabled": false,
                      "alert_type": "price_threshold",
                      "price_high": 27000.0,
                      "price_low": 24500.0,
                      "trigger_type": "above",
                      "custom_threshold": 4.2,
                      "custom_period": 20,
                      "enable_trend_consistency": false
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/alerts/preferences").session(alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$['NDX/USD'].enabled").value(false))
            .andExpect(jsonPath("$['NDX/USD'].alert_type").value("price_threshold"))
            .andExpect(jsonPath("$['NDX/USD'].price_high").value(27000.0))
            .andExpect(jsonPath("$['NDX/USD'].price_low").value(24500.0))
            .andExpect(jsonPath("$['NDX/USD'].trigger_type").value("above"))
            .andExpect(jsonPath("$['NDX/USD'].custom_threshold").value(4.2))
            .andExpect(jsonPath("$['NDX/USD'].custom_period").value(20))
            .andExpect(jsonPath("$['NDX/USD'].enable_trend_consistency").value(false));

        mockMvc.perform(post("/api/alerts/preferences").session(alice).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pair\":\"NDX/USD\",\"price_high\":null}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/alerts/preferences").session(alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$['NDX/USD'].alert_type").value("percentage_change"))
            .andExpect(jsonPath("$['NDX/USD'].price_high").isEmpty())
            .andExpect(jsonPath("$['NDX/USD'].custom_threshold").value(4.2))
            .andExpect(jsonPath("$['NDX/USD'].custom_period").value(20))
            .andExpect(jsonPath("$['NDX/USD'].enable_trend_consistency").value(false));

        mockMvc.perform(post("/api/alerts/preferences").session(alice).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "pair": "CUSTOM/USD",
                      "enabled": true,
                      "alert_type": "moving_average_crossover",
                      "ma_short_period": 12,
                      "ma_long_period": 26,
                      "lookback_years": 3,
                      "signal_type": "golden_cross",
                      "volatility_type": "atr"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/alerts/preferences").session(alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$['CUSTOM/USD'].ma_short_period").value(12))
            .andExpect(jsonPath("$['CUSTOM/USD'].ma_long_period").value(26))
            .andExpect(jsonPath("$['CUSTOM/USD'].signal_type").value("golden_cross"));

        expectBadRequest(alice, "/api/alerts/preferences", null);
        expectBadRequest(alice, "/api/alerts/preferences", "{\"pair\":\"   \",\"alert_type\":\"percentage_change\"}");
        expectBadRequest(alice, "/api/alerts/preferences", "{\"pair\":\"EUR/USD\",\"alert_type\":\"unknown_rule\"}");
        expectBadRequest(alice, "/api/alerts/preferences", "{\"pair\":\"EUR/USD\",\"price_high\":-5}");
    }

    private void expectBadRequest(MockHttpSession session, String uri, String body) throws Exception {
        var request = post(uri).session(session).with(csrf());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        mockMvc.perform(request)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
    }

    private void register(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(username, password)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(username, password)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    private static String credentials(String username, String password) {
        return """
            {
              "username": "%s",
              "password": "%s"
            }
            """.formatted(username, password);
    }
}
