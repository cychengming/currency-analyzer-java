package com.currencyanalyzer.ai.rag;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class RagIngestionService {

    private static final Logger log = LoggerFactory.getLogger(RagIngestionService.class);
    private static final int EMBEDDING_BATCH_SIZE = 16;
    private static final int INGESTION_CONCURRENCY = 2;

    private final RagDocumentRepository repository;
    private final DocumentParser documentParser;
    private final EmbeddingModel embeddingModel;
    private final Clock clock;
    private final ExecutorService executor = Executors.newFixedThreadPool(INGESTION_CONCURRENCY, runnable -> {
        Thread thread = new Thread(runnable, "rag-ingestion");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public RagIngestionService(
        RagDocumentRepository repository,
        DocumentParser documentParser,
        ObjectProvider<EmbeddingModel> embeddingModelProvider,
        Clock clock
    ) {
        this(repository, documentParser, embeddingModelProvider.getIfAvailable(), clock);
    }

    RagIngestionService(
        RagDocumentRepository repository,
        DocumentParser documentParser,
        EmbeddingModel embeddingModel,
        Clock clock
    ) {
        this.repository = repository;
        this.documentParser = documentParser;
        this.embeddingModel = embeddingModel;
        this.clock = clock;
    }

    public CompletableFuture<Void> enqueue(UUID documentId, String filename, String titleOverride, byte[] rawBytes) {
        return CompletableFuture.runAsync(
            () -> ingestNow(documentId, filename, titleOverride, rawBytes),
            executor
        );
    }

    public void ingestNow(UUID documentId, String filename, String titleOverride, byte[] rawBytes) {
        try {
            repository.updateStatus(documentId, RagDocument.STATUS_INDEXING, 0, null, clock.instant());
            DocumentParser.ParsedDocument parsed = documentParser.parse(filename, rawBytes, titleOverride);
            List<DocumentParser.ParsedChunk> chunks = parsed.chunks();
            List<float[]> vectors = embedAll(parsed.title(), chunks);

            List<RagDocumentRepository.ChunkRecord> records = new ArrayList<>(chunks.size());
            for (int index = 0; index < chunks.size(); index++) {
                DocumentParser.ParsedChunk chunk = chunks.get(index);
                float[] embedding = index < vectors.size() ? vectors.get(index) : null;
                records.add(new RagDocumentRepository.ChunkRecord(
                    chunk.index(),
                    chunk.heading(),
                    chunk.content(),
                    embedding
                ));
            }
            repository.replaceChunks(documentId, parsed.title(), records, clock.instant());
            log.info("Indexed RAG document {} ({}) into {} chunks", documentId, filename, records.size());
        } catch (RuntimeException exception) {
            log.warn("Failed to index RAG document {} ({}): {}", documentId, filename, exception.getMessage());
            String message = exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
            try {
                repository.updateStatus(documentId, RagDocument.STATUS_FAILED, 0, message, clock.instant());
            } catch (RuntimeException updateException) {
                log.debug("Unable to persist FAILED status for document {}", documentId, updateException);
            }
        }
    }

    private List<float[]> embedAll(String documentTitle, List<DocumentParser.ParsedChunk> chunks) {
        if (embeddingModel == null || !repository.isPgVectorAvailable() || chunks.isEmpty()) {
            return List.of();
        }
        String documentLead = extractDocumentLead(chunks);
        try {
            List<float[]> results = new ArrayList<>(chunks.size());
            for (int start = 0; start < chunks.size(); start += EMBEDDING_BATCH_SIZE) {
                int end = Math.min(chunks.size(), start + EMBEDDING_BATCH_SIZE);
                List<String> inputs = new ArrayList<>(end - start);
                for (int index = start; index < end; index++) {
                    DocumentParser.ParsedChunk chunk = chunks.get(index);
                    inputs.add(embeddingInput(documentTitle, index == 0 ? "" : documentLead, chunk));
                }
                List<float[]> batch = embeddingModel.embed(inputs);
                results.addAll(batch);
            }
            return results;
        } catch (RuntimeException exception) {
            log.warn(
                "Embedding model unavailable while indexing '{}'; storing text chunks for keyword search ({})",
                documentTitle,
                exception.getMessage()
            );
            return List.of();
        }
    }

    private static String extractDocumentLead(List<DocumentParser.ParsedChunk> chunks) {
        if (chunks.isEmpty()) {
            return "";
        }
        String first = chunks.getFirst().content();
        if (first == null || first.isBlank()) {
            return "";
        }
        String singleLine = first.replaceAll("\\s+", " ").strip();
        return singleLine.length() <= 140 ? singleLine : singleLine.substring(0, 140);
    }

    private static String embeddingInput(String documentTitle, String documentLead, DocumentParser.ParsedChunk chunk) {
        StringBuilder builder = new StringBuilder();
        if (documentTitle != null && !documentTitle.isBlank()) {
            builder.append(documentTitle);
        }
        if (chunk.heading() != null && !chunk.heading().isBlank() && !chunk.heading().equals(documentTitle)) {
            if (!builder.isEmpty()) {
                builder.append(" - ");
            }
            builder.append(chunk.heading());
        }
        if (documentLead != null && !documentLead.isBlank()) {
            builder.append('\n').append(documentLead);
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(chunk.content());
        return builder.toString();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
