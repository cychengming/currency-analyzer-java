package com.currencyanalyzer.ai.tools;

import com.currencyanalyzer.ai.knowledge.KnowledgeNote;
import com.currencyanalyzer.ai.knowledge.KnowledgeRetriever;
import com.currencyanalyzer.alerts.UserAlertRuleRepository;
import com.currencyanalyzer.alerts.UserAlertRuleService;
import com.currencyanalyzer.alerts.UserAlertRuleService.ProfileRequest;
import com.currencyanalyzer.auth.UserRepository;
import com.currencyanalyzer.marketdata.HistoricalPoint;
import com.currencyanalyzer.marketdata.LiveRate;
import com.currencyanalyzer.marketdata.MarketDataService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AssistantToolsTests {

    @Test
    void rejectsHistoryBeyondTheChatWindow() {
        MarketDataService marketDataService = mock(MarketDataService.class);
        AssistantTools tools = new AssistantTools(
            marketDataService,
            mock(JdbcTemplate.class),
            Clock.systemUTC(),
            60,
            null
        );

        String result = tools.execute("get_historical_series", "{\"pair\":\"EUR/USD\",\"days\":400}");

        assertTrue(result.contains("days must be between 1 and 365"));
        verifyNoInteractions(marketDataService);
    }

    @Test
    void rejectsAnUnknownTool() {
        AssistantTools tools = new AssistantTools(
            mock(MarketDataService.class),
            mock(JdbcTemplate.class),
            Clock.systemUTC(),
            60,
            null
        );

        String result = tools.execute("get_backtest_summary", "{}");

        assertTrue(result.contains("Unknown tool"));
    }

    @Test
    void rejectsAComparisonOutsideTheChatWindow() {
        MarketDataService marketDataService = mock(MarketDataService.class);
        AssistantTools tools = new AssistantTools(
            marketDataService,
            mock(JdbcTemplate.class),
            Clock.systemUTC(),
            60,
            null
        );

        String result = tools.execute(
            "compare_pairs",
            "{\"first\":\"EUR/USD\",\"second\":\"GBP/USD\",\"days\":400}"
        );

        assertTrue(result.contains("days must be between 1 and 365"));
        verifyNoInteractions(marketDataService);
    }

    @Test
    void omitsSecretSettings() {
        assertTrue(AssistantTools.secretSetting("smtp_password"));
        assertTrue(AssistantTools.secretSetting("deepseek_api_key"));
        assertTrue(AssistantTools.secretSetting("api-key"));
        assertTrue(AssistantTools.secretSetting(null));
        assertTrue(AssistantTools.secretSetting(" "));
        assertFalse(AssistantTools.secretSetting("alert_email"));
    }

    @Test
    void quotesLiveRatesAndADailyChange() {
        MarketDataService marketDataService = mock(MarketDataService.class);
        when(marketDataService.fetchLiveRates()).thenReturn(Map.of(
            "EUR/USD", new LiveRate(1.1, 0.01, 0.9)
        ));
        when(marketDataService.fetchHistorical(eq("EUR/USD"), eq(3))).thenReturn(List.of(
            new HistoricalPoint("2026-11-19", 0),
            new HistoricalPoint("2026-11-21", 1.2)
        ));
        when(marketDataService.fetchHistorical(eq("GBP/USD"), eq(3))).thenReturn(List.of());
        List<HistoricalPoint> longSeries = new java.util.ArrayList<>();
        for (int index = 0; index < 45; index++) {
            longSeries.add(new HistoricalPoint("2026-01-" + (index + 1), 1 + index));
        }
        when(marketDataService.fetchHistorical(eq("USD/JPY"), eq(45))).thenReturn(longSeries);
        AssistantTools tools = tools(marketDataService, mock(JdbcTemplate.class), null);

        String live = tools.execute("get_live_rates", "{}");
        String flatStart = tools.execute("get_historical_series", "{\"pair\":\"eur/usd\",\"days\":3}");
        String empty = tools.execute("get_historical_series", "{\"pair\":\"GBP/USD\",\"days\":3}");
        String sampled = tools.execute("get_historical_series", "{\"pair\":\"USD/JPY\",\"days\":45}");
        String compared = tools.execute(
            "compare_pairs",
            "{\"first\":\"EUR/USD\",\"second\":\"GBP/USD\",\"days\":3}"
        );

        assertTrue(live.contains("\"rate\":1.1"));
        assertTrue(flatStart.contains("\"change_percent\":0"));
        assertTrue(empty.contains("No daily closes"));
        assertTrue(sampled.contains("\"point_count\":45"));
        assertTrue(compared.contains("\"first\""));
        assertTrue(compared.contains("\"second\""));
    }

    @Test
    void readsSettingsSearchesNotesAndRejectsRetiredLegacyTools() {
        JdbcTemplate jdbc = database();
        jdbc.update("INSERT INTO settings (key, value) VALUES ('a_password', 'hidden')");
        jdbc.update("INSERT INTO settings (key, value) VALUES ('b_blank', NULL)");
        String longValue = "x".repeat(300);
        for (int index = 0; index < 41; index++) {
            jdbc.update("INSERT INTO settings (key, value) VALUES (?, ?)", "k" + index, longValue);
        }
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieve(anyString(), eq(4))).thenReturn(List.of());
        when(retriever.retrieve(eq("position sizing"), eq(4))).thenReturn(List.of(
            new KnowledgeNote("knowledge/concepts/size.md", "Position sizing", "x".repeat(900))
        ));
        AssistantTools tools = tools(mock(MarketDataService.class), jdbc, retriever);

        assertTrue(tools.execute("get_alert_history", "{}").contains("Unknown tool"));
        assertTrue(tools.execute("get_alert_preferences", "{}").contains("Unknown tool"));
        assertTrue(tools.execute("get_monitoring_state", "{}").contains("Unknown tool"));
        assertTrue(tools.execute("get_latest_forecast", "{}").contains("Unknown tool"));

        String settings = tools.execute("get_settings", " ");
        String notes = tools.execute("search_notes", "{\"query\":\"position sizing\"}");
        String noNotes = tools.execute("search_notes", "{\"query\":\"nothing\"}");

        assertTrue(settings.contains("secrets_omitted"));
        assertTrue(settings.contains("[truncated]"));
        assertFalse(settings.contains("hidden"));
        assertTrue(notes.contains("Position sizing"));
        assertTrue(noNotes.contains("No matching note"));
    }

    @Test
    void computesTechnicalsEnrichedComparisonAndPositionSizing() {
        MarketDataService marketDataService = mock(MarketDataService.class);
        List<HistoricalPoint> eur = new java.util.ArrayList<>();
        List<HistoricalPoint> gbp = new java.util.ArrayList<>();
        java.time.LocalDate start = java.time.LocalDate.of(2026, 8, 1);
        for (int i = 0; i < 60; i++) {
            String dt = start.plusDays(i).toString();
            eur.add(new HistoricalPoint(dt, 1.05 + i * 0.002 + (i % 2 == 0 ? 0.001 : 0.0)));
            gbp.add(new HistoricalPoint(dt, 1.25 + i * 0.003 + (i % 2 == 0 ? 0.0015 : 0.0)));
        }
        when(marketDataService.fetchHistorical(eq("EUR/USD"), eq(90))).thenReturn(eur);
        when(marketDataService.fetchHistorical(eq("EUR/USD"), eq(60))).thenReturn(eur);
        when(marketDataService.fetchHistorical(eq("GBP/USD"), eq(60))).thenReturn(gbp);
        when(marketDataService.fetchLiveRates()).thenReturn(Map.of(
            "EUR/USD", new LiveRate(1.10, 0.01, 0.91)
        ));
        JdbcTemplate jdbc = database();
        jdbc.update("INSERT INTO settings (key, value) VALUES ('daily_risk_limit_pct', '2.5')");

        AssistantTools tools = tools(marketDataService, jdbc, null);
        assertEquals(11, tools.definitions().size());

        String technicals = tools.execute(AssistantTools.ANALYZE_PAIR_TECHNICALS, "{\"pair\":\"EUR/USD\"}");
        assertTrue(technicals.contains("\"available\":true"));
        assertTrue(technicals.contains("\"rsi_14\""));
        assertTrue(technicals.contains("\"sma_20\""));
        assertTrue(technicals.contains("\"macd_12_26_9\""));
        assertTrue(technicals.contains("\"bollinger_bands_20\""));

        assertTrue(tools.execute(AssistantTools.ANALYZE_PAIR_TECHNICALS, "{\"pair\":\"EUR/USD\",\"days\":5}")
            .contains("days must be between 14 and 365"));

        String compared = tools.execute(
            AssistantTools.COMPARE_PAIRS,
            "{\"first\":\"EUR/USD\",\"second\":\"GBP/USD\",\"days\":60}"
        );
        assertTrue(compared.contains("\"pearson_correlation\""));
        assertTrue(compared.contains("\"relative_change_pct\""));

        String sizingLive = tools.execute(
            AssistantTools.CALCULATE_POSITION_SIZE,
            "{\"pair\":\"EUR/USD\",\"account_equity\":10000,\"risk_pct\":1.5,\"stop_loss_price\":1.08,\"take_profit_price\":1.14}"
        );
        assertTrue(sizingLive.contains("\"entry_source\":\"live_spot\""));
        assertTrue(sizingLive.contains("\"exceeds_daily_risk_limit\":false"));
        assertTrue(sizingLive.contains("\"reward_to_risk_ratio\":2.0"));

        String sizingExplicit = tools.execute(
            AssistantTools.CALCULATE_POSITION_SIZE,
            "{\"pair\":\"GOLD/USD\",\"account_equity\":10000,\"risk_pct\":3.5,\"entry_price\":2600,\"stop_loss_price\":2650}"
        );
        assertTrue(sizingExplicit.contains("\"entry_source\":\"user_specified\""));
        assertTrue(sizingExplicit.contains("\"direction\":\"short\""));
        assertTrue(sizingExplicit.contains("\"exceeds_daily_risk_limit\":true"));

        assertTrue(tools.execute(
            AssistantTools.CALCULATE_POSITION_SIZE,
            "{\"pair\":\"GOLD/USD\",\"account_equity\":10000,\"risk_pct\":1.0,\"stop_loss_price\":2500}"
        ).contains("live rate is unavailable"));
        assertTrue(tools.execute(
            AssistantTools.CALCULATE_POSITION_SIZE,
            "{\"pair\":\"EUR/USD\",\"risk_pct\":1.0,\"stop_loss_price\":1.08}"
        ).contains("account_equity is required"));
    }

    @Test
    void rejectsBadToolArgumentsAndFeedFailures() {
        MarketDataService marketDataService = mock(MarketDataService.class);
        when(marketDataService.fetchLiveRates()).thenThrow(new RestClientException("down"));
        AssistantTools tools = tools(marketDataService, mock(JdbcTemplate.class), null);
        KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieve(anyString(), eq(4))).thenReturn(List.of());
        AssistantTools withNotes = tools(marketDataService, database(), retriever);

        assertTrue(tools.execute("get_live_rates", "{}").contains("could not be reached"));
        doThrow(new IllegalStateException("boom")).when(marketDataService).fetchLiveRates();
        assertTrue(tools.execute("get_live_rates", "{}").contains("could not be completed"));
        JdbcTemplate broken = database();
        broken.execute("DROP TABLE settings");
        assertTrue(tools(marketDataService, broken, null).execute("get_settings", "{}")
            .contains("could not be read"));
        assertTrue(tools.execute("get_historical_series", "[").contains("not valid JSON"));
        assertTrue(tools.execute("get_historical_series", "[]").contains("JSON object"));
        assertTrue(tools.execute("get_historical_series", "{\"pair\":\"not-a-pair\",\"days\":3}")
            .contains("Invalid pair"));
        assertTrue(tools.execute("compare_pairs", "{\"first\":\"EUR/USD\",\"second\":\"EUR/USD\",\"days\":3}")
            .contains("two different pairs"));
        assertTrue(withNotes.execute("search_notes", "{\"query\":\" \"}").contains("query is required"));
        assertTrue(withNotes.execute("search_notes", "{\"query\":\"" + "q".repeat(600) + "\"}")
            .contains("No matching note"));
        assertTrue(tools.execute("search_notes", "{\"query\":\"kelly\"}").contains("unavailable"));
        assertTrue(tools.execute(null, null).contains("Unknown tool"));
    }

    private static AssistantTools tools(
        MarketDataService marketDataService,
        JdbcTemplate jdbcTemplate,
        KnowledgeRetriever knowledgeRetriever
    ) {
        return new AssistantTools(
            marketDataService,
            jdbcTemplate,
            Clock.fixed(Instant.parse("2026-11-21T10:00:00Z"), ZoneOffset.UTC),
            60,
            knowledgeRetriever
        );
    }

    private static JdbcTemplate database() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:assistant" + System.nanoTime() + ";NON_KEYWORDS=KEY,VALUE;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            CREATE TABLE alerts (
                pair VARCHAR(32),
                percent_change DOUBLE,
                old_rate DOUBLE,
                new_rate DOUBLE,
                timestamp TIMESTAMP WITH TIME ZONE,
                email_sent BOOLEAN,
                alert_type VARCHAR(40),
                trigger_value DOUBLE,
                threshold_value DOUBLE
            )
            """);
        jdbc.execute("""
            CREATE TABLE alert_preferences (
                pair VARCHAR(32),
                enabled BOOLEAN,
                alert_type VARCHAR(40),
                custom_threshold DOUBLE,
                custom_period INT,
                enable_trend_consistency BOOLEAN,
                lookback_years INT,
                price_high DOUBLE,
                price_low DOUBLE,
                trigger_type VARCHAR(40),
                volatility_type VARCHAR(40),
                ma_short_period INT,
                ma_long_period INT,
                signal_type VARCHAR(40)
            )
            """);
        jdbc.execute("""
            CREATE TABLE monitoring_state (
                pair VARCHAR(32),
                last_alert_time TIMESTAMP WITH TIME ZONE
            )
            """);
        jdbc.execute("CREATE TABLE settings (key VARCHAR(80), value VARCHAR(400))");
        jdbc.execute("""
            CREATE TABLE dl_forecast_runs (
                id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                created_at VARCHAR(40),
                model_type VARCHAR(40),
                lookback_days INT,
                horizon_days INT,
                asset VARCHAR(32),
                notes VARCHAR(200)
            )
            """);
        jdbc.execute("""
            CREATE TABLE asset_forecast_daily (
                run_id BIGINT,
                dt VARCHAR(20),
                predicted_close DOUBLE
            )
            """);
        jdbc.execute("""
            CREATE TABLE gold_forecast_daily (
                run_id BIGINT,
                dt VARCHAR(20),
                predicted_close DOUBLE
            )
            """);
        return jdbc;
    }

    @Test
    void managesUserScopedAlerts2RulesAndEnforcesAuthenticationIsolation() throws Exception {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
            "jdbc:h2:mem:assistant_alerts_" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;DB_CLOSE_DELAY=-1",
            "sa",
            "",
            true
        );
        try {
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

            Clock fixedClock = Clock.fixed(Instant.parse("2026-11-25T08:00:00Z"), ZoneOffset.UTC);
            JdbcClient jdbcClient = JdbcClient.create(dataSource);
            UserRepository userRepository = new UserRepository(jdbcClient, fixedClock);
            var alice = userRepository.create("alice", "hash-a");
            var bob = userRepository.create("bob", "hash-b");

            MarketDataService marketDataService = mock(MarketDataService.class);
            when(marketDataService.fetchLiveRates()).thenReturn(Map.of(
                "EUR/USD", new LiveRate(1.10, 0.01, 0.91),
                "GOLD/USD", new LiveRate(2650.0, 12.0, 0.45)
            ));
            UserAlertRuleRepository ruleRepository = new UserAlertRuleRepository(jdbcClient);
            UserAlertRuleService ruleService = new UserAlertRuleService(ruleRepository, marketDataService, fixedClock);
            ruleService.updateProfile(alice.id(), new ProfileRequest("alice@example.com", List.of("EUR/USD")));
            ruleRepository.insertUserAlert(
                alice.id(), null, "EUR breakout", "EUR/USD", 1.2, 1.09, 1.103,
                Instant.parse("2026-11-25T07:30:00Z"), "cross_above", 1.103, 1.10
            );

            AssistantTools tools = new AssistantTools(
                marketDataService,
                new JdbcTemplate(dataSource),
                fixedClock,
                60,
                null,
                ruleService,
                userRepository
            );
            ObjectMapper mapper = new ObjectMapper();

            ToolUserContext.clear();
            assertTrue(tools.execute(AssistantTools.LIST_MY_ALERT_RULES, "{}").contains("Authentication is required"));

            ToolUserContext.bind("alice");
            String createdJson = tools.execute(
                AssistantTools.CREATE_ALERT_RULE,
                "{\"pair\":\"EUR/USD\",\"condition_type\":\"cross_above\",\"target_value\":1.12,\"nickname\":\"EUR breakout\",\"frequency\":\"RECURRING\",\"notify_email\":true}"
            );
            JsonNode createdNode = mapper.readTree(createdJson);
            assertTrue(createdNode.path("created").asBoolean(), () -> "Unexpected create response: " + createdJson);
            String ruleId = createdNode.path("rule").path("id").asText();
            assertFalse(ruleId.isBlank());

            String listedJson = tools.execute(AssistantTools.LIST_MY_ALERT_RULES, "{\"pair\":\"EUR/USD\",\"status\":\"ARMED\"}");
            assertTrue(listedJson.contains("\"count\":1"));
            assertTrue(listedJson.contains("EUR breakout"));
            assertTrue(listedJson.contains("alice@example.com"));

            String inboxJson = tools.execute(AssistantTools.GET_MY_ALERT_INBOX, "{\"unread_only\":true,\"limit\":5}");
            assertTrue(inboxJson.contains("\"unread_count\":1"));
            assertTrue(inboxJson.contains("EUR/USD"));

            String pausedJson = tools.execute(
                AssistantTools.UPDATE_ALERT_RULE_STATUS,
                "{\"rule_id\":\"" + ruleId + "\",\"status\":\"PAUSED\"}"
            );
            assertTrue(pausedJson.contains("\"updated\":true"));
            assertTrue(pausedJson.contains("\"status\":\"PAUSED\""));

            assertTrue(tools.execute(AssistantTools.UPDATE_ALERT_RULE_STATUS, "{\"rule_id\":\"not-a-uuid\",\"status\":\"ARMED\"}")
                .contains("Invalid rule_id UUID"));
            assertTrue(tools.execute(AssistantTools.UPDATE_ALERT_RULE_STATUS, "{\"rule_id\":\"" + ruleId + "\"}")
                .contains("status is required"));
            assertTrue(tools.execute(AssistantTools.LIST_MY_ALERT_RULES, "{\"status\":\"INVALID\"}")
                .contains("status must be ARMED, TRIGGERED, or PAUSED"));
            assertTrue(tools.execute(AssistantTools.GET_MY_ALERT_INBOX, "{\"limit\":50}")
                .contains("limit must be between 1 and 20"));
            assertTrue(tools.execute(
                AssistantTools.CREATE_ALERT_RULE,
                "{\"pair\":\"GOLD/USD\",\"condition_type\":\"pct_move\",\"target_value\":\"2.5\",\"window_days\":\"14\",\"notify_email\":\"false\"}"
            ).contains("\"created\":true"));
            assertTrue(tools.execute(
                AssistantTools.CREATE_ALERT_RULE,
                "{\"pair\":\"GOLD/USD\",\"condition_type\":\"cross_above\",\"target_value\":\"bad\"}"
            ).contains("target_value must be a number"));
            assertTrue(tools.execute(
                AssistantTools.CREATE_ALERT_RULE,
                "{\"pair\":\"GOLD/USD\",\"condition_type\":\"pct_move\",\"target_value\":2.0,\"window_days\":\"bad\"}"
            ).contains("window_days must be an integer"));

            ToolUserContext.bind(" ");
            assertTrue(tools.execute(AssistantTools.LIST_MY_ALERT_RULES, "{}").contains("Authentication is required"));
            ToolUserContext.bind(null);
            assertTrue(tools.execute(AssistantTools.GET_MY_ALERT_INBOX, "{}").contains("Authentication is required"));

            ToolUserContext.bind(bob.username());
            assertTrue(tools.execute(AssistantTools.LIST_MY_ALERT_RULES, "{}").contains("\"count\":0"));
            assertTrue(tools.execute(
                AssistantTools.UPDATE_ALERT_RULE_STATUS,
                "{\"rule_id\":\"" + ruleId + "\",\"status\":\"ARMED\"}"
            ).contains("Alert rule not found"));
        } finally {
            ToolUserContext.clear();
            dataSource.destroy();
        }
    }
}
