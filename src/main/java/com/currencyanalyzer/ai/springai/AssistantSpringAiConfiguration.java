package com.currencyanalyzer.ai.springai;

import com.currencyanalyzer.ai.knowledge.KnowledgeLibrary;
import com.currencyanalyzer.ai.knowledge.LazyTransformersEmbeddingModel;
import com.currencyanalyzer.ai.knowledge.SemanticKnowledgeIndex;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true")
public class AssistantSpringAiConfiguration {

    @Bean
    @Conditional(DeepSeekApiKeyPresent.class)
    ChatModel assistantChatModel(
        @Value("${app.ai.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
        @Value("${app.ai.deepseek.api-key}") String apiKey,
        @Value("${app.ai.model:deepseek-flash}") String model,
        @Value("${app.ai.max-output-tokens:1200}") int maxOutputTokens,
        @Value("${app.ai.request-timeout-seconds:60}") int timeoutSeconds
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        HttpClient httpClient = HttpClient.create().responseTimeout(Duration.ofSeconds(timeoutSeconds));
        DeepSeekApi api = DeepSeekApi.builder()
            .baseUrl(withoutTrailingSlashes(baseUrl))
            .apiKey(apiKey)
            .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
            .webClientBuilder(WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient)))
            .build();
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
            .model(model)
            .maxTokens(maxOutputTokens)
            .enableThinking()
            .build();
        return DeepSeekChatModel.builder().deepSeekApi(api).options(options).build();
    }

    private static String withoutTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    @Bean
    @ConditionalOnProperty(name = "app.ai.knowledge.retrieval", havingValue = "vector")
    SemanticKnowledgeIndex semanticKnowledgeIndex(
        KnowledgeLibrary library,
        @Value("${app.ai.knowledge.similarity-threshold:0.45}") double similarityThreshold,
        @Value("${app.ai.knowledge.embedding-model-uri}") String modelUri,
        @Value("${app.ai.knowledge.embedding-tokenizer-uri}") String tokenizerUri,
        @Value("${app.ai.knowledge.embedding-cache-directory}") String cacheDirectory
    ) {
        EmbeddingModel embeddingModel = new LazyTransformersEmbeddingModel(modelUri, tokenizerUri, cacheDirectory);
        return new SemanticKnowledgeIndex(library, embeddingModel, similarityThreshold);
    }
}
