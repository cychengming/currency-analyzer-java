package com.currencyanalyzer.ai.springai;

import com.currencyanalyzer.ai.tools.AssistantTools;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiMarketToolsTests {

    @Test
    void forwardsEachReadOnlyToolAndSkipsBlankFilters() {
        AssistantTools assistantTools = mock(AssistantTools.class);
        when(assistantTools.execute(anyString(), anyString())).thenReturn("{\"ok\":true}");
        SpringAiMarketTools tools = new SpringAiMarketTools(assistantTools);

        assertEquals("{\"ok\":true}", tools.getLiveRates());
        assertEquals("{\"ok\":true}", tools.getHistoricalSeries("EUR/USD", 5));
        assertEquals("{\"ok\":true}", tools.comparePairs("EUR/USD", "GBP/USD", 5));
        assertEquals("{\"ok\":true}", tools.getAlertHistory(null, null));
        assertEquals("{\"ok\":true}", tools.getAlertHistory(" ", 3));
        assertEquals("{\"ok\":true}", tools.getAlertPreferences(""));
        assertEquals("{\"ok\":true}", tools.getMonitoringState("EUR/USD"));
        assertEquals("{\"ok\":true}", tools.getSettings());
        assertEquals("{\"ok\":true}", tools.getLatestForecast(null));
        assertEquals("{\"ok\":true}", tools.searchNotes("position sizing"));

        verify(assistantTools).execute(eq(AssistantTools.GET_ALERT_HISTORY), eq("{}"));
        verify(assistantTools).execute(eq(AssistantTools.GET_ALERT_HISTORY), eq("{\"limit\":3}"));
        verify(assistantTools).execute(eq(AssistantTools.GET_MONITORING_STATE), eq("{\"pair\":\"EUR/USD\"}"));
        verify(assistantTools).execute(eq(AssistantTools.SEARCH_NOTES), eq("{\"query\":\"position sizing\"}"));
    }
}
