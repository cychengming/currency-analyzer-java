package com.currencyanalyzer.ai.tools;

import com.currencyanalyzer.marketdata.MarketDataService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

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
            60
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
            60
        );

        String result = tools.execute("get_backtest_summary", "{}");

        assertTrue(result.contains("Unknown tool"));
    }
}
