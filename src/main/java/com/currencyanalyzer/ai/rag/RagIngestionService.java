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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RagIngestionService {

    private static final Logger log = LoggerFactory.getLogger(RagIngestionService.class);
    private static final int EMBEDDING_BATCH_SIZE = 16;
    // One batch at a time. A second worker does not embed faster on the pinned
    // 1 vCPU and would keep two ONNX batches resident inside the 1 GiB limit.
    private static final int INGESTION_CONCURRENCY = 1;

    private final RagDocumentRepository repository;
    private final DocumentParser documentParser;
    private final EmbeddingModel embeddingModel;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, AtomicLong> documentSequences = new ConcurrentHashMap<>();
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

    public CompletableFuture<Void> enqueue(
        UUID documentId,
        String expectedSha256,
        String filename,
        String titleOverride,
        byte[] rawBytes
    ) {
        long sequence = nextSequence(documentId);
        return CompletableFuture.runAsync(
            () -> ingestInternal(documentId, expectedSha256, sequence, filename, titleOverride, rawBytes),
            executor
        );
    }

    public void ingestNow(
        UUID documentId,
        String expectedSha256,
        String filename,
        String titleOverride,
        byte[] rawBytes
    ) {
        long sequence = nextSequence(documentId);
        ingestInternal(documentId, expectedSha256, sequence, filename, titleOverride, rawBytes);
    }

    public void cancelPending(UUID documentId) {
        nextSequence(documentId);
    }

    private void ingestInternal(
        UUID documentId,
        String expectedSha256,
        long sequence,
        String filename,
        String titleOverride,
        byte[] rawBytes
    ) {
        if (isSuperseded(documentId, sequence)) {
            log.debug("Skipping superseded ingestion task for document {} ({})", documentId, filename);
            return;
        }
        try {
            boolean claimed = repository.updateStatusIfMatches(
                documentId, expectedSha256, RagDocument.STATUS_INDEXING, 0, null, clock.instant()
            );
            if (!claimed) {
                log.debug("Skipping stale ingestion for document {} ({}): sha256 mismatch or deleted", documentId, filename);
                return;
            }
            DocumentParser.ParsedDocument parsed = documentParser.parse(filename, rawBytes, titleOverride);
            if (isSuperseded(documentId, sequence)) {
                log.debug("Aborting superseded ingestion after parse for document {} ({})", documentId, filename);
                return;
            }
            List<DocumentParser.ParsedChunk> chunks = parsed.chunks();
            List<float[]> vectors = embedAll(parsed.title(), chunks);
            if (isSuperseded(documentId, sequence)) {
                log.debug("Aborting superseded ingestion after embed for document {} ({})", documentId, filename);
                return;
            }

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
            boolean applied = repository.replaceChunks(documentId, expectedSha256, parsed.title(), records, clock.instant());
            if (applied) {
                log.info("Indexed RAG document {} ({}) into {} chunks", documentId, filename, records.size());
            } else {
                log.info("Skipped writing stale chunks for document {} ({})", documentId, filename);
            }
        } catch (RuntimeException exception) {
            if (isSuperseded(documentId, sequence)) {
                return;
            }
            log.warn("Failed to index RAG document {} ({}): {}", documentId, filename, exception.getMessage());
            String message = exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
            try {
                repository.updateStatusIfMatches(
                    documentId, expectedSha256, RagDocument.STATUS_FAILED, 0, message, clock.instant()
                );
            } catch (RuntimeException updateException) {
                log.debug("Unable to persist FAILED status for document {}", documentId, updateException);
            }
        }
    }

    private long nextSequence(UUID documentId) {
        return documentSequences.computeIfAbsent(documentId, ignored -> new AtomicLong()).incrementAndGet();
    }

    private boolean isSuperseded(UUID documentId, long sequence) {
        AtomicLong latest = documentSequences.get(documentId);
        return latest != null && latest.get() != sequence;
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
                    inputs.add(embeddingInput(documentTitle, documentLead, chunk));
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

    static String extractDocumentLead(List<DocumentParser.ParsedChunk> chunks) {
        if (chunks.isEmpty()) {
            return "";
        }
        String first = chunks.getFirst().content();
        if (first == null || first.isBlank()) {
            return "";
        }
        String singleLine = first.replaceAll("\\s+", " ").strip();
        return TextDocumentParser.takeTokens(singleLine, TextDocumentParser.LEAD_TOKEN_BUDGET).strip();
    }

    static String embeddingInput(String documentTitle, String documentLead, DocumentParser.ParsedChunk chunk) {
        String title = TextDocumentParser.takeTokens(
            documentTitle == null ? "" : documentTitle.strip(),
            TextDocumentParser.TITLE_TOKEN_BUDGET
        );
        String heading = TextDocumentParser.takeTokens(
            chunk.heading() == null ? "" : chunk.heading().strip(),
            TextDocumentParser.HEADING_TOKEN_BUDGET
        );
        if (heading.equals(title)) {
            heading = "";
        }
        String lead = TextDocumentParser.takeTokens(
            documentLead == null ? "" : documentLead.strip(),
            TextDocumentParser.LEAD_TOKEN_BUDGET
        );
        String content = chunk.content() == null ? "" : chunk.content();
        StringBuilder builder = new StringBuilder();
        if (!title.isEmpty()) {
            builder.append(title);
        }
        if (!heading.isEmpty()) {
            if (!builder.isEmpty()) {
                builder.append(" - ");
            }
            builder.append(heading);
        }
        if (!lead.isEmpty()) {
            builder.append('\n').append(lead);
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(content);
        return builder.toString();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
