package com.currencyanalyzer.ai.springai;

import com.currencyanalyzer.ai.knowledge.KnowledgeLibrary;
import com.currencyanalyzer.ai.knowledge.SemanticKnowledgeIndex;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantSpringAiConfigurationTests {

    @Test
    void buildsTheChatClientAndSkipsABlankMeaningSearch() {
        AssistantSpringAiConfiguration configuration = new AssistantSpringAiConfiguration();

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
        SemanticKnowledgeIndex index = configuration.semanticKnowledgeIndex(
            new KnowledgeLibrary(),
            0.45,
            "https://example.invalid/model.onnx",
            "https://example.invalid/tokenizer.json",
            System.getProperty("java.io.tmpdir")
        );

        assertNotNull(plain);
        assertNotNull(slashed);
        assertTrue(index.retrieve(null, 1).isEmpty());
        assertTrue(index.retrieve(" ", 1).isEmpty());
        assertTrue(index.retrieve("position sizing", 0).isEmpty());
    }
}
