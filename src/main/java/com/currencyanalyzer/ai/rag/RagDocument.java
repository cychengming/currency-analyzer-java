package com.currencyanalyzer.ai.rag;

import java.time.Instant;
import java.util.UUID;

public record RagDocument(
    UUID id,
    String source,
    String filename,
    String title,
    String contentType,
    long sizeBytes,
    String sha256,
    String storageProvider,
    String storageBucket,
    String storageKey,
    String storageGeneration,
    String status,
    int chunkCount,
    String errorMessage,
    String uploadedBy,
    Instant createdAt,
    Instant updatedAt
) {
    public static final String SOURCE_BUILTIN = "builtin";
    public static final String SOURCE_UPLOAD = "upload";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_INDEXING = "INDEXING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";

    public boolean isBuiltin() {
        return SOURCE_BUILTIN.equals(source);
    }
}
