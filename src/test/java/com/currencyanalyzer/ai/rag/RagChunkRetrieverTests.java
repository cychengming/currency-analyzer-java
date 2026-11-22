package com.currencyanalyzer.ai.rag;

import com.currencyanalyzer.ai.knowledge.KnowledgeNote;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagChunkRetrieverTests {

    @Test
    void fusesVectorAndLexicalHitsAndDownRanksStubsAndChangelogs() {
        RagDocumentRepository repository = mock(RagDocumentRepository.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);

        UUID howardId = UUID.randomUUID();
        UUID stubId = UUID.randomUUID();
        UUID changelogId = UUID.randomUUID();

        when(repository.isPgVectorAvailable()).thenReturn(true);
        when(embeddingModel.embed("Howard Marks")).thenReturn(new float[] {1f, 0f});
        when(repository.searchByVector(any(float[].class), anyDouble(), anyInt())).thenReturn(List.of(
            new RagChunk.RagSearchHit(stubId, "沃伦·巴菲特", "buffett.md", "upload", 0, "沃伦·巴菲特", "> 来源：[[哲学]]", 0.52),
            new RagChunk.RagSearchHit(howardId, "霍华德·马克斯", "howard.md", "upload", 0, "基本信息", "Howard Marks 是 Oaktree 创始人，专注周期与风险控制。", 0.60),
            new RagChunk.RagSearchHit(changelogId, "更新日志", "changelog.md", "upload", 0, "2026-05-16", "新增 howard-marks 备忘录索引与归档说明内容。", 0.55)
        ));
        when(repository.findAllReadyChunks()).thenReturn(List.of(
            new RagDocumentRepository.ReadyChunk(howardId, "霍华德·马克斯", "howard.md", "upload", 0, "基本信息", "Howard Marks 是 Oaktree 创始人，从1990年起撰写投资备忘录，专注周期与风险控制。"),
            new RagDocumentRepository.ReadyChunk(howardId, "霍华德·马克斯", "howard.md", "upload", 1, "第二层次思维", "第二层次思维要求比市场共识想得更深，不仅判断公司是否优秀，还要评估价格是否已充分反映共识，从而在偏离时寻找不对称机会。")
        ));

        RagChunkRetriever retriever = new RagChunkRetriever(repository, embeddingModel, 0.45);
        List<RagChunk.RagSearchHit> hits = retriever.search("Howard Marks", 4);

        assertFalse(hits.isEmpty());
        assertEquals(howardId, hits.getFirst().documentId());
        assertTrue(hits.stream().anyMatch(hit -> "第二层次思维".equals(hit.heading())));
        assertTrue(hits.stream().noneMatch(hit -> stubId.equals(hit.documentId())));

        List<KnowledgeNote> notes = retriever.retrieveAsNotes("Howard Marks", 2);
        assertEquals(2, notes.size());
        assertTrue(notes.getFirst().title().contains("霍华德·马克斯"));
    }

    @Test
    void fallsBackToLexicalSearchWhenEmbeddingModelThrowsAndHandlesBlankQueries() {
        RagDocumentRepository repository = mock(RagDocumentRepository.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        UUID docId = UUID.randomUUID();

        when(repository.isPgVectorAvailable()).thenReturn(true);
        when(embeddingModel.embed(any(String.class))).thenThrow(new IllegalStateException("onnx failed"));
        when(repository.findAllReadyChunks()).thenReturn(List.of(
            new RagDocumentRepository.ReadyChunk(docId, "Kelly Sizing", "kelly.md", "upload", 0, "Kelly Sizing", "Kelly criterion optimizes position sizing under known edge.")
        ));

        RagChunkRetriever retriever = new RagChunkRetriever(repository, embeddingModel, 0.45);

        assertTrue(retriever.search(null, 3).isEmpty());
        assertTrue(retriever.search("   ", 3).isEmpty());
        assertTrue(retriever.search("kelly", 0).isEmpty());

        List<RagChunk.RagSearchHit> hits = retriever.search("kelly sizing", 3);
        assertEquals(1, hits.size());
        assertEquals("Kelly Sizing", hits.getFirst().displayTitle());
    }

    @Test
    void returnsVectorOnlyResultsWhenLexicalProducesNoMatches() {
        RagDocumentRepository repository = mock(RagDocumentRepository.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        UUID docId = UUID.randomUUID();

        when(repository.isPgVectorAvailable()).thenReturn(true);
        when(embeddingModel.embed("risk per trade")).thenReturn(new float[] {0.5f, 0.5f});
        when(repository.searchByVector(any(float[].class), anyDouble(), anyInt())).thenReturn(List.of(
            new RagChunk.RagSearchHit(docId, "仓位管理", "pos.md", "upload", 0, "核心原则", "单笔风险占账户总资金的比例不应超过百分之一，并结合历史波动率与ATR指标动态调整止损距离，避免连续回撤对本金造成不可逆伤害。", 0.66)
        ));
        when(repository.findAllReadyChunks()).thenReturn(List.of());

        RagChunkRetriever retriever = new RagChunkRetriever(repository, embeddingModel, 0.45);
        List<RagChunk.RagSearchHit> hits = retriever.search("risk per trade", 3);

        assertEquals(1, hits.size());
        assertEquals("仓位管理 — 核心原则", hits.getFirst().displayTitle());
    }
}
