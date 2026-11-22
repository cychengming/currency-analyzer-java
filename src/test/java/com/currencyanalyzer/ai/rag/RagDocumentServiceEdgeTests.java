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

        RagDocumentService.StorageInfo info = service.storageInfo();
        assertEquals("gcs", info.provider());
        assertEquals("currency-analyze", info.bucket());
        assertEquals("knowledge", info.defaultPrefix());
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
