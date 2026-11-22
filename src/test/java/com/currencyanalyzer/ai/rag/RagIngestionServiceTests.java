package com.currencyanalyzer.ai.rag;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagIngestionServiceTests {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void embedsChunksWithDocumentLeadContextWhenPgVectorIsAvailable() {
        RagDocumentRepository repository = mock(RagDocumentRepository.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        TextDocumentParser parser = new TextDocumentParser(150, 20, 50);

        when(repository.isPgVectorAvailable()).thenReturn(true);
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            List<String> inputs = invocation.getArgument(0);
            return inputs.stream().map(text -> new float[] {0.1f, 0.2f}).toList();
        });

        RagIngestionService service = new RagIngestionService(repository, parser, embeddingModel, clock);
        UUID docId = UUID.randomUUID();
        String md = """
            # Howard Marks Notes

            ## Profile
            Howard Marks is the co-founder of Oaktree Capital and writes famous memos on risk and market cycles.

            ## Second Level Thinking
            To outperform the consensus you must think differently and more accurately than the crowd about future outcomes.
            """;

        service.ingestNow(docId, "howard.md", null, md.getBytes(StandardCharsets.UTF_8));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RagDocumentRepository.ChunkRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).replaceChunks(eq(docId), eq("Howard Marks Notes"), captor.capture(), eq(clock.instant()));
        assertEquals(2, captor.getValue().size());
        assertNotNull(captor.getValue().getFirst().embedding());
        assertEquals(2, captor.getValue().getFirst().embedding().length);
        service.shutdown();
    }

    @Test
    void fallsBackToTextOnlyChunksWhenEmbeddingFailsAndMarksDocumentFailedOnBadInput() {
        RagDocumentRepository repository = mock(RagDocumentRepository.class);
        EmbeddingModel brokenModel = mock(EmbeddingModel.class);
        TextDocumentParser parser = new TextDocumentParser(300, 50, 50);

        when(repository.isPgVectorAvailable()).thenReturn(true);
        when(brokenModel.embed(anyList())).thenThrow(new IllegalStateException("download failed"));

        RagIngestionService service = new RagIngestionService(repository, parser, brokenModel, clock);
        UUID okId = UUID.randomUUID();
        service.ingestNow(okId, "note.txt", "My Note", "Valid plain text note about FX volatility.".getBytes(StandardCharsets.UTF_8));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RagDocumentRepository.ChunkRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).replaceChunks(eq(okId), eq("My Note"), captor.capture(), eq(clock.instant()));
        assertNull(captor.getValue().getFirst().embedding());

        UUID badId = UUID.randomUUID();
        service.ingestNow(badId, "bad.pdf", null, "not supported".getBytes(StandardCharsets.UTF_8));
        verify(repository).updateStatus(eq(badId), eq(RagDocument.STATUS_FAILED), eq(0), any(String.class), eq(clock.instant()));
        service.shutdown();
    }

    @Test
    void formatsVectorLiteralsAndImplementsValueSemanticsOnChunkRecord() {
        assertEquals("[1.0,0.0,-0.5]", RagDocumentRepository.toVectorLiteral(new float[] {1.0f, Float.NaN, -0.5f}));

        RagDocumentRepository.ChunkRecord a = new RagDocumentRepository.ChunkRecord(0, "H", "body", new float[] {1f, 2f});
        RagDocumentRepository.ChunkRecord b = new RagDocumentRepository.ChunkRecord(0, "H", "body", new float[] {1f, 2f});
        RagDocumentRepository.ChunkRecord c = new RagDocumentRepository.ChunkRecord(0, "H", "body", new float[] {1f, 3f});

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertTrue(a.toString().contains("embeddingDim=2"));
    }
}
