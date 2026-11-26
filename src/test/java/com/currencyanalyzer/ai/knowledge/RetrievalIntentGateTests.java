package com.currencyanalyzer.ai.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalIntentGateTests {

    @Test
    void skipsPreRetrievalForPureOperationalMarketAndAlertCommands() {
        assertFalse(RetrievalIntentGate.shouldPreRetrieve(
            "What is the current EUR/USD rate?",
            "What is the current EUR/USD rate?"
        ));
        assertFalse(RetrievalIntentGate.shouldPreRetrieve(
            "Calculate RSI and MACD for GOLD/USD",
            "Calculate RSI and MACD for GOLD/USD"
        ));
        assertFalse(RetrievalIntentGate.shouldPreRetrieve(
            "Calculate position size for EUR/USD with stop loss 1.08",
            "Calculate position size for EUR/USD with stop loss 1.08"
        ));
        assertFalse(RetrievalIntentGate.shouldPreRetrieve(
            "Compare EUR/USD and GBP/USD correlation",
            "Compare EUR/USD and GBP/USD correlation"
        ));
        assertFalse(RetrievalIntentGate.shouldPreRetrieve("List my alert rules", "List my alert rules"));
        assertFalse(RetrievalIntentGate.shouldPreRetrieve("Check my unread alert inbox", "Check my unread alert inbox"));
        assertFalse(RetrievalIntentGate.shouldPreRetrieve(
            "Create alert for EUR/USD cross above 1.12",
            "Create alert for EUR/USD cross above 1.12"
        ));
        assertFalse(RetrievalIntentGate.shouldPreRetrieve("暂停预警", "暂停预警"));
        assertFalse(RetrievalIntentGate.shouldPreRetrieve("ignored", "   "));
        assertFalse(RetrievalIntentGate.shouldPreRetrieve("ignored", null));
    }

    @Test
    void keepsPreRetrievalForConceptualDocumentAndFollowUpQuestions() {
        assertTrue(RetrievalIntentGate.shouldPreRetrieve("Explain a moving average", "Explain a moving average"));
        assertTrue(RetrievalIntentGate.shouldPreRetrieve("请用凯利公式解释仓位", "请用凯利公式解释仓位"));
        assertTrue(RetrievalIntentGate.shouldPreRetrieve(
            "what about his risk view?",
            "Howard Marks. what about his risk view?"
        ));
        assertTrue(RetrievalIntentGate.shouldPreRetrieve(
            "how to configure threshold alerts",
            "how to configure threshold alerts"
        ));
        assertTrue(RetrievalIntentGate.shouldPreRetrieve(
            "EUR/USD GBP/USD 相关性 敞口",
            "EUR/USD GBP/USD 相关性 敞口"
        ));
        assertTrue(RetrievalIntentGate.shouldPreRetrieve(
            "Explain EUR/USD carry trade theory",
            "Explain EUR/USD carry trade theory"
        ));
        assertTrue(RetrievalIntentGate.shouldPreRetrieve(null, "fallback query"));
    }
}
