package com.currencyanalyzer.ai.rag;

import com.currencyanalyzer.ai.knowledge.KnowledgeLibrary;
import com.currencyanalyzer.storage.ObjectStorageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class RagDocumentService {

    private static final Logger log = LoggerFactory.getLogger(RagDocumentService.class);

    private final RagDocumentRepository repository;
    private final ObjectStorageClient storageClient;
    private final DocumentParser documentParser;
    private final RagIngestionService ingestionService;
    private final KnowledgeLibrary knowledgeLibrary;
    private final Clock clock;
    private final String defaultPrefix;
    private final long maxFileSizeBytes;

    public RagDocumentService(
        RagDocumentRepository repository,
        ObjectStorageClient storageClient,
        DocumentParser documentParser,
        RagIngestionService ingestionService,
        KnowledgeLibrary knowledgeLibrary,
        Clock clock,
        @Value("${app.storage.gcs.prefix:knowledge}") String defaultPrefix,
        @Value("${app.rag.max-file-size-bytes:5242880}") long maxFileSizeBytes
    ) {
        this.repository = repository;
        this.storageClient = storageClient;
        this.documentParser = documentParser;
        this.ingestionService = ingestionService;
        this.knowledgeLibrary = knowledgeLibrary;
        this.clock = clock;
        this.defaultPrefix = normalizePrefixSegment(defaultPrefix);
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public RagDocument upload(String originalFilename, byte[] rawBytes, String titleOverride, String username) {
        String filename = sanitizeFilename(originalFilename);
        validatePayload(filename, rawBytes);
        DocumentParser.ParsedDocument preview = documentParser.parse(filename, rawBytes, titleOverride);

        UUID id = UUID.randomUUID();
        String contentType = documentParser.inferContentType(filename);
        String objectKey = buildUploadKey(id, filename);
        String sha256 = sha256Hex(rawBytes);

        ObjectStorageClient.StoredObject stored = storageClient.put(objectKey, rawBytes, contentType);
        Instant now = clock.instant();
        RagDocument document = new RagDocument(
            id,
            RagDocument.SOURCE_UPLOAD,
            filename,
            preview.title(),
            contentType,
            rawBytes.length,
            sha256,
            stored.provider(),
            stored.bucket(),
            stored.key(),
            stored.generation(),
            RagDocument.STATUS_PENDING,
            0,
            null,
            username == null || username.isBlank() ? "system" : username,
            now,
            now
        );

        try {
            repository.insert(document);
        } catch (RuntimeException exception) {
            safeDeleteFromStorage(stored.key());
            throw exception;
        }

        ingestionService.enqueue(id, sha256, filename, titleOverride, rawBytes);
        return document;
    }

    public RagSyncSummary syncFromStorage(String requestedPrefix, String username) {
        String scopedPrefix = resolveScopedPrefix(requestedPrefix);
        List<ObjectStorageClient.StoredObjectSummary> objects = storageClient.list(scopedPrefix);

        int queued = 0;
        int skipped = 0;
        int ignored = 0;
        Set<String> seenKeys = new HashSet<>();
        String actor = username == null || username.isBlank() ? "system" : username;

        for (ObjectStorageClient.StoredObjectSummary summary : objects) {
            seenKeys.add(summary.key());
            String filename = extractFilename(summary.key());
            if (!documentParser.supports(filename) || summary.sizeBytes() <= 0 || summary.sizeBytes() > maxFileSizeBytes) {
                ignored++;
                continue;
            }
            Optional<RagDocument> existing = repository.findByStorage(storageClient.providerName(), summary.key());
            if (existing.isPresent()
                && RagDocument.STATUS_READY.equals(existing.get().status())
                && summary.generation() != null
                && summary.generation().equals(existing.get().storageGeneration())) {
                skipped++;
                continue;
            }

            Optional<byte[]> bytesOpt = storageClient.get(summary.key());
            if (bytesOpt.isEmpty()) {
                ignored++;
                continue;
            }
            byte[] rawBytes = bytesOpt.get();
            String sha256 = sha256Hex(rawBytes);
            if (existing.isPresent()
                && RagDocument.STATUS_READY.equals(existing.get().status())
                && sha256.equals(existing.get().sha256())) {
                skipped++;
                continue;
            }

            DocumentParser.ParsedDocument parsed;
            try {
                parsed = documentParser.parse(filename, rawBytes, null);
            } catch (IllegalArgumentException exception) {
                log.debug("Skipping unsupported/invalid storage object {}: {}", summary.key(), exception.getMessage());
                ignored++;
                continue;
            }

            String contentType = documentParser.inferContentType(filename);
            Instant now = clock.instant();
            UUID documentId;
            if (existing.isPresent()) {
                documentId = existing.get().id();
                repository.updateForReindex(
                    documentId,
                    filename,
                    parsed.title(),
                    contentType,
                    rawBytes.length,
                    sha256,
                    summary.generation(),
                    now
                );
            } else {
                documentId = UUID.randomUUID();
                RagDocument created = new RagDocument(
                    documentId,
                    RagDocument.SOURCE_UPLOAD,
                    filename,
                    parsed.title(),
                    contentType,
                    rawBytes.length,
                    sha256,
                    storageClient.providerName(),
                    storageClient.bucketName(),
                    summary.key(),
                    summary.generation(),
                    RagDocument.STATUS_PENDING,
                    0,
                    null,
                    actor,
                    now,
                    now
                );
                repository.insert(created);
            }

            ingestionService.enqueue(documentId, sha256, filename, null, rawBytes);
            queued++;
        }

        int deleted = pruneDeletedUploads(scopedPrefix, seenKeys);

        return new RagSyncSummary(
            storageClient.providerName(),
            storageClient.bucketName(),
            scopedPrefix,
            objects.size(),
            queued,
            skipped,
            ignored,
            deleted
        );
    }

    public int recoverStuckDocuments() {
        List<RagDocument> stuck = repository.findStuckDocuments();
        int recovered = 0;
        for (RagDocument document : stuck) {
            try {
                if (reindex(document.id()).isPresent()) {
                    recovered++;
                }
            } catch (RuntimeException exception) {
                log.warn("Unable to recover stuck RAG document {} ({}): {}", document.id(), document.filename(), exception.getMessage());
            }
        }
        return recovered;
    }

    public List<RagDocument> list() {
        return repository.findAll();
    }

    public Optional<RagDocument> findById(UUID id) {
        return repository.findById(id);
    }

    public Optional<DownloadedDocument> download(UUID id) {
        Optional<RagDocument> documentOpt = repository.findById(id);
        if (documentOpt.isEmpty()) {
            return Optional.empty();
        }
        RagDocument document = documentOpt.get();
        Optional<byte[]> content = loadRawBytes(document);
        return content.map(bytes -> new DownloadedDocument(document, bytes));
    }

    public Optional<RagDocument> reindex(UUID id) {
        Optional<RagDocument> documentOpt = repository.findById(id);
        if (documentOpt.isEmpty()) {
            return Optional.empty();
        }
        RagDocument document = documentOpt.get();
        byte[] rawBytes = loadRawBytes(document)
            .orElseThrow(() -> new IllegalStateException("Source file is no longer available in storage"));
        String sha256 = sha256Hex(rawBytes);
        repository.updateForReindex(
            document.id(),
            document.filename(),
            document.title(),
            document.contentType(),
            rawBytes.length,
            sha256,
            document.storageGeneration(),
            clock.instant()
        );
        ingestionService.enqueue(document.id(), sha256, document.filename(), document.isBuiltin() ? null : document.title(), rawBytes);
        return repository.findById(id);
    }

    public boolean delete(UUID id) {
        Optional<RagDocument> documentOpt = repository.findById(id);
        if (documentOpt.isEmpty()) {
            return false;
        }
        RagDocument document = documentOpt.get();
        if (document.isBuiltin()) {
            throw new IllegalArgumentException("Built-in application guides cannot be deleted");
        }
        ingestionService.cancelPending(id);
        storageClient.delete(document.storageKey());
        return repository.deleteById(id);
    }

    public StorageInfo storageInfo(boolean canManage) {
        return new StorageInfo(
            storageClient.providerName(),
            storageClient.bucketName(),
            defaultPrefix,
            maxFileSizeBytes,
            repository.isPgVectorAvailable(),
            canManage
        );
    }

    String resolveScopedPrefix(String requestedPrefix) {
        String basePrefix = defaultPrefix.isEmpty() ? "" : defaultPrefix + "/";
        if (requestedPrefix == null || requestedPrefix.isBlank()) {
            return basePrefix;
        }
        String normalized = normalizePrefixSegment(requestedPrefix);
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("Invalid sync prefix");
        }
        String candidate = normalized.isEmpty() ? "" : normalized + "/";
        if (!basePrefix.isEmpty() && !candidate.startsWith(basePrefix)) {
            throw new IllegalArgumentException(
                "Sync prefix must stay within the configured prefix '" + basePrefix + "'"
            );
        }
        return candidate;
    }

    private int pruneDeletedUploads(String scopedPrefix, Set<String> seenKeys) {
        int deleted = 0;
        for (RagDocument existing : repository.findUploadsByPrefix(storageClient.providerName(), scopedPrefix)) {
            if (!seenKeys.contains(existing.storageKey())) {
                ingestionService.cancelPending(existing.id());
                if (repository.deleteById(existing.id())) {
                    deleted++;
                    log.info("Pruned deleted storage document {} ({})", existing.id(), existing.storageKey());
                }
            }
        }
        return deleted;
    }

    private Optional<byte[]> loadRawBytes(RagDocument document) {
        if (document.isBuiltin()) {
            return knowledgeLibrary.notes().stream()
                .filter(note -> note.path().equals(document.storageKey()))
                .findFirst()
                .map(note -> note.body().getBytes(StandardCharsets.UTF_8));
        }
        return storageClient.get(document.storageKey());
    }

    private void validatePayload(String filename, byte[] rawBytes) {
        if (!documentParser.supports(filename)) {
            throw new IllegalArgumentException("Unsupported file type. Supported formats: .md, .txt, .csv");
        }
        if (rawBytes == null || rawBytes.length == 0) {
            throw new IllegalArgumentException("Uploaded file must not be empty");
        }
        if (rawBytes.length > maxFileSizeBytes) {
            throw new IllegalArgumentException("File exceeds maximum allowed size of " + maxFileSizeBytes + " bytes");
        }
    }

    private String buildUploadKey(UUID id, String filename) {
        if (defaultPrefix.isEmpty()) {
            return "uploads/" + id + "/" + filename;
        }
        return defaultPrefix + "/uploads/" + id + "/" + filename;
    }

    private void safeDeleteFromStorage(String key) {
        try {
            storageClient.delete(key);
        } catch (RuntimeException cleanupException) {
            log.warn("Failed to clean up storage object {} after DB error", key, cleanupException);
        }
    }

    static String sanitizeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("Filename is required");
        }
        String normalized = originalFilename.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String base = (slash >= 0 ? normalized.substring(slash + 1) : normalized).trim();
        String safe = base.replaceAll("[\\r\\n\\t\\x00]", "").replaceAll("[^\\p{L}\\p{N}._\\- ]", "_");
        while (safe.startsWith(".")) {
            safe = safe.substring(1);
        }
        if (safe.isBlank()) {
            throw new IllegalArgumentException("Filename is invalid");
        }
        return safe.length() <= 180 ? safe : safe.substring(safe.length() - 180);
    }

    private static String extractFilename(String storageKey) {
        String normalized = storageKey.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static String normalizePrefixSegment(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String cleaned = prefix.replace('\\', '/').trim();
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record DownloadedDocument(RagDocument document, byte[] bytes) {
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DownloadedDocument that)) {
                return false;
            }
            return Objects.equals(document, that.document) && Arrays.equals(bytes, that.bytes);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(document) + Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "DownloadedDocument[document=" + document + ", bytesLength=" + (bytes == null ? 0 : bytes.length) + "]";
        }
    }

    public record StorageInfo(
        String provider,
        String bucket,
        String defaultPrefix,
        long maxFileSizeBytes,
        boolean pgVectorEnabled,
        boolean canManage
    ) {
    }
}
