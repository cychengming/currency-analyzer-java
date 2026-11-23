package com.currencyanalyzer.ai.rag;

import com.currencyanalyzer.CurrencyAnalyzerApplication;
import com.currencyanalyzer.ai.knowledge.KnowledgeLibrary;
import com.currencyanalyzer.ai.knowledge.KnowledgeNote;
import com.currencyanalyzer.storage.ObjectStorageClient;
import com.currencyanalyzer.storage.StorageException;
import com.currencyanalyzer.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagDocumentServiceEdgeTests {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void rollsBackStorageObjectWhenDatabaseInsertFailsAndValidatesPayload() {
        RagDocumentRepository repository = mock(RagDocumentRepository.class);
        ObjectStorageClient storage = mock(ObjectStorageClient.class);
        RagIngestionService ingestion = mock(RagIngestionService.class);
        TextDocumentParser parser = new TextDocumentParser(600, 100, 50);
        KnowledgeLibrary library = new KnowledgeLibrary(List.of(
            new KnowledgeNote("knowledge/guides/user-guide.md", "User Guide", "# User Guide\nGuide body")
        ));

        when(storage.providerName()).thenReturn("gcs");
        when(storage.bucketName()).thenReturn("currency-analyze");
        when(storage.put(anyString(), any(byte[].class), anyString())).thenAnswer(inv ->
            new ObjectStorageClient.StoredObject("gcs", "currency-analyze", inv.getArgument(0), 10L, "text/markdown", "1")
        );
        when(repository.insert(any(RagDocument.class))).thenThrow(new IllegalStateException("db down"));

        RagDocumentService service = new RagDocumentService(
            repository, storage, parser, ingestion, library, clock, "knowledge", 100L
        );

        assertThrows(IllegalStateException.class, () ->
            service.upload("note.md", "# Title\nHello".getBytes(StandardCharsets.UTF_8), null, "alice")
        );
        verify(storage).delete(anyString());

        assertThrows(IllegalArgumentException.class, () ->
            service.upload("big.txt", "x".repeat(150).getBytes(StandardCharsets.UTF_8), null, "alice")
        );
        assertThrows(IllegalArgumentException.class, () ->
            service.upload("   ", "hello".getBytes(StandardCharsets.UTF_8), null, "alice")
        );
        assertEquals("safe_name.md", RagDocumentService.sanitizeFilename("../../safe:name.md"));

        RagDocumentService.StorageInfo info = service.storageInfo(true);
        assertEquals("gcs", info.provider());
        assertEquals("currency-analyze", info.bucket());
        assertEquals("knowledge", info.defaultPrefix());
        assertTrue(info.canManage());

        assertEquals("knowledge/", service.resolveScopedPrefix(null));
        assertEquals("knowledge/", service.resolveScopedPrefix("   "));
        assertEquals("knowledge/", service.resolveScopedPrefix("knowledge"));
        assertEquals("knowledge/concepts/", service.resolveScopedPrefix("knowledge/concepts"));
        assertThrows(IllegalArgumentException.class, () -> service.resolveScopedPrefix("knowledge-other"));
        assertThrows(IllegalArgumentException.class, () -> service.resolveScopedPrefix("other"));
        assertThrows(IllegalArgumentException.class, () -> service.resolveScopedPrefix("knowledge/../secret"));
    }

    @Test
    void downloadsBuiltinGuideAndChecksDownloadedDocumentEquality() {
        RagDocumentRepository repository = mock(RagDocumentRepository.class);
        ObjectStorageClient storage = mock(ObjectStorageClient.class);
        RagIngestionService ingestion = mock(RagIngestionService.class);
        TextDocumentParser parser = new TextDocumentParser(600, 100, 50);
        KnowledgeLibrary library = new KnowledgeLibrary(List.of(
            new KnowledgeNote("knowledge/guides/user-guide.md", "User Guide", "# User Guide\nGuide body")
        ));

        UUID id = UUID.randomUUID();
        RagDocument builtin = new RagDocument(
            id, RagDocument.SOURCE_BUILTIN, "user-guide.md", "User Guide", "text/markdown",
            24L, "sha", "classpath", null, "knowledge/guides/user-guide.md", null,
            RagDocument.STATUS_READY, 1, null, "system", clock.instant(), clock.instant()
        );
        when(repository.findById(id)).thenReturn(Optional.of(builtin));

        RagDocumentService service = new RagDocumentService(
            repository, storage, parser, ingestion, library, clock, "knowledge", 5_000_000L
        );

        RagDocumentService.DownloadedDocument first = service.download(id).orElseThrow();
        RagDocumentService.DownloadedDocument second = service.download(id).orElseThrow();
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, new RagDocumentService.DownloadedDocument(builtin, new byte[] {1}));
        assertTrue(first.toString().contains("bytesLength="));

        UUID missingId = UUID.randomUUID();
        when(repository.findById(missingId)).thenReturn(Optional.empty());
        assertTrue(service.download(missingId).isEmpty());
        assertTrue(service.reindex(missingId).isEmpty());
        assertEquals(false, service.delete(missingId));
    }

    @Test
    void syncUpdatesModifiedObjectsAndSkipsMissingOrInvalidOnes() {
        RagDocumentRepository repository = mock(RagDocumentRepository.class);
        ObjectStorageClient storage = mock(ObjectStorageClient.class);
        RagIngestionService ingestion = mock(RagIngestionService.class);
        TextDocumentParser parser = new TextDocumentParser(600, 100, 50);
        KnowledgeLibrary library = new KnowledgeLibrary(List.of());

        when(storage.providerName()).thenReturn("gcs");
        when(storage.bucketName()).thenReturn("currency-analyze");

        UUID existingId = UUID.randomUUID();
        RagDocument existingDoc = new RagDocument(
            existingId, RagDocument.SOURCE_UPLOAD, "existing.md", "Old Title", "text/markdown; charset=utf-8",
            20L, "old-sha", "gcs", "currency-analyze", "knowledge/existing.md", "1",
            RagDocument.STATUS_READY, 1, null, "alice", clock.instant(), clock.instant()
        );
        byte[] updatedBytes = "# Updated Title\nNew body content here.".getBytes(StandardCharsets.UTF_8);

        when(storage.list("knowledge/")).thenReturn(List.of(
            new ObjectStorageClient.StoredObjectSummary("knowledge/existing.md", updatedBytes.length, "text/markdown", "2"),
            new ObjectStorageClient.StoredObjectSummary("knowledge/missing.md", 10L, "text/markdown", "1"),
            new ObjectStorageClient.StoredObjectSummary("knowledge/binary.txt", 4L, "text/plain", "1"),
            new ObjectStorageClient.StoredObjectSummary("knowledge/huge.md", 99_999L, "text/markdown", "1")
        ));
        UUID removedId = UUID.randomUUID();
        RagDocument removedDoc = new RagDocument(
            removedId, RagDocument.SOURCE_UPLOAD, "deleted.md", "Deleted", "text/markdown; charset=utf-8",
            20L, "del-sha", "gcs", "currency-analyze", "knowledge/deleted.md", "1",
            RagDocument.STATUS_READY, 1, null, "alice", clock.instant(), clock.instant()
        );
        when(repository.findUploadsByPrefix("gcs", "knowledge/")).thenReturn(List.of(existingDoc, removedDoc));
        when(repository.deleteById(removedId)).thenReturn(true);
        when(repository.findByStorage("gcs", "knowledge/existing.md")).thenReturn(Optional.of(existingDoc));
        when(repository.findByStorage("gcs", "knowledge/missing.md")).thenReturn(Optional.empty());
        when(repository.findByStorage("gcs", "knowledge/binary.txt")).thenReturn(Optional.empty());
        when(storage.get("knowledge/existing.md")).thenReturn(Optional.of(updatedBytes));
        when(storage.get("knowledge/missing.md")).thenReturn(Optional.empty());
        when(storage.get("knowledge/binary.txt")).thenReturn(Optional.of(new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00, 0x01}));

        RagDocumentService service = new RagDocumentService(
            repository, storage, parser, ingestion, library, clock, "knowledge", 1000L
        );
        RagSyncSummary summary = service.syncFromStorage(null, "");
        assertEquals(4, summary.scanned());
        assertEquals(1, summary.queued());
        assertEquals(3, summary.ignored());
        assertEquals(1, summary.deleted());
        verify(repository).deleteById(removedId);

        RagStartupSyncRunner runner = new RagStartupSyncRunner(service);
        when(repository.findStuckDocuments()).thenReturn(List.of());
        runner.syncSafely();

        RagDocumentService failingService = mock(RagDocumentService.class);
        when(failingService.recoverStuckDocuments()).thenThrow(new StorageException("no creds"));
        new RagStartupSyncRunner(failingService).syncSafely();

        UUID missingBlobDocId = UUID.randomUUID();
        RagDocument missingBlobDoc = new RagDocument(
            missingBlobDocId, RagDocument.SOURCE_UPLOAD, "gone.md", "Gone", "text/markdown",
            10L, "sha", "gcs", "currency-analyze", "knowledge/gone.md", "1",
            RagDocument.STATUS_READY, 1, null, "alice", clock.instant(), clock.instant()
        );
        when(repository.findById(missingBlobDocId)).thenReturn(Optional.of(missingBlobDoc));
        when(storage.get("knowledge/gone.md")).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> service.reindex(missingBlobDocId));
    }

    @Test
    void handlesUploadLimitAndStorageExceptionsInApiExceptionHandler() {
        ApiExceptionHandler handler = new ApiExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/rag/documents");

        ResponseEntity<Map<String, Object>> tooLarge = handler.payloadTooLarge(
            new MaxUploadSizeExceededException(5_000_000L), request
        );
        assertEquals(HttpStatus.CONTENT_TOO_LARGE, tooLarge.getStatusCode());

        ResponseEntity<Map<String, Object>> storageErr = handler.storageError(
            new StorageException("GCS unreachable"), request
        );
        assertEquals(HttpStatus.BAD_GATEWAY, storageErr.getStatusCode());
        assertEquals("GCS unreachable", storageErr.getBody().get("error"));

        ResponseEntity<Map<String, Object>> forbidden = handler.forbidden(
            new org.springframework.security.access.AccessDeniedException("Admin required"), request
        );
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
        assertEquals("Admin required", forbidden.getBody().get("error"));

        com.currencyanalyzer.auth.UserRepository userRepository = mock(com.currencyanalyzer.auth.UserRepository.class);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(
            new com.currencyanalyzer.auth.UserAccount(1L, "admin", "hash", clock.instant())
        ));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(
            new com.currencyanalyzer.auth.UserAccount(2L, "alice", "hash", clock.instant())
        ));
        com.currencyanalyzer.auth.DatabaseUserDetailsService uds =
            new com.currencyanalyzer.auth.DatabaseUserDetailsService(userRepository, "Admin, ops");
        assertTrue(uds.loadUserByUsername("admin").getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())));
        assertTrue(uds.loadUserByUsername("alice").getAuthorities().stream()
            .noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())));
    }

    @Test
    void configuresProxySystemPropertiesFromEnvironment() {
        String prevHttpsHost = System.getProperty("https.proxyHost");
        String prevHttpsPort = System.getProperty("https.proxyPort");
        String prevHttpHost = System.getProperty("http.proxyHost");
        String prevHttpPort = System.getProperty("http.proxyPort");
        String prevNonProxy = System.getProperty("http.nonProxyHosts");
        try {
            System.clearProperty("https.proxyHost");
            System.clearProperty("https.proxyPort");
            System.clearProperty("http.proxyHost");
            System.clearProperty("http.proxyPort");
            System.clearProperty("http.nonProxyHosts");

            CurrencyAnalyzerApplication.configureProxyFromEnvironment(
                Map.of("HTTPS_PROXY", "http://127.0.0.1:7890")::get
            );
            assertEquals("127.0.0.1", System.getProperty("https.proxyHost"));
            assertEquals("7890", System.getProperty("https.proxyPort"));
            assertEquals("127.0.0.1", System.getProperty("http.proxyHost"));
            assertTrue(System.getProperty("http.nonProxyHosts").contains("localhost"));
        } finally {
            restoreProp("https.proxyHost", prevHttpsHost);
            restoreProp("https.proxyPort", prevHttpsPort);
            restoreProp("http.proxyHost", prevHttpHost);
            restoreProp("http.proxyPort", prevHttpPort);
            restoreProp("http.nonProxyHosts", prevNonProxy);
        }
    }

    private static void restoreProp(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
