package com.currencyanalyzer.ai.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeRetrieverTests {

    private final KnowledgeLibrary fixtureLibrary = loadFixtureLibrary();
    private final KnowledgeRetriever retriever = new KnowledgeRetriever(fixtureLibrary);

    @Test
    void classpathLibraryLoadsBuiltInGuidesOnly() {
        KnowledgeLibrary builtIn = new KnowledgeLibrary();

        assertFalse(builtIn.notes().isEmpty());
        assertTrue(builtIn.notes().stream().allMatch(note -> note.path().startsWith("knowledge/guides/")));
        assertTrue(builtIn.notes().stream().anyMatch(note -> note.title().contains("User Guide")));
        assertTrue(builtIn.notes().stream().noneMatch(note ->
            note.path().contains("/concepts/") || note.path().contains("/entities/") || note.path().contains("/journal/")
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
    void keepsRetrievedNotesOutOfTheSystemPrompt() {
        String system = AssistantPrompt.systemMessage();
        String context = AssistantPrompt.referenceContext(retriever.retrieve("凯利公式", 2), 4000);

        assertTrue(system.contains("Never provide personalized buy, sell"));
        assertFalse(system.contains("凯利公式"));
        assertFalse(system.contains(context));
        assertTrue(context.startsWith("UNTRUSTED REFERENCE CONTEXT"));
        assertTrue(context.contains("## 凯利公式"));
        assertTrue(context.contains("END UNTRUSTED REFERENCE CONTEXT"));
        assertEquals("", AssistantPrompt.referenceContext(List.of(), 4000));
    }

    @Test
    void rewritesAFollowUpSoRetrievalStillFindsTheEarlierSubject() {
        String followUp = "what about his risk view?";
        assertTrue(retriever.retrieve(followUp, 4).isEmpty());

        String query = RetrievalQueryRewriter.rewrite(List.of(
            new RetrievalQueryRewriter.SpokenTurn("user", "Tell me about Howard Marks"),
            new RetrievalQueryRewriter.SpokenTurn("assistant", "He writes about market cycles."),
            new RetrievalQueryRewriter.SpokenTurn("user", followUp)
        ));

        assertTrue(query.toLowerCase(java.util.Locale.ROOT).contains("howard marks"));
        assertTrue(query.contains(followUp));
        assertTrue(retriever.retrieve(query, 4).stream().anyMatch(note -> note.title().contains("霍华德")));
        assertEquals(
            "请用凯利公式解释仓位",
            RetrievalQueryRewriter.rewrite(List.of(
                new RetrievalQueryRewriter.SpokenTurn("user", "请用凯利公式解释仓位")
            ))
        );
    }

    private static KnowledgeLibrary loadFixtureLibrary() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:knowledge/fixtures/*.md");
            List<KnowledgeNote> notes = new ArrayList<>();
            for (Resource resource : resources) {
                try (InputStream input = resource.getInputStream()) {
                    String raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    String body = KnowledgeLibrary.stripFrontMatter(raw).strip();
                    String title = body.lines()
                        .map(String::strip)
                        .filter(line -> line.startsWith("# "))
                        .map(line -> line.substring(2).strip())
                        .findFirst()
                        .orElse(resource.getFilename());
                    notes.add(new KnowledgeNote("knowledge/fixtures/" + resource.getFilename(), title, body));
                }
            }
            return new KnowledgeLibrary(notes);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load test knowledge fixtures", exception);
        }
    }
}
