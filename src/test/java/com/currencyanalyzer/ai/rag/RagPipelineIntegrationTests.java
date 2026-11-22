package com.currencyanalyzer.ai.rag;

import com.currencyanalyzer.CurrencyAnalyzerApplication;
import com.currencyanalyzer.ai.knowledge.KnowledgeLibrary;
import com.currencyanalyzer.ai.knowledge.KnowledgeNote;
import com.currencyanalyzer.ai.knowledge.KnowledgeRetriever;
import com.currencyanalyzer.storage.ObjectStorageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = CurrencyAnalyzerApplication.class)
class RagPipelineIntegrationTests {

    @Autowired
    private RagDocumentService documentService;

    @Autowired
    private RagDocumentRepository repository;

    @Autowired
    private RagIngestionService ingestionService;

    @Autowired
    private RagChunkRetriever chunkRetriever;

    @Autowired
    private KnowledgeRetriever knowledgeRetriever;

    @Autowired
    private KnowledgeLibrary knowledgeLibrary;

    @Autowired
    private ObjectStorageClient storageClient;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM rag_chunks");
        jdbcTemplate.update("DELETE FROM rag_documents");
        for (ObjectStorageClient.StoredObjectSummary item : storageClient.list("")) {
            storageClient.delete(item.key());
        }
    }

    @Test
    void uploadIngestsChunksAndMakesThemRetrievableThenDeletesCleanly() throws Exception {
        byte[] bytes = """
            # Carry Trade Checklist

            ## Funding Currencies
            JPY and CHF often act as low-yield funding currencies when interest rate differentials widen.
            """.getBytes(StandardCharsets.UTF_8);

        RagDocument uploaded = documentService.upload("carry-trade.md", bytes, null, "alice");
        RagDocument ready = awaitStatus(uploaded.id(), RagDocument.STATUS_READY);

        assertEquals(RagDocument.STATUS_READY, ready.status());
        assertTrue(ready.chunkCount() >= 1);
        assertTrue(storageClient.get(ready.storageKey()).isPresent());

        List<RagChunk.RagSearchHit> hits = chunkRetriever.search("funding currencies JPY carry trade", 3);
        assertFalse(hits.isEmpty());
        assertEquals("Carry Trade Checklist", hits.getFirst().documentTitle());

        List<KnowledgeNote> assistantNotes = knowledgeRetriever.retrieve("carry trade funding currencies", 3);
        assertTrue(assistantNotes.stream().anyMatch(note -> note.title().contains("Carry Trade Checklist")));

        assertTrue(documentService.delete(ready.id()));
        assertTrue(repository.findById(ready.id()).isEmpty());
        assertTrue(storageClient.get(ready.storageKey()).isEmpty());
        assertTrue(chunkRetriever.search("funding currencies JPY carry trade", 3).isEmpty());
    }

    @Test
    void syncFromStorageImportsNewObjectsAndSkipsUnchangedOnes() throws Exception {
        byte[] noteBytes = "# Soros Reflexivity\n\nFeedback loops between market prices and fundamentals create booms and busts.\n"
            .getBytes(StandardCharsets.UTF_8);
        storageClient.put("knowledge/concepts/soros-reflexivity.md", noteBytes, "text/markdown");
        storageClient.put("knowledge/concepts/ignored.png", new byte[] {1, 2, 3, 4}, "image/png");

        RagSyncSummary first = documentService.syncFromStorage("knowledge", "sync-user");
        assertEquals(2, first.scanned());
        assertEquals(1, first.queued());
        assertEquals(0, first.skipped());
        assertEquals(1, first.ignored());

        List<RagDocument> docs = documentService.list();
        assertEquals(1, docs.size());
        awaitStatus(docs.getFirst().id(), RagDocument.STATUS_READY);

        RagSyncSummary second = documentService.syncFromStorage("knowledge", "sync-user");
        assertEquals(2, second.scanned());
        assertEquals(0, second.queued());
        assertEquals(1, second.skipped());
        assertEquals(1, second.ignored());
    }

    @Test
    void builtinGuideSeederSeedsIdempotentlyAndProtectsAgainstDeletion() {
        BuiltinGuideSeeder seeder = new BuiltinGuideSeeder(knowledgeLibrary, repository, ingestionService, clock);

        int firstQueued = seeder.seedNow(false);
        assertTrue(firstQueued >= 1);

        int secondQueued = seeder.seedNow(false);
        assertEquals(0, secondQueued);

        List<RagDocument> builtins = repository.findBySource(RagDocument.SOURCE_BUILTIN);
        assertFalse(builtins.isEmpty());
        assertEquals(RagDocument.STATUS_READY, builtins.getFirst().status());
        assertTrue(builtins.getFirst().chunkCount() >= 1);

        UUID builtinId = builtins.getFirst().id();
        assertThrows(IllegalArgumentException.class, () -> documentService.delete(builtinId));
    }

    @Test
    void expandsStrongBilingualEntityMatchToCoreSectionsAndDropsUnrelatedNoise() throws Exception {
        byte[] howardBytes = """
            # 霍华德·马克斯投资哲学

            ## 基本信息
            - **全称**：Howard Marks（霍华德·马克斯）
            - **机构**：Oaktree Capital 联合创始人，从1990年起撰写投资备忘录，擅长周期与信用风险分析。
            - **核心信条**：最重要的投资知识来自周期和情绪，而非预测。

            ## 第二层次思维
            第一层次思维看表面好坏，第二层次思维看市场共识已经定价了什么，以及偏离共识时赔率是否足够不对称。

            ## 钟摆理论
            市场情绪像钟摆一样在极度乐观与极度悲观之间摆动，大多数时间停留在极端而不是中点，理解周期位置有助于控制下行风险。
            """.getBytes(StandardCharsets.UTF_8);

        byte[] changelogBytes = """
            # 更新日志

            ## 2026-05-16
            - 新增 howard-marks 备忘录摘要
            """.getBytes(StandardCharsets.UTF_8);

        RagDocument howard = documentService.upload("howard.md", howardBytes, null, "alice");
        RagDocument changelog = documentService.upload("changelog.md", changelogBytes, null, "alice");
        awaitStatus(howard.id(), RagDocument.STATUS_READY);
        awaitStatus(changelog.id(), RagDocument.STATUS_READY);

        List<RagChunk.RagSearchHit> hits = chunkRetriever.search("Howard Marks", 4);

        assertTrue(hits.size() >= 3);
        assertEquals("霍华德·马克斯投资哲学", hits.getFirst().documentTitle());
        assertTrue(hits.stream().anyMatch(hit -> "第二层次思维".equals(hit.heading()) || "钟摆理论".equals(hit.heading())));
        assertTrue(hits.stream().noneMatch(hit -> "更新日志".equals(hit.documentTitle())));
    }

    private RagDocument awaitStatus(UUID id, String expectedStatus) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Optional<RagDocument> current = repository.findById(id);
            if (current.isPresent() && expectedStatus.equals(current.get().status())) {
                return current.get();
            }
            Thread.sleep(25);
        }
        return repository.findById(id).orElseThrow();
    }
}
