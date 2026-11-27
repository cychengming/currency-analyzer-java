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
    void splitsLongPlainTextWithOverlap() {
        String text = ("Central bank divergence drives multi-month currency trends. ").repeat(12);

        DocumentParser.ParsedDocument parsed = parser.parse("macro.txt", text.getBytes(StandardCharsets.UTF_8), "Macro Notes");

        assertEquals("Macro Notes", parsed.title());
        assertTrue(parsed.chunks().size() >= 3);
        assertTrue(parsed.chunks().stream().allMatch(chunk -> chunk.content().length() <= 200));
    }

    @Test
    void rejectsUnsupportedExtensionsIncludingCsvAndBinaryPayloads() {
        assertFalse(parser.supports("report.pdf"));
        assertFalse(parser.supports("prices.csv"));
        assertTrue(parser.supports("notes.MD"));
        assertTrue(parser.supports("runbook.txt"));

        assertThrows(IllegalArgumentException.class, () ->
            parser.parse("report.pdf", "hello".getBytes(StandardCharsets.UTF_8), null)
        );
        assertThrows(IllegalArgumentException.class, () ->
            parser.parse("prices.csv", "date,close\n2026-11-20,1.08\n".getBytes(StandardCharsets.UTF_8), null)
        );
        assertThrows(IllegalArgumentException.class, () ->
            parser.parse("bad.txt", new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00, 0x01}, null)
        );
        assertThrows(IllegalArgumentException.class, () ->
            parser.parse("empty.md", new byte[0], null)
        );
        assertThrows(IllegalArgumentException.class, () ->
            parser.parse("blank.txt", "   \n\t  ".getBytes(StandardCharsets.UTF_8), null)
        );
    }

    @Test
    void handlesBomCodeFencesAndBreakPoints() {
        String mdWithBomAndFence = "\uFEFF# Top Guide\n\n```python\n# not a heading\nx = 1\n```\n\n"
            + "Paragraph one has enough detail to exceed the split limit cleanly.\n\n"
            + "Paragraph two also contains sentences. Second sentence here! Third sentence here?\n"
            + "SingleLineWithoutSpaces".repeat(12);

        DocumentParser.ParsedDocument parsed = parser.parse("folder/guide.markdown", mdWithBomAndFence.getBytes(StandardCharsets.UTF_8), null);
        assertEquals("Top Guide", parsed.title());
        assertTrue(parsed.chunks().size() >= 3);
        assertEquals("text/markdown; charset=utf-8", parser.inferContentType("guide.markdown"));
        assertEquals("text/plain; charset=utf-8", parser.inferContentType("notes.txt"));

        assertEquals("---\nunclosed front matter", TextDocumentParser.stripFrontMatter("---\nunclosed front matter"));
        assertEquals("", TextDocumentParser.stripFrontMatter("---\nk: v\n---"));
    }

    @Test
    void capsChineseChunksWithinTokenBudget() {
        TextDocumentParser cjkParser = new TextDocumentParser(520, 80, 50);
        String longChinese = "凯利公式用于衡量在胜率和赔率已知时单笔仓位占总资金的最优比例，结合波动率与回撤约束可以避免极端风险。".repeat(12);

        DocumentParser.ParsedDocument parsed = cjkParser.parse("kelly-zh.md", longChinese.getBytes(StandardCharsets.UTF_8), "凯利公式");

        assertTrue(parsed.chunks().size() >= 2);
        for (DocumentParser.ParsedChunk chunk : parsed.chunks()) {
            assertTrue(TextDocumentParser.weightedLength(chunk.content()) <= 520);
            assertTrue(chunk.content().length() <= 260);
        }
    }

    @Test
    void fullChineseChunkWithTitleAndLeadFitsEmbeddingWindow() {
        TextDocumentParser cjkParser = new TextDocumentParser(520, 80, 50);
        String title = "凯利公式与仓位管理".repeat(8);
        String body = """
            # %s

            本文讨论在已知胜率和赔率时如何限制单笔风险。

            ## 仓位
            %s

            ## 回撤
            %s
            """.formatted(
            title,
            "凯利公式用于衡量胜率和赔率已知时的最优仓位，并结合波动约束。".repeat(12),
            "回撤约束用来避免极端亏损，仓位必须留有安全边际。".repeat(12)
        );

        DocumentParser.ParsedDocument parsed = cjkParser.parse("kelly-zh.md", body.getBytes(StandardCharsets.UTF_8), null);
        String lead = RagIngestionService.extractDocumentLead(parsed.chunks());
        assertFalse(lead.isBlank());
        assertTrue(parsed.chunks().size() >= 2);

        for (DocumentParser.ParsedChunk chunk : parsed.chunks()) {
            String embedded = RagIngestionService.embeddingInput(parsed.title(), lead, chunk);
            assertTrue(
                TextDocumentParser.estimateTokens(embedded) <= TextDocumentParser.EMBEDDING_TOKEN_WINDOW,
                () -> "embedding length " + TextDocumentParser.estimateTokens(embedded)
            );
            assertTrue(embedded.contains(chunk.content()));
            assertTrue(TextDocumentParser.estimateTokens(chunk.content()) <= TextDocumentParser.MAX_CHUNK_TOKENS);
        }
    }
}
