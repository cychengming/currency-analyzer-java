package com.currencyanalyzer.ai.springai;

import com.currencyanalyzer.ai.rag.RagEmbeddingConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class AssistantSpringAiConfigurationTests {

    @Test
    void buildsTheChatClientAndLazyEmbeddingModel() {
        AssistantSpringAiConfiguration configuration = new AssistantSpringAiConfiguration();
        RagEmbeddingConfiguration embeddingConfiguration = new RagEmbeddingConfiguration();

        ChatModel plain = configuration.assistantChatModel(
            "https://api.deepseek.com",
            "test-key",
            "deepseek-flash",
            200,
            15
        );
        ChatModel slashed = configuration.assistantChatModel(
            "https://api.deepseek.com///",
            "test-key",
            "deepseek-flash",
            200,
            15
        );
        EmbeddingModel embeddingModel = embeddingConfiguration.ragEmbeddingModel(
            "https://example.invalid/model.onnx",
            "https://example.invalid/tokenizer.json",
            System.getProperty("java.io.tmpdir")
        );

        assertNotNull(plain);
        assertNotNull(slashed);
        assertNotNull(embeddingModel);
    }
}
