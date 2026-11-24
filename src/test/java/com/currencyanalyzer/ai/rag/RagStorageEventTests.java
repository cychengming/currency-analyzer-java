package com.currencyanalyzer.ai.rag;

import com.currencyanalyzer.ai.knowledge.KnowledgeLibrary;
import com.currencyanalyzer.ai.rag.RagDocumentService.StorageEventResult;
import com.currencyanalyzer.ai.rag.RagDocumentService.StorageObjectEvent;
import com.currencyanalyzer.storage.ObjectStorageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagStorageEventTests {

    private static final Instant NOW = Instant.parse("2026-11-24T15:00:00Z");

    @Mock
    private RagDocumentRepository repository;
    @Mock
    private ObjectStorageClient storageClient;
    @Mock
    private RagIngestionService ingestionService;
    @Mock
    private KnowledgeLibrary knowledgeLibrary;

    private RagDocumentService service;

    @BeforeEach
    void setUp() {
        lenient().when(storageClient.providerName()).thenReturn("gcs");
        lenient().when(storageClient.bucketName()).thenReturn("currency-analyze");
        service = new RagDocumentService(
            repository,
            storageClient,
            new TextDocumentParser(155, 80, 500),
            ingestionService,
            knowledgeLibrary,
            Clock.fixed(NOW, ZoneOffset.UTC),
            "knowledge",
            1024
        );
    }

    @Test
    void validatesEventAndEnforcesBucketAndPrefixBoundaries() {
        assertThatThrownBy(() -> service.handleStorageObjectEvent(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.handleStorageObjectEvent(new StorageObjectEvent("", "currency-analyze", "knowledge/a.md", "1", null)))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(service.handleStorageObjectEvent(new StorageObjectEvent("OBJECT_FINALIZE", "other-bucket", "knowledge/a.md", "1", null)))
            .extracting(StorageEventResult::status, StorageEventResult::reason)
            .containsExactly("ignored", "bucket_mismatch");

        assertThat(service.handleStorageObjectEvent(new StorageObjectEvent("OBJECT_FINALIZE", "currency-analyze", "knowledge-other/a.md", "1", null)))
            .extracting(StorageEventResult::status, StorageEventResult::reason)
            .containsExactly("ignored", "outside_prefix");

        assertThat(service.handleStorageObjectEvent(new StorageObjectEvent("OBJECT_FINALIZE", "currency-analyze", "knowledge/../secret.md", "1", null)))
            .extracting(StorageEventResult::status, StorageEventResult::reason)
            .containsExactly("ignored", "outside_prefix");

        assertThat(service.handleStorageObjectEvent(new StorageObjectEvent("OBJECT_FINALIZE", "currency-analyze", "knowledge/sub/", "1", null)))
            .extracting(StorageEventResult::status, StorageEventResult::reason)
            .containsExactly("ignored", "outside_prefix");

        assertThat(service.handleStorageObjectEvent(new StorageObjectEvent("OBJECT_METADATA_UPDATE", "currency-analyze", "knowledge/a.md", "1", null)))
            .extracting(StorageEventResult::status, StorageEventResult::reason)
            .containsExactly("ignored", "unsupported_event_type");

        assertThat(service.handleStorageObjectEvent(new StorageObjectEvent("OBJECT_FINALIZE", "currency-analyze", "knowledge/a.exe", "1", null)))
            .extracting(StorageEventResult::status, StorageEventResult::reason)
            .containsExactly("ignored", "unsupported_file_type");
    }

    @Test
    void indexesNewObjectReindexesNewerGenerationAndSkipsStaleOrUnchangedEvents() {
        byte[] contentV1 = "# Macro Note\n\nCentral bank rate hike analysis.\n".getBytes(StandardCharsets.UTF_8);
        String shaV1 = RagDocumentService.sha256Hex(contentV1);

        when(repository.findByStorage("gcs", "knowledge/notes/macro.md")).thenReturn(Optional.empty());
        when(storageClient.get("knowledge/notes/macro.md")).thenReturn(Optional.of(contentV1));

        StorageEventResult created = service.handleStorageObjectEvent(
            new StorageObjectEvent("OBJECT_FINALIZE", "currency-analyze", "knowledge/notes/macro.md", "200", null)
        );
        assertThat(created.status()).isEqualTo("indexed");
        assertThat(created.documentId()).isNotNull();
        verify(repository).insert(any(RagDocument.class));
        verify(ingestionService).ingestNow(eq(created.documentId()), eq(shaV1), eq("macro.md"), isNull(), eq(contentV1));

        UUID existingId = created.documentId();
        RagDocument readyV1 = sampleDoc(existingId, "knowledge/notes/macro.md", shaV1, "200", RagDocument.STATUS_READY, RagDocument.SOURCE_UPLOAD);
        when(repository.findByStorage("gcs", "knowledge/notes/macro.md")).thenReturn(Optional.of(readyV1));

        // Same generation already READY -> skipped without downloading from GCS
        StorageEventResult sameGen = service.handleStorageObjectEvent(
            new StorageObjectEvent("OBJECT_FINALIZE", "currency-analyze", "knowledge/notes/macro.md", "200", null)
        );
        assertThat(sameGen.status()).isEqualTo("skipped");
        assertThat(sameGen.reason()).isEqualTo("already_indexed_or_in_progress");

        // Older stale generation -> skipped
        StorageEventResult staleGen = service.handleStorageObjectEvent(
            new StorageObjectEvent("OBJECT_FINALIZE", "currency-analyze", "knowledge/notes/macro.md", "150", null)
        );
        assertThat(staleGen.status()).isEqualTo("skipped");
        assertThat(staleGen.reason()).isEqualTo("stale_generation");

        // Newer generation with identical bytes -> unchanged_content
        StorageEventResult unchanged = service.handleStorageObjectEvent(
            new StorageObjectEvent("OBJECT_FINALIZE", "currency-analyze", "knowledge/notes/macro.md", "300", null)
        );
        assertThat(unchanged.status()).isEqualTo("skipped");
        assertThat(unchanged.reason()).isEqualTo("unchanged_content");

        // Newer generation with updated bytes -> updates and re-ingests synchronously
        byte[] contentV2 = "# Macro Note v2\n\nUpdated yield curve guidance.\n".getBytes(StandardCharsets.UTF_8);
        String shaV2 = RagDocumentService.sha256Hex(contentV2);
        when(storageClient.get("knowledge/notes/macro.md")).thenReturn(Optional.of(contentV2));

        StorageEventResult updated = service.handleStorageObjectEvent(
            new StorageObjectEvent("OBJECT_FINALIZE", "currency-analyze", "knowledge/notes/macro.md", "301", null)
        );
        assertThat(updated.status()).isEqualTo("indexed");
        assertThat(updated.documentId()).isEqualTo(existingId);
        verify(repository).updateForReindex(eq(existingId), eq("macro.md"), anyString(), anyString(), anyLong(), eq(shaV2), eq("301"), eq(NOW));
        verify(ingestionService).ingestNow(eq(existingId), eq(shaV2), eq("macro.md"), isNull(), eq(contentV2));
    }

    @Test
    void handlesMissingOversizedAndUnparseableFinalizeObjects() {
        when(repository.findByStorage("gcs", "knowledge/missing.md")).thenReturn(Optional.empty());
        when(storageClient.get("knowledge/missing.md")).thenReturn(Optional.empty());
        assertThat(service.handleStorageObjectEvent(new StorageObjectEvent("OBJECT_FINALIZE", "currency-analyze", "knowledge/missing.md", "1", null)))
            .extracting(StorageEventResult::status, StorageEventResult::reason)
            .containsExactly("skipped", "object_not_found");

        when(repository.findByStorage("gcs", "knowledge/huge.md")).thenReturn(Optional.empty());
        when(storageClient.get("knowledge/huge.md")).thenReturn(Optional.of(new byte[2048]));
        assertThat(service.handleStorageObjectEvent(new StorageObjectEvent("OBJECT_FINALIZE", "currency-analyze", "knowledge/huge.md", "1", null)))
            .extracting(StorageEventResult::status, StorageEventResult::reason)
            .containsExactly("ignored", "invalid_file_size");

        when(repository.findByStorage("gcs", "knowledge/blank.md")).thenReturn(Optional.empty());
        when(storageClient.get("knowledge/blank.md")).thenReturn(Optional.of("   \n\t ".getBytes(StandardCharsets.UTF_8)));
        assertThat(service.handleStorageObjectEvent(new StorageObjectEvent("OBJECT_FINALIZE", "currency-analyze", "knowledge/blank.md", "1", null)))
            .extracting(StorageEventResult::status, StorageEventResult::reason)
            .containsExactly("ignored", "unparseable_content");
    }

    @Test
    void prunesDeletedObjectsWhileGuardingOverwritesStaleGenerationsAndBuiltins() {
        UUID docId = UUID.randomUUID();
        RagDocument doc = sampleDoc(docId, "knowledge/notes/old.md", "abc", "500", RagDocument.STATUS_READY, RagDocument.SOURCE_UPLOAD);

        // Overwrite notification carries overwrittenByGeneration -> never delete
        assertThat(service.handleStorageObjectEvent(new StorageObjectEvent("OBJECT_ARCHIVE", "currency-analyze", "knowledge/notes/old.md", "500", "501")))
            .extracting(StorageEventResult::status, StorageEventResult::reason)
            .containsExactly("skipped", "overwritten_by_newer_generation");

        // Missing in DB -> skip
        when(repository.findByStorage("gcs", "knowledge/notes/missing.md")).thenReturn(Optional.empty());
        assertThat(service.handleStorageObjectEvent(new StorageObjectEvent("OBJECT_DELETE", "currency-analyze", "knowledge/notes/missing.md", "500", null)))
            .extracting(StorageEventResult::status, StorageEventResult::reason)
            .containsExactly("skipped", "document_not_found");

        // Builtin guide -> protected
        UUID builtinId = UUID.randomUUID();
        RagDocument builtin = sampleDoc(builtinId, "knowledge/guides/user-guide.md", "abc", "1", RagDocument.STATUS_READY, RagDocument.SOURCE_BUILTIN);
        when(repository.findByStorage("gcs", "knowledge/guides/user-guide.md")).thenReturn(Optional.of(builtin));
        assertThat(service.handleStorageObjectEvent(new StorageObjectEvent("OBJECT_DELETE", "currency-analyze", "knowledge/guides/user-guide.md", "1", null)))
            .extracting(StorageEventResult::status, StorageEventResult::reason)
            .containsExactly("skipped", "builtin_protected");

        // Stale delete generation (499 != stored 500) -> skip
        when(repository.findByStorage("gcs", "knowledge/notes/old.md")).thenReturn(Optional.of(doc));
        assertThat(service.handleStorageObjectEvent(new StorageObjectEvent("OBJECT_DELETE", "currency-analyze", "knowledge/notes/old.md", "499", null)))
            .extracting(StorageEventResult::status, StorageEventResult::reason)
            .containsExactly("skipped", "stale_generation");
        verify(repository, never()).deleteById(docId);

        // Matching generation -> cancels pending ingestion and deletes row
        when(repository.deleteById(docId)).thenReturn(true);
        StorageEventResult deleted = service.handleStorageObjectEvent(
            new StorageObjectEvent("OBJECT_DELETE", "currency-analyze", "knowledge/notes/old.md", "500", null)
        );
        assertThat(deleted.status()).isEqualTo("deleted");
        assertThat(deleted.documentId()).isEqualTo(docId);
        verify(ingestionService).cancelPending(docId);
        verify(repository).deleteById(docId);
    }

    private static RagDocument sampleDoc(
        UUID id,
        String key,
        String sha256,
        String generation,
        String status,
        String sourceType
    ) {
        return new RagDocument(
            id,
            sourceType,
            "macro.md",
            "Macro",
            "text/markdown",
            64,
            sha256,
            "gcs",
            "currency-analyze",
            key,
            generation,
            status,
            1,
            null,
            "pubsub-gcs",
            NOW,
            NOW
        );
    }
}
