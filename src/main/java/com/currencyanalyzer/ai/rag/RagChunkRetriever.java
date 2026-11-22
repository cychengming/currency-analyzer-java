package com.currencyanalyzer.ai.rag;

import com.currencyanalyzer.ai.knowledge.KnowledgeNote;
import com.currencyanalyzer.ai.knowledge.KnowledgeRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
public class RagChunkRetriever {

    private static final Logger log = LoggerFactory.getLogger(RagChunkRetriever.class);
    private static final int MIN_INFORMATIVE_CHARS = 45;
    private static final int MIN_EXPANSION_CHARS = 45;
    private static final double STRONG_MATCH_THRESHOLD = 0.70;
    private static final double MAX_SCORE_GAP_BELOW_STRONG_TOP = 0.18;

    private final RagDocumentRepository repository;
    private final EmbeddingModel embeddingModel;
    private final double similarityThreshold;

    @Autowired
    public RagChunkRetriever(
        RagDocumentRepository repository,
        ObjectProvider<EmbeddingModel> embeddingModelProvider,
        @Value("${app.rag.similarity-threshold:0.45}") double similarityThreshold
    ) {
        this(repository, embeddingModelProvider.getIfAvailable(), similarityThreshold);
    }

    RagChunkRetriever(
        RagDocumentRepository repository,
        EmbeddingModel embeddingModel,
        double similarityThreshold
    ) {
        this.repository = repository;
        this.embeddingModel = embeddingModel;
        this.similarityThreshold = similarityThreshold;
    }

    public List<RagChunk.RagSearchHit> search(String query, int limit) {
        if (query == null || query.isBlank() || limit < 1) {
            return List.of();
        }
        String trimmed = query.strip();
        int candidateLimit = Math.max(limit * 4, 24);

        List<RagDocumentRepository.ReadyChunk> allChunks = repository.findAllReadyChunks();
        List<RagChunk.RagSearchHit> vectorHits = vectorSearch(trimmed, candidateLimit);
        List<RagChunk.RagSearchHit> lexicalHits = keywordSearch(trimmed, allChunks, candidateLimit);

        List<RagChunk.RagSearchHit> fused;
        if (vectorHits.isEmpty()) {
            fused = lexicalHits;
        } else if (lexicalHits.isEmpty()) {
            fused = vectorHits.stream()
                .map(hit -> withScore(hit, adjustHitScore(hit.documentTitle(), hit.filename(), hit.content(), hit.score())))
                .sorted(hitComparator())
                .toList();
        } else {
            fused = fuseHybrid(vectorHits, lexicalHits);
        }

        List<RagChunk.RagSearchHit> expanded = expandStrongDocuments(fused, allChunks, limit);
        return filterAndLimit(expanded, limit);
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
        try {
            float[] queryVector = embeddingModel.embed(query);
            return repository.searchByVector(queryVector, similarityThreshold, limit);
        } catch (RuntimeException exception) {
            log.warn("Vector retrieval unavailable; falling back to keyword search ({})", exception.getMessage());
            return List.of();
        }
    }

    private List<RagChunk.RagSearchHit> fuseHybrid(
        List<RagChunk.RagSearchHit> vectorHits,
        List<RagChunk.RagSearchHit> lexicalHits
    ) {
        Map<ChunkKey, FusedCandidate> candidates = new LinkedHashMap<>();

        for (RagChunk.RagSearchHit hit : vectorHits) {
            ChunkKey key = new ChunkKey(hit.documentId(), hit.chunkIndex());
            double adjustedVector = adjustHitScore(hit.documentTitle(), hit.filename(), hit.content(), hit.score());
            candidates.put(key, new FusedCandidate(hit, adjustedVector, 0.0));
        }

        for (RagChunk.RagSearchHit hit : lexicalHits) {
            ChunkKey key = new ChunkKey(hit.documentId(), hit.chunkIndex());
            FusedCandidate existing = candidates.get(key);
            if (existing != null) {
                candidates.put(key, new FusedCandidate(existing.hit(), existing.vectorScore(), hit.score()));
            } else {
                candidates.put(key, new FusedCandidate(hit, 0.0, hit.score()));
            }
        }

        return candidates.values().stream()
            .map(candidate -> {
                double combined;
                if (candidate.vectorScore() > 0 && candidate.lexicalScore() > 0) {
                    combined = Math.min(
                        1.0,
                        Math.max(candidate.vectorScore(), candidate.lexicalScore())
                            + 0.18 * Math.min(candidate.vectorScore(), candidate.lexicalScore())
                    );
                } else if (candidate.lexicalScore() > 0) {
                    combined = candidate.lexicalScore();
                } else {
                    combined = candidate.vectorScore();
                }
                return withScore(candidate.hit(), combined);
            })
            .sorted(hitComparator())
            .toList();
    }

    /**
     * When a query strongly identifies a primary document (for example an English entity name
     * inside a Chinese profile's intro section), also surface that document's substantive core
     * chunks so multi-section documents are well represented even across languages.
     */
    private List<RagChunk.RagSearchHit> expandStrongDocuments(
        List<RagChunk.RagSearchHit> ranked,
        List<RagDocumentRepository.ReadyChunk> allChunks,
        int limit
    ) {
        if (ranked.isEmpty() || allChunks.isEmpty()) {
            return ranked;
        }
        Map<UUID, Double> strongDocScores = new HashMap<>();
        for (RagChunk.RagSearchHit hit : ranked) {
            if (hit.score() >= STRONG_MATCH_THRESHOLD && !isChangelogLike(hit.documentTitle(), hit.filename())) {
                // Prefer documents where the match occurs in the intro/profile (chunk 0 or 1) or title
                if (hit.chunkIndex() <= 1 || hit.score() >= 0.80) {
                    strongDocScores.merge(hit.documentId(), hit.score(), Math::max);
                }
            }
        }
        if (strongDocScores.isEmpty()) {
            return ranked;
        }

        Map<ChunkKey, RagChunk.RagSearchHit> byKey = new LinkedHashMap<>();
        for (RagChunk.RagSearchHit hit : ranked) {
            byKey.put(new ChunkKey(hit.documentId(), hit.chunkIndex()), hit);
        }

        int maxSiblingsPerDoc = Math.max(1, limit - 1);
        Map<UUID, Integer> addedPerDoc = new HashMap<>();
        for (RagDocumentRepository.ReadyChunk chunk : allChunks) {
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

    private List<RagChunk.RagSearchHit> keywordSearch(
        String query,
        List<RagDocumentRepository.ReadyChunk> allChunks,
        int limit
    ) {
        String normalized = query.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        List<String> phrases = KnowledgeRetriever.cjkPhrases(normalized);
        List<String> tokens = KnowledgeRetriever.latinTokens(normalized);
        List<RagChunk.RagSearchHit> scored = new ArrayList<>();
        for (RagDocumentRepository.ReadyChunk chunk : allChunks) {
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
                    // Extra signal when exact phrase appears in the opening profile/intro chunk
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

    private record FusedCandidate(RagChunk.RagSearchHit hit, double vectorScore, double lexicalScore) {
    }
}
