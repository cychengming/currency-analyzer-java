package com.currencyanalyzer.ai.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.rag.sync-on-startup", havingValue = "true")
public class RagStartupSyncRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RagStartupSyncRunner.class);

    private final RagDocumentService documentService;

    public RagStartupSyncRunner(RagDocumentService documentService) {
        this.documentService = documentService;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread worker = new Thread(this::syncSafely, "rag-startup-sync");
        worker.setDaemon(true);
        worker.start();
    }

    void syncSafely() {
        try {
            int recovered = documentService.recoverStuckDocuments();
            if (recovered > 0) {
                log.info("Recovered {} stuck RAG document(s) on startup", recovered);
            }
            RagSyncSummary summary = documentService.syncFromStorage(null, "system");
            log.info(
                "Startup RAG storage sync complete for {}/{}: scanned={}, queued={}, skipped={}, ignored={}, deleted={}",
                summary.bucket(),
                summary.prefix(),
                summary.scanned(),
                summary.queued(),
                summary.skipped(),
                summary.ignored(),
                summary.deleted()
            );
        } catch (RuntimeException exception) {
            log.warn("Startup RAG storage sync skipped ({})", exception.getMessage());
        }
    }
}
