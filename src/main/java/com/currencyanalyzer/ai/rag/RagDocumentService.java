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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class RagDocumentService {

    private static final Logger log = LoggerFactory.getLogger(RagDocumentService.class);
    private static final String EVENT_OBJECT_FINALIZE = "OBJECT_FINALIZE";
    private static final String EVENT_OBJECT_DELETE = "OBJECT_DELETE";
    private static final String EVENT_OBJECT_ARCHIVE = "OBJECT_ARCHIVE";
    private static final String STATUS_INDEXED = "indexed";
    private static final String STATUS_DELETED = "deleted";
    private static final String STATUS_SKIPPED = "skipped";
    private static final String STATUS_IGNORED = "ignored";
    private static final String ACTOR_PUBSUB_GCS = "pubsub-gcs";

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
        Instant listedAt = clock.instant();
        List<ObjectStorageClient.StoredObjectSummary> objects = storageClient.list(scopedPrefix);

        int queued = 0;
        int skipped = 0;
        int ignored = 0;
        Set<String> seenKeys = new HashSet<>();
        String actor = username == null || username.isBlank() ? "system" : username;

        for (ObjectStorageClient.StoredObjectSummary summary : objects) {
            seenKeys.add(summary.key());
            try {
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
            } catch (RuntimeException exception) {
                log.warn("Skipping storage object {} during sync: {}", summary.key(), exception.getMessage());
                ignored++;
            }
        }

        int deleted = objects.isEmpty() ? 0 : pruneDeletedUploads(scopedPrefix, seenKeys, listedAt);

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

    public StorageEventResult handleStorageObjectEvent(StorageObjectEvent event) {
        if (event == null || event.eventType() == null || event.eventType().isBlank()
            || event.objectKey() == null || event.objectKey().isBlank()) {
            throw new IllegalArgumentException("Storage event requires eventType and objectKey");
        }
        String objectKey = event.objectKey().replace('\\', '/').trim();
        if (!matchesConfiguredBucket(event.bucket())) {
            return new StorageEventResult(STATUS_IGNORED, "bucket_mismatch", objectKey, null);
        }
        if (!isWithinConfiguredPrefix(objectKey)) {
            return new StorageEventResult(STATUS_IGNORED, "outside_prefix", objectKey, null);
        }

        String normalizedType = event.eventType().trim().toUpperCase(Locale.ROOT);
        return switch (normalizedType) {
            case EVENT_OBJECT_FINALIZE -> handleObjectFinalize(objectKey, event.generation());
            case EVENT_OBJECT_DELETE, EVENT_OBJECT_ARCHIVE ->
                handleObjectDelete(objectKey, event.generation(), event.overwrittenByGeneration());
            default -> new StorageEventResult(STATUS_IGNORED, "unsupported_event_type", objectKey, null);
        };
    }

    private StorageEventResult handleObjectFinalize(String objectKey, String generation) {
        String filename = extractFilename(objectKey);
        if (!documentParser.supports(filename)) {
            return new StorageEventResult(STATUS_IGNORED, "unsupported_file_type", objectKey, null);
        }

        Optional<RagDocument> existingOpt = repository.findByStorage(storageClient.providerName(), objectKey);
        if (existingOpt.isPresent()) {
            StorageEventResult precheck = checkExistingFinalize(existingOpt.get(), generation, objectKey);
            if (precheck != null) {
                return precheck;
            }
        }

        Optional<byte[]> bytesOpt = storageClient.get(objectKey);
        if (bytesOpt.isEmpty()) {
            return new StorageEventResult(STATUS_SKIPPED, "object_not_found", objectKey, null);
        }
        byte[] rawBytes = bytesOpt.get();
        if (rawBytes.length == 0 || rawBytes.length > maxFileSizeBytes) {
            return new StorageEventResult(STATUS_IGNORED, "invalid_file_size", objectKey, null);
        }

        String sha256 = sha256Hex(rawBytes);
        if (existingOpt.isPresent()
            && RagDocument.STATUS_READY.equals(existingOpt.get().status())
            && sha256.equals(existingOpt.get().sha256())) {
            return new StorageEventResult(STATUS_SKIPPED, "unchanged_content", objectKey, existingOpt.get().id());
        }

        DocumentParser.ParsedDocument parsed;
        try {
            parsed = documentParser.parse(filename, rawBytes, null);
        } catch (IllegalArgumentException exception) {
            log.debug("Skipping unparseable storage object {}: {}", objectKey, exception.getMessage());
            return new StorageEventResult(STATUS_IGNORED, "unparseable_content", objectKey, null);
        }

        UUID documentId = upsertStorageDocument(
            existingOpt,
            objectKey,
            filename,
            parsed.title(),
            rawBytes.length,
            sha256,
            generation
        );
        ingestionService.ingestNow(documentId, sha256, filename, null, rawBytes);
        return new StorageEventResult(STATUS_INDEXED, null, objectKey, documentId);
    }

    private StorageEventResult handleObjectDelete(
        String objectKey,
        String generation,
        String overwrittenByGeneration
    ) {
        if (overwrittenByGeneration != null && !overwrittenByGeneration.isBlank()) {
            return new StorageEventResult(STATUS_SKIPPED, "overwritten_by_newer_generation", objectKey, null);
        }
        Optional<RagDocument> existingOpt = repository.findByStorage(storageClient.providerName(), objectKey);
        if (existingOpt.isEmpty()) {
            return new StorageEventResult(STATUS_SKIPPED, "document_not_found", objectKey, null);
        }
        RagDocument existing = existingOpt.get();
        if (existing.isBuiltin()) {
            return new StorageEventResult(STATUS_SKIPPED, "builtin_protected", objectKey, existing.id());
        }
        if (isStaleDeleteGeneration(generation, existing.storageGeneration())) {
            return new StorageEventResult(STATUS_SKIPPED, "stale_generation", objectKey, existing.id());
        }
        ingestionService.cancelPending(existing.id());
        boolean deleted = repository.deleteById(existing.id());
        if (!deleted) {
            return new StorageEventResult(STATUS_SKIPPED, "document_not_found", objectKey, existing.id());
        }
        log.info("Deleted RAG document {} ({}) via storage event", existing.id(), objectKey);
        return new StorageEventResult(STATUS_DELETED, null, objectKey, existing.id());
    }

    private StorageEventResult checkExistingFinalize(
        RagDocument existing,
        String incomingGeneration,
        String objectKey
    ) {
        Integer cmp = compareGenerations(incomingGeneration, existing.storageGeneration());
        if (cmp != null && cmp < 0) {
            return new StorageEventResult(STATUS_SKIPPED, "stale_generation", objectKey, existing.id());
        }
        if (cmp != null && cmp == 0 && !RagDocument.STATUS_FAILED.equals(existing.status())) {
            return new StorageEventResult(STATUS_SKIPPED, "already_indexed_or_in_progress", objectKey, existing.id());
        }
        return null;
    }

    private UUID upsertStorageDocument(
        Optional<RagDocument> existingOpt,
        String objectKey,
        String filename,
        String title,
        long sizeBytes,
        String sha256,
        String generation
    ) {
        String contentType = documentParser.inferContentType(filename);
        Instant now = clock.instant();
        if (existingOpt.isPresent()) {
            UUID documentId = existingOpt.get().id();
            repository.updateForReindex(documentId, filename, title, contentType, sizeBytes, sha256, generation, now);
            return documentId;
        }
        UUID documentId = UUID.randomUUID();
        RagDocument created = new RagDocument(
            documentId,
            RagDocument.SOURCE_UPLOAD,
            filename,
            title,
            contentType,
            sizeBytes,
            sha256,
            storageClient.providerName(),
            storageClient.bucketName(),
            objectKey,
            generation,
            RagDocument.STATUS_PENDING,
            0,
            null,
            ACTOR_PUBSUB_GCS,
            now,
            now
        );
        repository.insert(created);
        return documentId;
    }

    private boolean matchesConfiguredBucket(String eventBucket) {
        String configuredBucket = storageClient.bucketName();
        if (eventBucket == null || eventBucket.isBlank() || configuredBucket == null || configuredBucket.isBlank()) {
            return true;
        }
        return configuredBucket.equals(eventBucket.trim());
    }

    private boolean isWithinConfiguredPrefix(String objectKey) {
        if (objectKey.startsWith("/") || objectKey.endsWith("/") || objectKey.contains("..")) {
            return false;
        }
        if (defaultPrefix.isEmpty()) {
            return true;
        }
        return objectKey.startsWith(defaultPrefix + "/");
    }

    private static boolean isStaleDeleteGeneration(String incomingGeneration, String storedGeneration) {
        if (incomingGeneration == null || incomingGeneration.isBlank()
            || storedGeneration == null || storedGeneration.isBlank()) {
            return false;
        }
        return !incomingGeneration.trim().equals(storedGeneration.trim());
    }

    private static Integer compareGenerations(String incomingGeneration, String storedGeneration) {
        if (incomingGeneration == null || incomingGeneration.isBlank()
            || storedGeneration == null || storedGeneration.isBlank()) {
            return null;
        }
        String left = incomingGeneration.trim();
        String right = storedGeneration.trim();
        if (left.equals(right)) {
            return 0;
        }
        try {
            return Long.compare(Long.parseLong(left), Long.parseLong(right));
        } catch (NumberFormatException exception) {
            return null;
        }
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

    private int pruneDeletedUploads(String scopedPrefix, Set<String> seenKeys, Instant listedAt) {
        if (seenKeys.isEmpty()) {
            return 0;
        }
        String bucket = storageClient.bucketName();
        List<RagDocument> indexedBeforeList = new ArrayList<>();
        boolean listingMatchesIndex = false;
        for (RagDocument existing : repository.findUploadsByPrefix(storageClient.providerName(), scopedPrefix)) {
            if (!sameStorageBucket(existing, bucket)) {
                continue;
            }
            if (existing.createdAt() != null && !existing.createdAt().isBefore(listedAt)) {
                continue;
            }
            indexedBeforeList.add(existing);
            if (seenKeys.contains(existing.storageKey())) {
                listingMatchesIndex = true;
            }
        }
        if (!listingMatchesIndex) {
            log.info("Skipping RAG prune because the listing does not match indexed uploads");
            return 0;
        }
        int deleted = 0;
        for (RagDocument existing : indexedBeforeList) {
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

    private static boolean sameStorageBucket(RagDocument document, String bucket) {
        String stored = document.storageBucket();
        if (stored == null || stored.isBlank() || bucket == null || bucket.isBlank()) {
            return true;
        }
        return stored.equals(bucket);
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

    public record StorageObjectEvent(
        String eventType,
        String bucket,
        String objectKey,
        String generation,
        String overwrittenByGeneration
    ) {
    }

    public record StorageEventResult(
        String status,
        String reason,
        String objectKey,
        UUID documentId
    ) {
    }
}
