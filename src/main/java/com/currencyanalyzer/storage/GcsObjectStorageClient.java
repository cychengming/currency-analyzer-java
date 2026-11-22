package com.currencyanalyzer.storage;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.http.HttpTransportFactory;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.http.HttpTransportOptions;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

public class GcsObjectStorageClient implements ObjectStorageClient {

    private static final Logger log = LoggerFactory.getLogger(GcsObjectStorageClient.class);

    private final String projectId;
    private final String bucket;
    private final Supplier<Storage> storageSupplier;
    private final Object lock = new Object();
    private volatile Storage storage;

    public GcsObjectStorageClient(String projectId, String bucket, String credentialsPath) {
        this(projectId, bucket, () -> createStorage(projectId, credentialsPath));
    }

    GcsObjectStorageClient(String projectId, String bucket, Supplier<Storage> storageSupplier) {
        this.projectId = projectId == null ? "" : projectId.trim();
        this.bucket = bucket == null ? "" : bucket.trim();
        this.storageSupplier = storageSupplier;
    }

    @Override
    public String providerName() {
        return "gcs";
    }

    @Override
    public String bucketName() {
        return bucket;
    }

    @Override
    public StoredObject put(String objectKey, byte[] content, String contentType) {
        requireBucket();
        String key = normalizeKey(objectKey);
        try {
            BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucket, key))
                .setContentType(contentType)
                .build();
            Blob created = client().create(blobInfo, content);
            String generation = created.getGeneration() == null ? null : created.getGeneration().toString();
            long size = created.getSize() == null ? content.length : created.getSize();
            return new StoredObject(providerName(), bucket, key, size, contentType, generation);
        } catch (com.google.cloud.storage.StorageException exception) {
            log.warn("GCS upload failed for gs://{}/{}: {}", bucket, key, exception.getMessage());
            throw new StorageException("Unable to upload file to Google Cloud Storage", exception);
        }
    }

    @Override
    public Optional<byte[]> get(String objectKey) {
        requireBucket();
        String key = normalizeKey(objectKey);
        try {
            Blob blob = client().get(BlobId.of(bucket, key));
            if (blob == null || !blob.exists()) {
                return Optional.empty();
            }
            return Optional.of(blob.getContent());
        } catch (com.google.cloud.storage.StorageException exception) {
            log.warn("GCS read failed for gs://{}/{}: {}", bucket, key, exception.getMessage());
            throw new StorageException("Unable to read file from Google Cloud Storage", exception);
        }
    }

    @Override
    public boolean delete(String objectKey) {
        requireBucket();
        String key = normalizeKey(objectKey);
        try {
            return client().delete(BlobId.of(bucket, key));
        } catch (com.google.cloud.storage.StorageException exception) {
            log.warn("GCS delete failed for gs://{}/{}: {}", bucket, key, exception.getMessage());
            throw new StorageException("Unable to delete file from Google Cloud Storage", exception);
        }
    }

    @Override
    public List<StoredObjectSummary> list(String prefix) {
        requireBucket();
        String normalizedPrefix = normalizePrefix(prefix);
        try {
            List<StoredObjectSummary> results = new ArrayList<>();
            Iterable<Blob> blobs = normalizedPrefix.isEmpty()
                ? client().list(bucket).iterateAll()
                : client().list(bucket, Storage.BlobListOption.prefix(normalizedPrefix)).iterateAll();
            for (Blob blob : blobs) {
                if (blob.isDirectory() || blob.getName().endsWith("/")) {
                    continue;
                }
                long size = blob.getSize() == null ? 0L : blob.getSize();
                String generation = blob.getGeneration() == null ? null : blob.getGeneration().toString();
                results.add(new StoredObjectSummary(blob.getName(), size, blob.getContentType(), generation));
            }
            return List.copyOf(results);
        } catch (com.google.cloud.storage.StorageException exception) {
            log.warn("GCS list failed for gs://{}/{}: {}", bucket, normalizedPrefix, exception.getMessage());
            throw new StorageException("Unable to list files in Google Cloud Storage", exception);
        }
    }

    private Storage client() {
        Storage existing = storage;
        if (existing != null) {
            return existing;
        }
        synchronized (lock) {
            if (storage == null) {
                storage = storageSupplier.get();
            }
            return storage;
        }
    }

    private void requireBucket() {
        if (bucket.isBlank()) {
            throw new StorageException("GCP storage bucket is not configured (app.storage.gcs.bucket)");
        }
    }

    private static Storage createStorage(String projectId, String credentialsPath) {
        try {
            StorageOptions.Builder builder = StorageOptions.newBuilder();
            if (projectId != null && !projectId.isBlank()) {
                builder.setProjectId(projectId.trim());
            }
            Proxy proxy = resolveProxy();
            HttpTransportFactory transportFactory = proxy == null
                ? null
                : () -> new NetHttpTransport.Builder().setProxy(proxy).build();
            if (transportFactory != null) {
                log.info("Configuring GCS client with HTTP proxy {}", proxy.address());
                builder.setTransportOptions(
                    HttpTransportOptions.newBuilder().setHttpTransportFactory(transportFactory).build()
                );
            }
            if (credentialsPath != null && !credentialsPath.isBlank()) {
                Path path = Path.of(credentialsPath.trim());
                try (InputStream input = Files.newInputStream(path)) {
                    GoogleCredentials credentials = transportFactory == null
                        ? GoogleCredentials.fromStream(input)
                        : GoogleCredentials.fromStream(input, transportFactory);
                    builder.setCredentials(credentials);
                }
            } else if (transportFactory != null) {
                builder.setCredentials(GoogleCredentials.getApplicationDefault(transportFactory));
            }
            return builder.build().getService();
        } catch (IOException | RuntimeException exception) {
            throw new StorageException(
                "Unable to initialize Google Cloud Storage client. Verify GOOGLE_APPLICATION_CREDENTIALS or ADC.",
                exception
            );
        }
    }

    static Proxy resolveProxy() {
        return resolveProxy(System::getenv);
    }

    static Proxy resolveProxy(Function<String, String> env) {
        String proxyUrl = firstNonBlank(
            env.apply("HTTPS_PROXY"),
            env.apply("https_proxy"),
            env.apply("HTTP_PROXY"),
            env.apply("http_proxy")
        );
        if (proxyUrl == null) {
            return null;
        }
        String normalized = proxyUrl.contains("://") ? proxyUrl : "http://" + proxyUrl;
        URI uri = URI.create(normalized);
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || host.isBlank() || port <= 0) {
            return null;
        }
        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String normalizeKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey must not be blank");
        }
        String cleaned = objectKey.replace('\\', '/').trim();
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.isEmpty() || cleaned.contains("..")) {
            throw new IllegalArgumentException("Invalid objectKey");
        }
        return cleaned;
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String cleaned = prefix.replace('\\', '/').trim();
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        return cleaned;
    }
}
