package com.currencyanalyzer.storage;

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GcsObjectStorageClientTests {

    @Test
    void putsGetsListsAndDeletesObjectsThroughGcsSdk() {
        Storage storage = mock(Storage.class);
        Blob created = mock(Blob.class);
        when(created.getGeneration()).thenReturn(42L);
        when(created.getSize()).thenReturn(5L);
        when(storage.create(any(BlobInfo.class), any(byte[].class))).thenReturn(created);

        Blob existing = mock(Blob.class);
        when(existing.exists()).thenReturn(true);
        when(existing.getContent()).thenReturn("hello".getBytes(StandardCharsets.UTF_8));
        when(storage.get(BlobId.of("currency-analyze", "knowledge/note.md"))).thenReturn(existing);
        when(storage.get(BlobId.of("currency-analyze", "knowledge/missing.md"))).thenReturn(null);
        when(storage.delete(BlobId.of("currency-analyze", "knowledge/note.md"))).thenReturn(true);

        Blob dirBlob = mock(Blob.class);
        when(dirBlob.isDirectory()).thenReturn(true);
        when(dirBlob.getName()).thenReturn("knowledge/");
        Blob fileBlob = mock(Blob.class);
        when(fileBlob.isDirectory()).thenReturn(false);
        when(fileBlob.getName()).thenReturn("knowledge/note.md");
        when(fileBlob.getSize()).thenReturn(5L);
        when(fileBlob.getContentType()).thenReturn("text/markdown");
        when(fileBlob.getGeneration()).thenReturn(42L);

        @SuppressWarnings("unchecked")
        Page<Blob> page = mock(Page.class);
        when(page.iterateAll()).thenReturn(List.of(dirBlob, fileBlob));
        when(storage.list(eq("currency-analyze"), any(Storage.BlobListOption.class))).thenReturn(page);
        when(storage.list("currency-analyze")).thenReturn(page);

        GcsObjectStorageClient client = new GcsObjectStorageClient("proj-1", "currency-analyze", () -> storage);

        assertEquals("gcs", client.providerName());
        assertEquals("currency-analyze", client.bucketName());

        ObjectStorageClient.StoredObject stored = client.put("/knowledge/note.md", "hello".getBytes(StandardCharsets.UTF_8), "text/markdown");
        assertEquals("knowledge/note.md", stored.key());
        assertEquals("42", stored.generation());
        assertEquals(5L, stored.sizeBytes());

        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), client.get("knowledge/note.md").orElseThrow());
        assertTrue(client.get("knowledge/missing.md").isEmpty());
        assertTrue(client.delete("knowledge/note.md"));

        List<ObjectStorageClient.StoredObjectSummary> prefixed = client.list("/knowledge");
        assertEquals(1, prefixed.size());
        assertEquals("knowledge/note.md", prefixed.getFirst().key());
        assertEquals(1, client.list("").size());
    }

    @Test
    void wrapsGcsSdkFailuresAndValidatesInputs() {
        Storage storage = mock(Storage.class);
        com.google.cloud.storage.StorageException sdkFailure = new com.google.cloud.storage.StorageException(403, "denied");
        when(storage.create(any(BlobInfo.class), any(byte[].class))).thenThrow(sdkFailure);
        when(storage.get(any(BlobId.class))).thenThrow(sdkFailure);
        when(storage.delete(any(BlobId.class))).thenThrow(sdkFailure);
        when(storage.list(eq("currency-analyze"))).thenThrow(sdkFailure);

        GcsObjectStorageClient client = new GcsObjectStorageClient("proj-1", "currency-analyze", () -> storage);

        assertThrows(StorageException.class, () -> client.put("a.md", new byte[] {1}, "text/plain"));
        assertThrows(StorageException.class, () -> client.get("a.md"));
        assertThrows(StorageException.class, () -> client.delete("a.md"));
        assertThrows(StorageException.class, () -> client.list(""));

        assertThrows(IllegalArgumentException.class, () -> client.put(" ", new byte[] {1}, "text/plain"));
        assertThrows(IllegalArgumentException.class, () -> client.get("../secret.txt"));

        GcsObjectStorageClient noBucket = new GcsObjectStorageClient("proj-1", " ", () -> storage);
        assertThrows(StorageException.class, () -> noBucket.list(""));
    }

    @Test
    void resolvesProxyFromEnvironmentAndSelectsStorageBean() {
        Map<String, String> env = Map.of("HTTPS_PROXY", "http://127.0.0.1:7890");
        Proxy proxy = GcsObjectStorageClient.resolveProxy(env::get);
        assertNotNull(proxy);
        assertEquals(new InetSocketAddress("127.0.0.1", 7890), proxy.address());

        Proxy bareHost = GcsObjectStorageClient.resolveProxy(Map.of("HTTP_PROXY", "127.0.0.1:8080")::get);
        assertNotNull(bareHost);
        assertNull(GcsObjectStorageClient.resolveProxy(key -> null));
        assertNull(GcsObjectStorageClient.resolveProxy(Map.of("HTTPS_PROXY", "http://127.0.0.1")::get));

        StorageConfiguration config = new StorageConfiguration();
        assertInstanceOf(LocalObjectStorageClient.class, config.objectStorageClient("local", Path.of(System.getProperty("java.io.tmpdir"), "rag-cfg-test").toString(), "", "", ""));
        assertInstanceOf(GcsObjectStorageClient.class, config.objectStorageClient("gcs", "", "proj", "bucket", ""));

        StorageException ex = new StorageException("boom");
        assertEquals("boom", ex.getMessage());
    }

    @Test
    void localStorageValidatesPathsAndListsEmptyDirectory(@TempDir Path tempDir) {
        LocalObjectStorageClient local = new LocalObjectStorageClient(tempDir.resolve("missing-sub").toString());
        assertEquals("local", local.providerName());
        assertEquals("missing-sub", local.bucketName());
        assertTrue(local.list("").isEmpty());
        assertTrue(local.get("nope.md").isEmpty());
        assertFalse(local.delete("nope.md"));

        local.put("a/one.md", "one".getBytes(StandardCharsets.UTF_8), "text/markdown");
        local.put("b/two.md", "two".getBytes(StandardCharsets.UTF_8), "text/markdown");
        assertEquals(1, local.list("a").size());
        assertEquals(2, local.list("").size());

        assertThrows(IllegalArgumentException.class, () -> local.get("../escape.md"));
        assertThrows(IllegalArgumentException.class, () -> local.put("", new byte[] {1}, "text/plain"));
    }
}
