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
        assertEquals("{\"ok\":true}", tools.analyzePairTechnicals("EUR/USD", 90));
        assertEquals("{\"ok\":true}", tools.calculatePositionSize("EUR/USD", 10000.0, 1.0, 1.08, 1.10, 1.14));
        assertEquals("{\"ok\":true}", tools.getSettings());
        assertEquals("{\"ok\":true}", tools.searchNotes("position sizing"));
        assertEquals("{\"ok\":true}", tools.listMyAlertRules("EUR/USD", "ARMED"));
        assertEquals("{\"ok\":true}", tools.getMyAlertInbox(null, true, 5));
        assertEquals("{\"ok\":true}", tools.createAlertRule("EUR/USD", "cross_above", 1.12, "Breakout", null, "ONCE", true));
        assertEquals("{\"ok\":true}", tools.updateAlertRuleStatus("11111111-1111-1111-1111-111111111111", "PAUSED"));

        verify(assistantTools).execute(eq(AssistantTools.ANALYZE_PAIR_TECHNICALS), eq("{\"pair\":\"EUR/USD\",\"days\":90}"));
        verify(assistantTools).execute(
            eq(AssistantTools.CALCULATE_POSITION_SIZE),
            eq("{\"pair\":\"EUR/USD\",\"account_equity\":10000.0,\"risk_pct\":1.0,\"stop_loss_price\":1.08,\"entry_price\":1.1,\"take_profit_price\":1.14}")
        );
        verify(assistantTools).execute(eq(AssistantTools.SEARCH_NOTES), eq("{\"query\":\"position sizing\"}"));
        verify(assistantTools).execute(eq(AssistantTools.LIST_MY_ALERT_RULES), eq("{\"pair\":\"EUR/USD\",\"status\":\"ARMED\"}"));
        verify(assistantTools).execute(eq(AssistantTools.GET_MY_ALERT_INBOX), eq("{\"unread_only\":true,\"limit\":5}"));
        verify(assistantTools).execute(
            eq(AssistantTools.CREATE_ALERT_RULE),
            eq("{\"pair\":\"EUR/USD\",\"condition_type\":\"cross_above\",\"target_value\":1.12,\"nickname\":\"Breakout\",\"frequency\":\"ONCE\",\"notify_email\":true}")
        );
        verify(assistantTools).execute(
            eq(AssistantTools.UPDATE_ALERT_RULE_STATUS),
            eq("{\"rule_id\":\"11111111-1111-1111-1111-111111111111\",\"status\":\"PAUSED\"}")
        );
    }
}
