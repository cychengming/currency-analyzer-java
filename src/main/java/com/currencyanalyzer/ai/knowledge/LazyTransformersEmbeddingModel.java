package com.currencyanalyzer.ai.knowledge;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.transformers.TransformersEmbeddingModel;

import java.util.Map;

/**
 * Loads the multilingual ONNX model on the first embedding call so application
 * startup does not download it.
 */
public final class LazyTransformersEmbeddingModel implements EmbeddingModel {

    private final TransformersEmbeddingModel delegate = new TransformersEmbeddingModel();
    private final Object lock = new Object();
    private volatile boolean ready;

    public LazyTransformersEmbeddingModel(String modelUri, String tokenizerUri, String cacheDirectory) {
        delegate.setModelResource(modelUri);
        delegate.setTokenizerResource(tokenizerUri);
        delegate.setResourceCacheDirectory(cacheDirectory);
        delegate.setModelOutputName(TransformersEmbeddingModel.DEFAULT_MODEL_OUTPUT_NAME);
        delegate.setTokenizerOptions(Map.of(
            "padding", "true",
            "truncation", "true",
            "maxLength", "256",
            "modelMaxLength", "256"
        ));
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        initialize();
        return delegate.call(request);
    }

    @Override
    public float[] embed(Document document) {
        initialize();
        return delegate.embed(document);
    }

    private void initialize() {
        if (ready) {
            return;
        }
        synchronized (lock) {
            if (ready) {
                return;
            }
            try {
                delegate.afterPropertiesSet();
            } catch (Exception exception) {
                throw new IllegalStateException("The embedding model could not be loaded", exception);
            }
            ready = true;
        }
    }
}
