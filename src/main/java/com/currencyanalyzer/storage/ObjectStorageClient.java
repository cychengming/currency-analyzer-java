package com.currencyanalyzer.storage;

import java.util.List;
import java.util.Optional;

public interface ObjectStorageClient {

    String providerName();

    String bucketName();

    StoredObject put(String objectKey, byte[] content, String contentType);

    Optional<byte[]> get(String objectKey);

    boolean delete(String objectKey);

    List<StoredObjectSummary> list(String prefix);

    record StoredObject(
        String provider,
        String bucket,
        String key,
        long sizeBytes,
        String contentType,
        String generation
    ) {
    }

    record StoredObjectSummary(
        String key,
        long sizeBytes,
        String contentType,
        String generation
    ) {
    }
}
