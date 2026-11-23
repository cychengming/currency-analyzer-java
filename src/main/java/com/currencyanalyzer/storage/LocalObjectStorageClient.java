package com.currencyanalyzer.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class LocalObjectStorageClient implements ObjectStorageClient {

    private final Path rootDirectory;

    public LocalObjectStorageClient(String rootDirectory) {
        this.rootDirectory = Path.of(rootDirectory).toAbsolutePath().normalize();
    }

    @Override
    public String providerName() {
        return "local";
    }

    @Override
    public String bucketName() {
        return rootDirectory.getFileName() == null ? "local" : rootDirectory.getFileName().toString();
    }

    @Override
    public StoredObject put(String objectKey, byte[] content, String contentType) {
        Path target = resolveSafe(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            String generation = fileGeneration(target, content.length);
            return new StoredObject(providerName(), bucketName(), normalizeKey(objectKey), content.length, contentType, generation);
        } catch (IOException exception) {
            throw new StorageException("Unable to write file to local storage", exception);
        }
    }

    @Override
    public Optional<byte[]> get(String objectKey) {
        Path target = resolveSafe(objectKey);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException exception) {
            throw new StorageException("Unable to read file from local storage", exception);
        }
    }

    @Override
    public boolean delete(String objectKey) {
        Path target = resolveSafe(objectKey);
        try {
            return Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new StorageException("Unable to delete file from local storage", exception);
        }
    }

    @Override
    public List<StoredObjectSummary> list(String prefix) {
        if (!Files.isDirectory(rootDirectory)) {
            return List.of();
        }
        String normalizedPrefix = normalizePrefix(prefix);
        List<StoredObjectSummary> items = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(rootDirectory)) {
            walk.filter(Files::isRegularFile)
                .sorted(Comparator.comparing(path -> rootDirectory.relativize(path).toString()))
                .forEach(file -> {
                    String key = rootDirectory.relativize(file).toString().replace('\\', '/');
                    if (!normalizedPrefix.isEmpty() && !key.startsWith(normalizedPrefix)) {
                        return;
                    }
                    try {
                        long size = Files.size(file);
                        String generation = fileGeneration(file, size);
                        items.add(new StoredObjectSummary(key, size, null, generation));
                    } catch (IOException exception) {
                        throw new StorageException("Unable to inspect file in local storage", exception);
                    }
                });
        } catch (IOException exception) {
            throw new StorageException("Unable to list files in local storage", exception);
        }
        return List.copyOf(items);
    }

    private Path resolveSafe(String objectKey) {
        String cleaned = normalizeKey(objectKey);
        Path target = rootDirectory.resolve(cleaned).normalize();
        if (!target.startsWith(rootDirectory)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return target;
    }

    private static String fileGeneration(Path file, long size) throws IOException {
        return Files.getLastModifiedTime(file).toMillis() + "-" + size;
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
            throw new IllegalArgumentException("Invalid storage key");
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
