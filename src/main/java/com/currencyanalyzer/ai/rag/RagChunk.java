package com.currencyanalyzer.ai.rag;

import java.time.Instant;
import java.util.UUID;

public record RagChunk(
    UUID id,
    UUID documentId,
    int chunkIndex,
    String heading,
    String content,
    int charCount,
    Instant createdAt
) {
    public record RagSearchHit(
        UUID documentId,
        String documentTitle,
        String filename,
        String source,
        int chunkIndex,
        String heading,
        String content,
        double score
    ) {
        public String displayTitle() {
            if (heading == null || heading.isBlank() || heading.equalsIgnoreCase(documentTitle)) {
                return documentTitle;
            }
            return documentTitle + " — " + heading;
        }
    }
}
