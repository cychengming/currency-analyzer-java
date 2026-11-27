package com.currencyanalyzer.trade;

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

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    classes = CurrencyAnalyzerApplication.class,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:trade-journal-it;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE"
    }
)
@AutoConfigureMockMvc
class TradeJournalIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void enforcesAuthRbacUserIsolationDailyRiskBudgetAndCloseLifecycle() throws Exception {
        // 1. Anonymous rejected with 401
        mockMvc.perform(get("/api/trade/summary")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/trade/diary")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/trade/diary").with(csrf())).andExpect(status().isUnauthorized());

        register("trader_alice", "password123");
        register("trader_bob", "password123");
        register("viewer_cara", "password123");
        jdbcTemplate.update("UPDATE users SET role = 'VIEWER' WHERE username = 'viewer_cara'");

        MockHttpSession alice = login("trader_alice", "password123");
        MockHttpSession bob = login("trader_bob", "password123");
        MockHttpSession cara = login("viewer_cara", "password123");

        // 2. Personal daily risk limit isolated per user
        mockMvc.perform(get("/api/trade/settings").session(alice))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.daily_risk_limit_pct").value(3.0));

        mockMvc.perform(put("/api/trade/settings").session(alice).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"daily_risk_limit_pct\": 2.5}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.daily_risk_limit_pct").value(2.5));

        mockMvc.perform(put("/api/trade/settings").session(alice).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"daily_risk_limit_pct\": 2.0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.daily_risk_limit_pct").value(2.0));

        mockMvc.perform(put("/api/trade/settings").session(alice).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"daily_risk_limit_pct\": -1.0}"))
            .andExpect(status().isBadRequest());

        // Bob still sees default 3.0%
        mockMvc.perform(get("/api/trade/settings").session(bob))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.daily_risk_limit_pct").value(3.0));

        // Viewer Cara can read summary/diary/settings, but cannot write (403)
        mockMvc.perform(get("/api/trade/summary?equity=10000&planned_risk_usd=50").session(cara))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.daily_limit_usd").value(300.0));
        mockMvc.perform(put("/api/trade/settings").session(cara).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"daily_risk_limit_pct\": 1.5}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/trade/diary").session(cara).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "pair": "EUR/USD",
                      "side": "long",
                      "entry_price": 1.0800,
                      "stop_price": 1.0700,
                      "quantity": 5000,
                      "equity": 10000,
                      "entry_reason": "Breakout above 20d high"
                    }
                    """))
            .andExpect(status().isForbidden());

        // 3. Stop-direction & field validation
        mockMvc.perform(post("/api/trade/diary").session(alice).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "pair": "EUR/USD",
                      "side": "long",
                      "entry_price": 1.0800,
                      "stop_price": 1.0900,
                      "quantity": 5000,
                      "equity": 10000,
                      "entry_reason": "Invalid stop above entry for long"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));

        // 4. Alice logs valid trade ($120 risk against $200 daily limit on $10,000 equity at 2.0%)
        MvcResult createRes = mockMvc.perform(post("/api/trade/diary").session(alice).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "pair": "EUR/USD",
                      "side": "long",
                      "entry_price": 1.0800,
                      "stop_price": 1.0680,
                      "quantity": 10000,
                      "equity": 10000,
                      "entry_reason": "Carver spine aligned momentum entry",
                      "notes": "NY close confirmed",
                      "atr": 0.0065,
                      "sigma": 0.48
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.entry.risk_amount_usd").value(closeTo(120.0, 0.01)))
            .andExpect(jsonPath("$.summary.remaining_risk_usd").value(closeTo(80.0, 0.01)))
            .andReturn();

        long tradeId = com.jayway.jsonpath.JsonPath.read(
            createRes.getResponse().getContentAsString(), "$.entry.id"
        ) instanceof Number n ? n.longValue() : 1L;

        // 5. Second trade exceeding remaining $80 daily budget is blocked with 400 + summary
        mockMvc.perform(post("/api/trade/diary").session(alice).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "pair": "GOLD/USD",
                      "side": "short",
                      "entry_price": 2400.0,
                      "stop_price": 2430.0,
                      "quantity": 4.0,
                      "equity": 10000,
                      "entry_reason": "Mean reversion attempt exceeding budget"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.summary.can_open_new_trade").value(false));

        // 6. Cross-user isolation: Bob sees 0 entries and gets 404 trying to edit/close Alice's trade
        mockMvc.perform(get("/api/trade/diary").session(bob))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entries", hasSize(0)));

        mockMvc.perform(put("/api/trade/diary/" + tradeId).session(bob).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "pair": "EUR/USD",
                      "side": "long",
                      "entry_price": 1.0800,
                      "stop_price": 1.0700,
                      "quantity": 5000,
                      "equity": 10000,
                      "entry_reason": "Hijack attempt"
                    }
                    """))
            .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/trade/diary/" + tradeId + "/close").session(bob).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"close_reason\":\"Hijack close\",\"close_price\":1.09}"))
            .andExpect(status().isNotFound());

        // Editing open trade above $200 daily limit is rejected with 400
        mockMvc.perform(put("/api/trade/diary/" + tradeId).session(alice).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "pair": "EUR/USD",
                      "side": "long",
                      "entry_price": 1.0800,
                      "stop_price": 1.0500,
                      "quantity": 10000,
                      "equity": 10000,
                      "entry_reason": "Over-widened stop exceeding daily cap"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));

        // 7. Alice edits her trade within budget ($150 total risk <= $200 limit), then closes at profit
        mockMvc.perform(put("/api/trade/diary/" + tradeId).session(alice).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "pair": "EUR/USD",
                      "side": "long",
                      "entry_price": 1.0800,
                      "stop_price": 1.0650,
                      "quantity": 10000,
                      "equity": 10000,
                      "entry_reason": "Widened stop slightly inside budget",
                      "notes": "Still within 2% daily cap"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entry.risk_amount_usd").value(closeTo(150.0, 0.01)))
            .andExpect(jsonPath("$.summary.remaining_risk_usd").value(closeTo(50.0, 0.01)));

        mockMvc.perform(post("/api/trade/diary/" + tradeId + "/close").session(alice).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "close_reason": "Hit 2R target ahead of FOMC",
                      "close_price": 1.1100,
                      "equity": 10000
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entry.status").value("closed"))
            .andExpect(jsonPath("$.entry.realized_pnl").value(closeTo(300.0, 0.01)))
            .andExpect(jsonPath("$.summary.active_risk_usd").value(0.0))
            .andExpect(jsonPath("$.summary.opened_today_risk_usd").value(closeTo(150.0, 0.01)));

        // Editing a closed trade updates close_price & close_reason cleanly
        mockMvc.perform(put("/api/trade/diary/" + tradeId).session(alice).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "pair": "EUR/USD",
                      "side": "long",
                      "entry_price": 1.0800,
                      "stop_price": 1.0650,
                      "quantity": 10000,
                      "equity": 10000,
                      "entry_reason": "Widened stop slightly inside budget",
                      "close_price": 1.1120,
                      "close_reason": "Adjusted exact broker exit fill"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entry.realized_pnl").value(closeTo(320.0, 0.01)));

        // Bob logs and closes a short trade to exercise short realized PnL
        MvcResult bobShort = mockMvc.perform(post("/api/trade/diary").session(bob).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "pair": "GBP/USD",
                      "side": "short",
                      "entry_price": 1.2700,
                      "stop_price": 1.2800,
                      "quantity": 10000,
                      "equity": 10000,
                      "entry_reason": "Short resistance rejection"
                    }
                    """))
            .andExpect(status().isCreated())
            .andReturn();
        long bobTradeId = com.jayway.jsonpath.JsonPath.read(
            bobShort.getResponse().getContentAsString(), "$.entry.id"
        ) instanceof Number n2 ? n2.longValue() : 2L;

        mockMvc.perform(post("/api/trade/diary/" + bobTradeId + "/close").session(bob).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"close_reason\":\"Target reached\",\"close_price\":1.2550,\"equity\":10000}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entry.realized_pnl").value(closeTo(150.0, 0.01)));
    }

    private void register(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk());
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }
}
