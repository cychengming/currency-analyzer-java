package com.currencyanalyzer.ai.rag;

import com.currencyanalyzer.auth.KnowledgeAdminService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/rag")
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true", matchIfMissing = true)
public class RagDocumentController {

    private final RagDocumentService documentService;
    private final RagChunkRetriever chunkRetriever;
    private final KnowledgeAdminService knowledgeAdminService;

    public RagDocumentController(
        RagDocumentService documentService,
        RagChunkRetriever chunkRetriever,
        KnowledgeAdminService knowledgeAdminService
    ) {
        this.documentService = documentService;
        this.chunkRetriever = chunkRetriever;
        this.knowledgeAdminService = knowledgeAdminService;
    }

    @GetMapping("/info")
    public RagDocumentService.StorageInfo info(Authentication authentication) {
        return documentService.storageInfo(isAdmin(authentication));
    }

    @GetMapping("/documents")
    public List<RagDocument> list() {
        return documentService.list();
    }

    @GetMapping("/documents/{id}")
    public ResponseEntity<RagDocument> get(@PathVariable UUID id) {
        return documentService.findById(id)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RagDocument> upload(
        Authentication authentication,
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "title", required = false) String title
    ) throws IOException {
        requireAdmin(authentication);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file must not be empty");
        }
        RagDocument created = documentService.upload(
            file.getOriginalFilename(),
            file.getBytes(),
            title,
            authentication.getName()
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(created);
    }

    @PostMapping("/sync")
    public ResponseEntity<RagSyncSummary> syncFromStorage(
        Authentication authentication,
        @RequestParam(value = "prefix", required = false) String prefix
    ) {
        requireAdmin(authentication);
        RagSyncSummary summary = documentService.syncFromStorage(prefix, authentication.getName());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(summary);
    }

    @PostMapping("/documents/{id}/reindex")
    public ResponseEntity<RagDocument> reindex(
        Authentication authentication,
        @PathVariable UUID id
    ) {
        requireAdmin(authentication);
        return documentService.reindex(id)
            .map(doc -> ResponseEntity.status(HttpStatus.ACCEPTED).body(doc))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/documents/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        Optional<RagDocumentService.DownloadedDocument> downloaded = documentService.download(id);
        if (downloaded.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        RagDocumentService.DownloadedDocument payload = downloaded.get();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "plain", StandardCharsets.UTF_8));
        headers.setContentDisposition(
            ContentDisposition.attachment().filename(payload.document().filename(), StandardCharsets.UTF_8).build()
        );
        headers.add("X-Content-Type-Options", "nosniff");
        return ResponseEntity.ok().headers(headers).body(payload.bytes());
    }

    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Map<String, Object>> delete(
        Authentication authentication,
        @PathVariable UUID id
    ) {
        requireAdmin(authentication);
        if (!documentService.delete(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "success", false,
                "error", "Document not found"
            ));
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/search")
    public Map<String, Object> search(
        @RequestParam("q") String query,
        @RequestParam(value = "limit", defaultValue = "4") int limit
    ) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("q must not be blank");
        }
        int boundedLimit = Math.max(1, Math.min(limit, 20));
        List<RagChunk.RagSearchHit> hits = chunkRetriever.search(query.trim(), boundedLimit);
        return Map.of(
            "query", query.trim(),
            "count", hits.size(),
            "hits", hits
        );
    }

    private void requireAdmin(Authentication authentication) {
        if (!isAdmin(authentication)) {
            throw new AccessDeniedException("Admin privileges are required to modify the shared knowledge base");
        }
    }

    private boolean isAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        boolean hasAdminRole = authentication.getAuthorities() != null
            && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (hasAdminRole) {
            return true;
        }
        return knowledgeAdminService.isAdminUsername(authentication.getName());
    }
}
