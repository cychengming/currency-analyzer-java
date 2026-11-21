package com.currencyanalyzer.ai.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Finds notes by embedding similarity. The index is built on the first search.
 * If the embedding model cannot be loaded, later searches return nothing and the
 * keyword retriever answers instead.
 */
public class SemanticKnowledgeIndex {

    private static final Logger log = LoggerFactory.getLogger(SemanticKnowledgeIndex.class);
    private static final int INDEX_CHARS = 1800;

    private final KnowledgeLibrary library;
    private final EmbeddingModel embeddingModel;
    private final double similarityThreshold;
    private final Object lock = new Object();
    private volatile SimpleVectorStore vectorStore;
    private volatile boolean unavailable;

    public SemanticKnowledgeIndex(KnowledgeLibrary library, EmbeddingModel embeddingModel, double similarityThreshold) {
        this.library = library;
        this.embeddingModel = embeddingModel;
        this.similarityThreshold = similarityThreshold;
    }

    public List<KnowledgeNote> retrieve(String query, int maxNotes) {
        if (unavailable || query == null || query.isBlank() || maxNotes < 1) {
            return List.of();
        }
        try {
            SimpleVectorStore store = readyStore();
            if (store == null) {
                return List.of();
            }
            List<Document> hits = store.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(maxNotes)
                .similarityThreshold(similarityThreshold)
                .build());
            List<KnowledgeNote> notes = new ArrayList<>();
            for (Document hit : hits) {
                Object path = hit.getMetadata().get("path");
                KnowledgeNote note = path == null ? null : noteByPath(path.toString());
                if (note != null) {
                    notes.add(note);
                }
            }
            return List.copyOf(notes);
        } catch (RuntimeException exception) {
            unavailable = true;
            log.warn("Meaning search is unavailable ({})", exception.toString());
            return List.of();
        }
    }

    private SimpleVectorStore readyStore() {
        SimpleVectorStore store = vectorStore;
        if (store != null || unavailable) {
            return store;
        }
        synchronized (lock) {
            if (vectorStore != null || unavailable) {
                return vectorStore;
            }
            try {
                List<Document> documents = new ArrayList<>();
                for (KnowledgeNote note : library.notes()) {
                    String text = note.title() + "\n" + excerpt(note.body());
                    documents.add(new Document(note.path(), text, Map.of(
                        "path", note.path(),
                        "title", note.title()
                    )));
                }
                SimpleVectorStore created = SimpleVectorStore.builder(embeddingModel).build();
                created.add(documents);
                vectorStore = created;
                return created;
            } catch (RuntimeException exception) {
                unavailable = true;
                throw exception;
            }
        }
    }

    private KnowledgeNote noteByPath(String path) {
        for (KnowledgeNote note : library.notes()) {
            if (note.path().equals(path)) {
                return note;
            }
        }
        return null;
    }

    private static String excerpt(String body) {
        String text = body == null ? "" : body.strip();
        if (text.length() <= INDEX_CHARS) {
            return text;
        }
        return text.substring(0, INDEX_CHARS);
    }
}
