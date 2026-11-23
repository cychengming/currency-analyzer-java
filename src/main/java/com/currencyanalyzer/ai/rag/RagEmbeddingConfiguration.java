package com.currencyanalyzer.ai.rag;

import com.currencyanalyzer.ai.knowledge.LazyTransformersEmbeddingModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagEmbeddingConfiguration {

    @Bean
    @ConditionalOnProperty(name = "app.ai.knowledge.retrieval", havingValue = "vector")
    public EmbeddingModel ragEmbeddingModel(
        @Value("${app.ai.knowledge.embedding-model-uri}") String modelUri,
        @Value("${app.ai.knowledge.embedding-tokenizer-uri}") String tokenizerUri,
        @Value("${app.ai.knowledge.embedding-cache-directory}") String cacheDirectory
    ) {
        return new LazyTransformersEmbeddingModel(modelUri, tokenizerUri, cacheDirectory);
    }
}
