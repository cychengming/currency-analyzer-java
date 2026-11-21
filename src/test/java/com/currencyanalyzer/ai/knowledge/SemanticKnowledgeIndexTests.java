package com.currencyanalyzer.ai.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticKnowledgeIndexTests {

    @Test
    void findsANoteByMeaningWhenTheWordsDoNotOverlap() {
        KnowledgeNote position = new KnowledgeNote("concepts/position.md", "仓位管理", "单笔风险占账户的比例");
        KnowledgeNote pendulum = new KnowledgeNote("concepts/pendulum.md", "钟摆", "市场情绪在极端之间摆动");
        KnowledgeLibrary library = new KnowledgeLibrary(List.of(position, pendulum));
        SemanticKnowledgeIndex index = new SemanticKnowledgeIndex(library, new MeaningEmbedding(), 0.5);
        KnowledgeRetriever retriever = new KnowledgeRetriever(library, index);

        List<KnowledgeNote> notes = retriever.retrieve("how much of the account should one trade risk?", 2);

        assertEquals(1, notes.size());
        assertEquals("仓位管理", notes.getFirst().title());
    }

    @Test
    void usesKeywordSearchWhenTheEmbeddingModelFails() {
        EmbeddingModel broken = new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                throw new IllegalStateException("model unavailable");
            }

            @Override
            public float[] embed(Document document) {
                throw new IllegalStateException("model unavailable");
            }
        };
        KnowledgeLibrary library = new KnowledgeLibrary();
        SemanticKnowledgeIndex index = new SemanticKnowledgeIndex(library, broken, 0.45);
        KnowledgeRetriever retriever = new KnowledgeRetriever(library, index);

        List<KnowledgeNote> notes = retriever.retrieve("请用凯利公式解释仓位", 4);

        assertTrue(notes.stream().anyMatch(note -> note.title().equals("凯利公式")));
    }

    /**
     * Maps a few related phrases onto the same vector so the index can be tested
     * without downloading an embedding model.
     */
    private static final class MeaningEmbedding implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> results = new ArrayList<>();
            int index = 0;
            for (String text : request.getInstructions()) {
                results.add(new Embedding(vector(text), index++));
            }
            return new EmbeddingResponse(results);
        }

        @Override
        public float[] embed(Document document) {
            return vector(document.getText());
        }

        private static float[] vector(String text) {
            String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
            if (value.contains("仓位") || value.contains("账户") || value.contains("risk") || value.contains("kelly")) {
                return new float[] {1f, 0f, 0f};
            }
            if (value.contains("钟摆") || value.contains("情绪")) {
                return new float[] {0f, 1f, 0f};
            }
            return new float[] {0f, 0f, 1f};
        }
    }
}
