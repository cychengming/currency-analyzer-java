package com.currencyanalyzer.ai.rag;

import com.currencyanalyzer.ai.knowledge.KnowledgeLibrary;
import com.currencyanalyzer.ai.knowledge.KnowledgeNote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.rag.seed-builtin-guides", havingValue = "true", matchIfMissing = true)
public class BuiltinGuideSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BuiltinGuideSeeder.class);
    private static final String CLASSPATH_PROVIDER = "classpath";

    private final KnowledgeLibrary knowledgeLibrary;
    private final RagDocumentRepository repository;
    private final RagIngestionService ingestionService;
    private final Clock clock;

    public BuiltinGuideSeeder(
        KnowledgeLibrary knowledgeLibrary,
        RagDocumentRepository repository,
        RagIngestionService ingestionService,
        Clock clock
    ) {
        this.knowledgeLibrary = knowledgeLibrary;
        this.repository = repository;
        this.ingestionService = ingestionService;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedNow(true);
    }

    public int seedNow(boolean async) {
        Set<String> activeKeys = new HashSet<>();
        int queued = 0;

        for (KnowledgeNote note : knowledgeLibrary.notes()) {
            String key = note.path();
            activeKeys.add(key);
            byte[] bytes = note.body().getBytes(StandardCharsets.UTF_8);
            String sha256 = RagDocumentService.sha256Hex(bytes);
            String filename = extractFilename(key);

            Optional<RagDocument> existing = repository.findByStorage(CLASSPATH_PROVIDER, key);
            if (existing.isPresent()
                && RagDocument.STATUS_READY.equals(existing.get().status())
                && sha256.equals(existing.get().sha256())) {
                continue;
            }

            Instant now = clock.instant();
            UUID id;
            if (existing.isPresent()) {
                id = existing.get().id();
                repository.updateForReindex(
                    id,
                    filename,
                    note.title(),
                    "text/markdown; charset=utf-8",
                    bytes.length,
                    sha256,
                    null,
                    now
                );
            } else {
                id = UUID.randomUUID();
                repository.insert(new RagDocument(
                    id,
                    RagDocument.SOURCE_BUILTIN,
                    filename,
                    note.title(),
                    "text/markdown; charset=utf-8",
                    bytes.length,
                    sha256,
                    CLASSPATH_PROVIDER,
                    null,
                    key,
                    null,
                    RagDocument.STATUS_PENDING,
                    0,
                    null,
                    "system",
                    now,
                    now
                ));
            }

            if (async) {
                ingestionService.enqueue(id, sha256, filename, null, bytes);
            } else {
                ingestionService.ingestNow(id, sha256, filename, null, bytes);
            }
            queued++;
        }

        for (RagDocument existingBuiltin : repository.findBySource(RagDocument.SOURCE_BUILTIN)) {
            if (!activeKeys.contains(existingBuiltin.storageKey())) {
                repository.deleteById(existingBuiltin.id());
                log.info("Removed obsolete built-in guide {}", existingBuiltin.storageKey());
            }
        }

        if (queued > 0) {
            log.info("Queued {} built-in application guide(s) for RAG indexing", queued);
        }
        return queued;
    }

    private static String extractFilename(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
