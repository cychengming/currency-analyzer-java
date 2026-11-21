package com.currencyanalyzer.ai.tools;

import com.currencyanalyzer.marketdata.MarketDataService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

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
        assertFalse(AssistantTools.secretSetting("alert_email"));
    }
}
