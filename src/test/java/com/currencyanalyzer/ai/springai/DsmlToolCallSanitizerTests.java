package com.currencyanalyzer.ai.springai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DsmlToolCallSanitizerTests {

    @Test
    void extractsAndStripsMalformedDoubledFullwidthPipeDsmlCalls() {
        String leaked = """
            抱歉，上一条消息里的工具调用格式出错了，没有真正执行。我重新查一遍。

            <｜｜DSML｜｜ calls>
            <｜｜DSML｜｜ invoke name="get_live_rates">

            </｜｜DSML｜｜ invoke>
            <｜｜DSML｜｜ invoke name="get_settings">

            </｜｜DSML｜｜ invoke>
            </｜｜DSML｜｜ calls>""";

        assertTrue(DsmlToolCallSanitizer.containsDsml(leaked));

        List<AssistantMessage.ToolCall> calls = DsmlToolCallSanitizer.extractToolCalls(
            leaked,
            Set.of("get_live_rates", "get_settings")
        );
        assertEquals(2, calls.size());
        assertEquals("get_live_rates", calls.get(0).name());
        assertEquals("{}", calls.get(0).arguments());
        assertEquals("get_settings", calls.get(1).name());
        assertEquals("{}", calls.get(1).arguments());

        String cleaned = DsmlToolCallSanitizer.stripDsml(leaked);
        assertEquals("抱歉，上一条消息里的工具调用格式出错了，没有真正执行。我重新查一遍。", cleaned);
        assertFalse(DsmlToolCallSanitizer.containsDsml(cleaned));
    }

    @Test
    void extractsTypedParametersAndFiltersUnknownTools() {
        String dsml = """
            <｜DSML｜function_calls>
              <｜DSML｜invoke name="get_historical_series">
                <｜DSML｜parameter name="pair" string="true">EUR/USD</｜DSML｜parameter>
                <｜DSML｜parameter name="days" string="false">30</｜DSML｜parameter>
              </｜DSML｜invoke>
              <｜DSML｜invoke name="unknown_tool">
                <｜DSML｜parameter name="x" string="true">1</｜DSML｜parameter>
              </｜DSML｜invoke>
            </｜DSML｜function_calls>""";

        List<AssistantMessage.ToolCall> calls = DsmlToolCallSanitizer.extractToolCalls(
            dsml,
            Set.of("get_historical_series")
        );
        assertEquals(1, calls.size());
        assertEquals("get_historical_series", calls.getFirst().name());
        assertEquals("{\"pair\":\"EUR/USD\",\"days\":30}", calls.getFirst().arguments());
    }

    @Test
    void streamFilterForwardsNormalTextAndSuppressesChunkedDsml() {
        String stream = "Checking EUR/USD (< 1.10) now.\n<｜｜DSML｜｜ calls>\n"
            + "<｜｜DSML｜｜ invoke name=\"get_live_rates\"></｜｜DSML｜｜ invoke>\n"
            + "</｜｜DSML｜｜ calls>\nDone.";

        DsmlToolCallSanitizer.StreamFilter filter = new DsmlToolCallSanitizer.StreamFilter();
        StringBuilder emitted = new StringBuilder();
        for (int i = 0; i < stream.length(); i += 3) {
            String chunk = stream.substring(i, Math.min(stream.length(), i + 3));
            filter.accept(chunk, emitted::append);
        }
        filter.finish(emitted::append);

        assertTrue(filter.sawDsml());
        assertEquals("Checking EUR/USD (< 1.10) now.\n\nDone.", emitted.toString());
    }

    @Test
    void streamFilterDropsTruncatedDsmlAtEndOfStreamAndPreservesOrdinaryAngleBrackets() {
        DsmlToolCallSanitizer.StreamFilter truncated = new DsmlToolCallSanitizer.StreamFilter();
        StringBuilder truncatedOut = new StringBuilder();
        truncated.accept("Rate is 1.09 <｜DSML", truncatedOut::append);
        truncated.finish(truncatedOut::append);
        assertTrue(truncated.sawDsml());
        assertEquals("Rate is 1.09 ", truncatedOut.toString());

        DsmlToolCallSanitizer.StreamFilter ordinary = new DsmlToolCallSanitizer.StreamFilter();
        StringBuilder ordinaryOut = new StringBuilder();
        ordinary.accept("If price < 1.08 or > 1.12, alert.", ordinaryOut::append);
        ordinary.finish(ordinaryOut::append);
        assertFalse(ordinary.sawDsml());
        assertEquals("If price < 1.08 or > 1.12, alert.", ordinaryOut.toString());
    }

    @Test
    void handlesEdgeCasesOrphanInvokeUnquotedAttributesAndBlankInputs() {
        assertFalse(DsmlToolCallSanitizer.containsDsml(null));
        assertFalse(DsmlToolCallSanitizer.containsDsml("plain text <tag>"));
        assertEquals("", DsmlToolCallSanitizer.stripDsml(null));
        assertEquals("", DsmlToolCallSanitizer.stripDsml(""));
        assertEquals("clean", DsmlToolCallSanitizer.stripDsml("clean"));
        assertTrue(DsmlToolCallSanitizer.extractToolCalls(null, Set.of()).isEmpty());
        assertTrue(DsmlToolCallSanitizer.extractToolCalls("", Set.of()).isEmpty());

        String orphan = "Intro\r\n\r\n\r\n<|DSML|invoke name='create_alert_rule'>"
            + "<|DSML|parameter name=pair string=true>EUR/USD</|DSML|parameter>"
            + "<|DSML|parameter name=\"target_value\" string=\"false\">1.15</|DSML|parameter>"
            + "<|DSML|parameter name=\"note\" string=\"false\">not-json</|DSML|parameter>"
            + "</|DSML|invoke></|DSML|calls>";
        List<AssistantMessage.ToolCall> calls = DsmlToolCallSanitizer.extractToolCalls(orphan, Set.of());
        assertEquals(1, calls.size());
        assertEquals("create_alert_rule", calls.getFirst().name());
        assertTrue(calls.getFirst().arguments().contains("\"pair\":\"EUR/USD\""));
        assertTrue(calls.getFirst().arguments().contains("\"target_value\":1.15"));
        assertTrue(calls.getFirst().arguments().contains("\"note\":\"not-json\""));
        assertEquals("Intro", DsmlToolCallSanitizer.stripDsml(orphan));

        String unclosedWrapper = "Before\n<｜DSML｜calls><｜DSML｜invoke name=\"get_live_rates\">";
        assertEquals("Before", DsmlToolCallSanitizer.stripDsml(unclosedWrapper));
        assertEquals(1, DsmlToolCallSanitizer.extractToolCalls(unclosedWrapper, Set.of("get_live_rates")).size());
    }
}
