package com.currencyanalyzer.ai.rag;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextDocumentParserTests {

    private final TextDocumentParser parser = new TextDocumentParser(200, 40, 50);

    @Test
    void parsesMarkdownFrontMatterAndSplitsByHeadings() {
        String md = """
            ---
            tags: [risk, fx]
            ---
            # Risk Rules

            Always define invalidation before entering a trade, and record the exact thesis, stop level, and risk budget in your journal.

            ## Position Sizing

            Risk no more than 1% of account equity per position using ATR(14) stops.
            """;

        DocumentParser.ParsedDocument parsed = parser.parse("risk.md", md.getBytes(StandardCharsets.UTF_8), null);

        assertEquals("Risk Rules", parsed.title());
        assertEquals(2, parsed.chunks().size());
        assertEquals("Risk Rules", parsed.chunks().get(0).heading());
        assertEquals("Position Sizing", parsed.chunks().get(1).heading());
        assertTrue(parsed.chunks().get(1).content().contains("ATR(14)"));
    }

    @Test
    void mergesTinyMetadataStubIntoNextSectionAndSkipsTagOnlyHeading() {
        String md = """
            # 霍华德·马克斯投资哲学

            > 来源：Oaktree Capital 官网完整 PDF

            ## 基本信息

            - **全称**：Howard Marks（霍华德·马克斯）
            - **机构**：Oaktree Capital 联合创始人，擅长周期与信用风险分析。

            ## 标签

            #价值投资 #逆向投资
            """;

        DocumentParser.ParsedDocument parsed = parser.parse("howard.md", md.getBytes(StandardCharsets.UTF_8), null);

        assertEquals("霍华德·马克斯投资哲学", parsed.title());
        assertEquals(1, parsed.chunks().size());
        assertEquals("基本信息", parsed.chunks().getFirst().heading());
        assertTrue(parsed.chunks().getFirst().content().contains("Howard Marks"));
        assertTrue(parsed.chunks().getFirst().content().contains("Oaktree Capital"));
        assertFalse(parsed.chunks().getFirst().content().contains("#价值投资"));
    }

    @Test
    void repeatsCsvHeaderOnEveryChunk() {
        StringBuilder csv = new StringBuilder("pair,bias,note\n");
        for (int i = 1; i <= 25; i++) {
            csv.append("PAIR").append(i).append("/USD,bullish,Long observation row number ").append(i).append("\n");
        }

        DocumentParser.ParsedDocument parsed = parser.parse("watchlist.csv", csv.toString().getBytes(StandardCharsets.UTF_8), null);

        assertEquals("watchlist", parsed.title());
        assertTrue(parsed.chunks().size() > 1);
        for (DocumentParser.ParsedChunk chunk : parsed.chunks()) {
            assertTrue(chunk.content().startsWith("pair,bias,note\n"), "Every CSV chunk should include the header row");
        }
    }

    @Test
    void splitsLongPlainTextWithOverlap() {
        String text = ("Central bank divergence drives multi-month currency trends. ").repeat(12);

        DocumentParser.ParsedDocument parsed = parser.parse("macro.txt", text.getBytes(StandardCharsets.UTF_8), "Macro Notes");

        assertEquals("Macro Notes", parsed.title());
        assertTrue(parsed.chunks().size() >= 3);
        assertTrue(parsed.chunks().stream().allMatch(chunk -> chunk.content().length() <= 200));
    }

    @Test
    void rejectsUnsupportedExtensionsAndBinaryPayloads() {
        assertFalse(parser.supports("report.pdf"));
        assertTrue(parser.supports("notes.MD"));
        assertTrue(parser.supports("prices.csv"));

        assertThrows(IllegalArgumentException.class, () ->
            parser.parse("report.pdf", "hello".getBytes(StandardCharsets.UTF_8), null)
        );
        assertThrows(IllegalArgumentException.class, () ->
            parser.parse("bad.txt", new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00, 0x01}, null)
        );
        assertThrows(IllegalArgumentException.class, () ->
            parser.parse("empty.md", new byte[0], null)
        );
    }
}
