package com.currencyanalyzer.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfiguration {

    @Bean
    public ObjectStorageClient objectStorageClient(
        @Value("${app.storage.provider:gcs}") String provider,
        @Value("${app.storage.local-directory:${user.home}/.cache/currency-analyzer/uploads}") String localDirectory,
        @Value("${app.storage.gcs.project-id:}") String projectId,
        @Value("${app.storage.gcs.bucket:}") String bucket,
        @Value("${app.storage.gcs.credentials-path:}") String credentialsPath
    ) {
        if ("local".equalsIgnoreCase(provider == null ? "" : provider.trim())) {
            return new LocalObjectStorageClient(localDirectory);
        }
        return new GcsObjectStorageClient(projectId, bucket, credentialsPath);
    }
}
