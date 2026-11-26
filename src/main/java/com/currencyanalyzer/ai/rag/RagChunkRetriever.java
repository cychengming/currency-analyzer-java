package com.currencyanalyzer.ai.rag;

import com.currencyanalyzer.ai.chat.AiMetrics;
import com.currencyanalyzer.ai.knowledge.KnowledgeNote;
import com.currencyanalyzer.ai.knowledge.KnowledgeRetriever;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class RagChunkRetriever {

    private static final Logger log = LoggerFactory.getLogger(RagChunkRetriever.class);
    private static final int MIN_INFORMATIVE_CHARS = 45;
    private static final int MIN_EXPANSION_CHARS = 45;
    private static final double STRONG_MATCH_THRESHOLD = 0.70;
    private static final double MAX_SCORE_GAP_BELOW_STRONG_TOP = 0.18;
    private static final int QUERY_EMBEDDING_CONCURRENCY = 2;
    private static final int QUERY_EMBED_TIMEOUT_SECONDS = 10;
    private static final int QUERY_CACHE_MAX_SIZE = 1_000;
    private static final Duration QUERY_CACHE_TTL = Duration.ofMinutes(10);
    static final int RRF_K = 60;

    private final RagDocumentRepository repository;
    private final EmbeddingModel embeddingModel;
    private final double similarityThreshold;
    private final AiMetrics aiMetrics;
    private final Cache<String, float[]> queryEmbeddingCache = Caffeine.newBuilder()
        .maximumSize(QUERY_CACHE_MAX_SIZE)
        .expireAfterWrite(QUERY_CACHE_TTL)
        .build();
    private final ExecutorService queryEmbeddingExecutor = Executors.newFixedThreadPool(
        QUERY_EMBEDDING_CONCURRENCY,
        runnable -> {
            Thread thread = new Thread(runnable, "rag-query-embed");
            thread.setDaemon(true);
            return thread;
        }
    );

    @Autowired
    public RagChunkRetriever(
        RagDocumentRepository repository,
        ObjectProvider<EmbeddingModel> embeddingModelProvider,
        ObjectProvider<AiMetrics> aiMetricsProvider,
        @Value("${app.rag.similarity-threshold:0.45}") double similarityThreshold
    ) {
        this(
            repository,
            embeddingModelProvider.getIfAvailable(),
            similarityThreshold,
            aiMetricsProvider.getIfAvailable(AiMetrics::noop)
        );
    }

    RagChunkRetriever(
        RagDocumentRepository repository,
        EmbeddingModel embeddingModel,
        double similarityThreshold
    ) {
        this(repository, embeddingModel, similarityThreshold, AiMetrics.noop());
    }

    RagChunkRetriever(
        RagDocumentRepository repository,
        EmbeddingModel embeddingModel,
        double similarityThreshold,
        AiMetrics aiMetrics
    ) {
        this.repository = repository;
        this.embeddingModel = embeddingModel;
        this.similarityThreshold = similarityThreshold;
        this.aiMetrics = aiMetrics == null ? AiMetrics.noop() : aiMetrics;
    }

    public List<RagChunk.RagSearchHit> search(String query, int limit) {
        if (query == null || query.isBlank() || limit < 1) {
            return List.of();
        }
        long startedAt = System.nanoTime();
        String trimmed = query.strip();
        int candidateLimit = Math.max(limit * 4, 24);

        List<RagChunk.RagSearchHit> vectorHits = vectorSearch(trimmed, candidateLimit);
        List<RagChunk.RagSearchHit> lexicalHits = lexicalSearch(trimmed, candidateLimit);

        List<RagChunk.RagSearchHit> fused;
        String mode;
        if (vectorHits.isEmpty() && lexicalHits.isEmpty()) {
            fused = List.of();
            mode = "empty";
        } else if (vectorHits.isEmpty()) {
            fused = lexicalHits;
            mode = "lexical";
        } else if (lexicalHits.isEmpty()) {
            fused = vectorHits.stream()
                .map(hit -> withScore(hit, adjustHitScore(hit.documentTitle(), hit.filename(), hit.content(), hit.score())))
                .sorted(hitComparator())
                .toList();
            mode = "vector";
        } else {
            fused = fuseWithRrf(vectorHits, lexicalHits);
            mode = "hybrid";
        }

        List<RagChunk.RagSearchHit> expanded = expandStrongDocuments(fused, limit);
        List<RagChunk.RagSearchHit> results = filterAndLimit(expanded, limit);
        aiMetrics.recordRagRetrieval(mode, results.size(), System.nanoTime() - startedAt);
        return results;
    }

    public List<KnowledgeNote> retrieveAsNotes(String query, int maxNotes) {
        return search(query, maxNotes).stream()
            .map(hit -> new KnowledgeNote(
                hit.source() + "/" + hit.filename() + "#chunk-" + hit.chunkIndex(),
                hit.displayTitle(),
                hit.content()
            ))
            .toList();
    }

    private List<RagChunk.RagSearchHit> vectorSearch(String query, int limit) {
        if (embeddingModel == null || !repository.isPgVectorAvailable()) {
            return List.of();
        }
        float[] queryVector = embedQuery(query);
        if (queryVector == null || queryVector.length == 0) {
            return List.of();
        }
        try {
            return repository.searchByVector(queryVector, similarityThreshold, limit);
        } catch (RuntimeException exception) {
            log.warn("Vector retrieval unavailable; falling back to keyword search ({})", exception.getMessage());
            return List.of();
        }
    }

    private float[] embedQuery(String query) {
        long startedAt = System.nanoTime();
        String cacheKey = query.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        float[] cached = queryEmbeddingCache.getIfPresent(cacheKey);
        if (cached != null) {
            aiMetrics.recordRagEmbedding("hit", System.nanoTime() - startedAt);
            return cached;
        }
        Future<float[]> future = null;
        try {
            future = queryEmbeddingExecutor.submit(() -> embeddingModel.embed(query));
            float[] vector = future.get(QUERY_EMBED_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (vector == null || vector.length == 0) {
                aiMetrics.recordRagEmbedding("error", System.nanoTime() - startedAt);
                return null;
            }
            queryEmbeddingCache.put(cacheKey, vector);
            aiMetrics.recordRagEmbedding("miss", System.nanoTime() - startedAt);
            return vector;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (future != null) {
                future.cancel(true);
            }
            aiMetrics.recordRagEmbedding("error", System.nanoTime() - startedAt);
            return null;
        } catch (TimeoutException | ExecutionException | RuntimeException exception) {
            if (future != null) {
                future.cancel(true);
            }
            String reason = exception instanceof ExecutionException && exception.getCause() != null
                ? exception.getCause().getMessage()
                : exception.getMessage();
            log.warn("Query embedding failed; falling back to keyword search ({})", reason);
            aiMetrics.recordRagEmbedding("error", System.nanoTime() - startedAt);
            return null;
        }
    }

    @PreDestroy
    void shutdown() {
        queryEmbeddingExecutor.shutdownNow();
    }

    /**
     * Combines vector and lexical rankings with Reciprocal Rank Fusion (RRF),
     * operating purely on rank positions so retrieval requires no cross-ranker score calibration.
     */
    static List<RagChunk.RagSearchHit> fuseWithRrf(
        List<RagChunk.RagSearchHit> vectorHits,
        List<RagChunk.RagSearchHit> lexicalHits
    ) {
        Map<ChunkKey, RrfCandidate> candidates = new LinkedHashMap<>();

        for (int index = 0; index < vectorHits.size(); index++) {
            RagChunk.RagSearchHit hit = vectorHits.get(index);
            ChunkKey key = new ChunkKey(hit.documentId(), hit.chunkIndex());
            int rank = index + 1;
            candidates.put(key, new RrfCandidate(hit, rank, 0));
        }

        for (int index = 0; index < lexicalHits.size(); index++) {
            RagChunk.RagSearchHit hit = lexicalHits.get(index);
            ChunkKey key = new ChunkKey(hit.documentId(), hit.chunkIndex());
            int rank = index + 1;
            RrfCandidate existing = candidates.get(key);
            if (existing != null) {
                RagChunk.RagSearchHit preferred = preferredHit(existing.hit(), hit);
                candidates.put(key, new RrfCandidate(preferred, existing.vectorRank(), rank));
            } else {
                candidates.put(key, new RrfCandidate(hit, 0, rank));
            }
        }

        double maxDualRrf = 2.0 / (RRF_K + 1.0);
        return candidates.values().stream()
            .map(candidate -> {
                double rawRrf = 0.0;
                if (candidate.vectorRank() > 0) {
                    rawRrf += 1.0 / (RRF_K + candidate.vectorRank());
                }
                if (candidate.lexicalRank() > 0) {
                    rawRrf += 1.0 / (RRF_K + candidate.lexicalRank());
                }
                double normalized = Math.min(1.0, rawRrf / maxDualRrf);
                RagChunk.RagSearchHit hit = candidate.hit();
                double adjusted = adjustHitScore(hit.documentTitle(), hit.filename(), hit.content(), normalized);
                return withScore(hit, adjusted);
            })
            .sorted(hitComparator())
            .toList();
    }

    /**
     * When a query strongly identifies a primary document (for example an English entity name
     * inside a Chinese profile's intro section), also surface that document's substantive core
     * chunks via a targeted document-ID query instead of scanning the entire table.
     */
    private List<RagChunk.RagSearchHit> expandStrongDocuments(
        List<RagChunk.RagSearchHit> ranked,
        int limit
    ) {
        if (ranked.isEmpty()) {
            return ranked;
        }
        Map<UUID, Double> strongDocScores = new HashMap<>();
        for (RagChunk.RagSearchHit hit : ranked) {
            if (hit.score() >= STRONG_MATCH_THRESHOLD && !isChangelogLike(hit.documentTitle(), hit.filename())) {
                if (hit.chunkIndex() <= 1 || hit.score() >= 0.80) {
                    strongDocScores.merge(hit.documentId(), hit.score(), Math::max);
                }
            }
        }
        if (strongDocScores.isEmpty()) {
            return ranked;
        }

        List<RagDocumentRepository.ReadyChunk> siblingChunks =
            repository.findReadyChunksByDocumentIds(strongDocScores.keySet());
        if (siblingChunks == null || siblingChunks.isEmpty()) {
            return ranked;
        }

        Map<ChunkKey, RagChunk.RagSearchHit> byKey = new LinkedHashMap<>();
        for (RagChunk.RagSearchHit hit : ranked) {
            byKey.put(new ChunkKey(hit.documentId(), hit.chunkIndex()), hit);
        }

        int maxSiblingsPerDoc = Math.max(1, limit - 1);
        Map<UUID, Integer> addedPerDoc = new HashMap<>();
        for (RagDocumentRepository.ReadyChunk chunk : siblingChunks) {
            Double parentScore = strongDocScores.get(chunk.documentId());
            if (parentScore == null) {
                continue;
            }
            ChunkKey key = new ChunkKey(chunk.documentId(), chunk.chunkIndex());
            if (byKey.containsKey(key)) {
                continue;
            }
            String content = chunk.content() == null ? "" : chunk.content().strip();
            if (content.length() < MIN_EXPANSION_CHARS) {
                continue;
            }
            int alreadyAdded = addedPerDoc.getOrDefault(chunk.documentId(), 0);
            if (alreadyAdded >= maxSiblingsPerDoc) {
                continue;
            }
            double siblingScore = Math.max(similarityThreshold, parentScore - 0.08 - (alreadyAdded * 0.02));
            byKey.put(key, new RagChunk.RagSearchHit(
                chunk.documentId(),
                chunk.documentTitle(),
                chunk.filename(),
                chunk.source(),
                chunk.chunkIndex(),
                chunk.heading(),
                chunk.content(),
                siblingScore
            ));
            addedPerDoc.put(chunk.documentId(), alreadyAdded + 1);
        }

        return byKey.values().stream()
            .sorted(hitComparator())
            .toList();
    }

    private List<RagChunk.RagSearchHit> lexicalSearch(String query, int limit) {
        String normalized = query.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        List<String> phrases = KnowledgeRetriever.cjkPhrases(normalized);
        List<String> tokens = KnowledgeRetriever.latinTokens(normalized);
        List<String> sqlTerms = buildSqlTerms(phrases, tokens);
        int sqlCandidateLimit = Math.max(limit * 3, 60);

        List<RagDocumentRepository.ReadyChunk> candidates =
            repository.searchLexicalCandidates(normalized, sqlTerms, sqlCandidateLimit);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return scoreLexicalCandidates(normalized, phrases, tokens, candidates, limit);
    }

    private static List<String> buildSqlTerms(List<String> phrases, List<String> tokens) {
        Set<String> terms = new LinkedHashSet<>();
        for (String phrase : phrases) {
            if (phrase.length() >= 2) {
                terms.add(phrase);
            }
        }
        for (String token : tokens) {
            if (token.length() >= 2) {
                terms.add(token);
            }
        }
        return List.copyOf(terms);
    }

    private List<RagChunk.RagSearchHit> scoreLexicalCandidates(
        String normalized,
        List<String> phrases,
        List<String> tokens,
        List<RagDocumentRepository.ReadyChunk> candidates,
        int limit
    ) {
        List<RagChunk.RagSearchHit> scored = new ArrayList<>();
        for (RagDocumentRepository.ReadyChunk chunk : candidates) {
            String title = chunk.documentTitle() == null ? "" : chunk.documentTitle();
            String heading = chunk.heading() == null ? "" : chunk.heading();
            String content = chunk.content() == null ? "" : chunk.content();
            String combinedTitle = heading.isBlank() ? title : title + " " + heading;

            int score = KnowledgeRetriever.scoreText(combinedTitle, content, normalized, phrases, tokens);

            if (normalized.length() >= 3) {
                String lowerTitle = combinedTitle.toLowerCase(Locale.ROOT);
                String lowerContent = content.toLowerCase(Locale.ROOT);
                if (lowerTitle.contains(normalized)) {
                    score += 42;
                } else if (lowerContent.contains(normalized)) {
                    score += 30;
                    if (chunk.chunkIndex() <= 1) {
                        score += 12;
                    }
                }
            }

            if (score >= KnowledgeRetriever.minimumScore()) {
                double normalizedScore = Math.min(0.99, 0.45 + (score / 70.0) * 0.54);
                normalizedScore = adjustHitScore(title, chunk.filename(), content, normalizedScore);
                scored.add(new RagChunk.RagSearchHit(
                    chunk.documentId(),
                    chunk.documentTitle(),
                    chunk.filename(),
                    chunk.source(),
                    chunk.chunkIndex(),
                    chunk.heading(),
                    chunk.content(),
                    normalizedScore
                ));
            }
        }
        scored.sort(hitComparator());
        if (scored.size() > limit) {
            return List.copyOf(scored.subList(0, limit));
        }
        return List.copyOf(scored);
    }

    private List<RagChunk.RagSearchHit> filterAndLimit(List<RagChunk.RagSearchHit> hits, int limit) {
        if (hits.isEmpty()) {
            return List.of();
        }
        double topScore = hits.getFirst().score();
        double floor = topScore >= STRONG_MATCH_THRESHOLD
            ? Math.max(similarityThreshold, topScore - MAX_SCORE_GAP_BELOW_STRONG_TOP)
            : similarityThreshold;

        List<RagChunk.RagSearchHit> filtered = new ArrayList<>();
        for (RagChunk.RagSearchHit hit : hits) {
            if (hit.score() < floor) {
                break;
            }
            filtered.add(hit);
            if (filtered.size() >= limit) {
                break;
            }
        }
        return List.copyOf(filtered);
    }

    private static double adjustHitScore(String title, String filename, String content, double score) {
        double adjusted = score;
        String trimmed = content == null ? "" : content.strip();
        if (trimmed.length() < MIN_INFORMATIVE_CHARS) {
            adjusted *= 0.60;
        }
        if (isChangelogLike(title, filename)) {
            adjusted *= 0.80;
        }
        return adjusted;
    }

    private static boolean isChangelogLike(String title, String filename) {
        String combined = ((title == null ? "" : title) + " " + (filename == null ? "" : filename))
            .toLowerCase(Locale.ROOT);
        return combined.contains("更新日志") || combined.contains("changelog") || combined.contains("release-notes");
    }

    private static RagChunk.RagSearchHit preferredHit(RagChunk.RagSearchHit first, RagChunk.RagSearchHit second) {
        int firstLen = first.content() == null ? 0 : first.content().length();
        int secondLen = second.content() == null ? 0 : second.content().length();
        return secondLen > firstLen ? second : first;
    }

    private static RagChunk.RagSearchHit withScore(RagChunk.RagSearchHit hit, double score) {
        return new RagChunk.RagSearchHit(
            hit.documentId(),
            hit.documentTitle(),
            hit.filename(),
            hit.source(),
            hit.chunkIndex(),
            hit.heading(),
            hit.content(),
            score
        );
    }

    private static Comparator<RagChunk.RagSearchHit> hitComparator() {
        return Comparator.comparingDouble(RagChunk.RagSearchHit::score).reversed()
            .thenComparing(RagChunk.RagSearchHit::documentTitle)
            .thenComparingInt(RagChunk.RagSearchHit::chunkIndex);
    }

    private record ChunkKey(UUID documentId, int chunkIndex) {
    }

    private record RrfCandidate(RagChunk.RagSearchHit hit, int vectorRank, int lexicalRank) {
    }
}
