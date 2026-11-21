package com.currencyanalyzer.ai.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeRetrieverTests {

    private final KnowledgeLibrary library = new KnowledgeLibrary();
    private final KnowledgeRetriever retriever = new KnowledgeRetriever(library);

    @Test
    void loadsInvestmentNotesAndLeavesOutJournals() {
        assertTrue(library.notes().size() > 40);
        assertTrue(library.notes().stream().anyMatch(note -> note.title().equals("凯利公式")));
        assertTrue(library.notes().stream().noneMatch(note ->
            note.path().contains("/journal/") || note.path().contains("/interview/")
        ));
    }

    @Test
    void retrievesKellyNotesForAPositionQuestion() {
        List<KnowledgeNote> notes = retriever.retrieve("请用凯利公式解释仓位", 4);

        assertFalse(notes.isEmpty());
        assertEquals("凯利公式", notes.getFirst().title());
        assertTrue(notes.stream().anyMatch(note -> note.title().equals("仓位管理")));
    }

    @Test
    void retrievesHowardMarksFromEnglishAndChineseTerms() {
        List<KnowledgeNote> notes = retriever.retrieve("Howard Marks 的钟摆理论是什么", 4);

        assertTrue(notes.stream().anyMatch(note -> note.title().contains("霍华德")));
    }

    @Test
    void ignoresQuestionsOutsideTheNotes() {
        assertTrue(retriever.retrieve("redis 缓存穿透怎么处理", 4).isEmpty());
    }

    @Test
    void putsRetrievedNoteTitlesIntoTheSystemPrompt() {
        String prompt = AssistantPrompt.systemMessage(
            retriever.retrieve("凯利公式", 2),
            4000
        );

        assertTrue(prompt.contains("Knowledge notes:"));
        assertTrue(prompt.contains("## 凯利公式"));
        assertTrue(prompt.contains("Never provide personalized buy, sell"));
    }
}
