package com.currencyanalyzer.ai.rag;

public record RagSyncSummary(
    String provider,
    String bucket,
    String prefix,
    int scanned,
    int queued,
    int skipped,
    int ignored,
    int deleted
) {
}
