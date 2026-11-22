package com.currencyanalyzer.ai.rag;

import com.currencyanalyzer.CurrencyAnalyzerApplication;
import com.currencyanalyzer.storage.ObjectStorageClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CurrencyAnalyzerApplication.class)
@AutoConfigureMockMvc
class RagDocumentControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectStorageClient storageClient;

    @Autowired
    private RagDocumentRepository repository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM rag_chunks");
        jdbcTemplate.update("DELETE FROM rag_documents");
        for (ObjectStorageClient.StoredObjectSummary item : storageClient.list("")) {
            storageClient.delete(item.key());
        }
    }

    @Test
    void requiresAuthenticationAndCsrf() throws Exception {
        mockMvc.perform(get("/api/rag/documents"))
            .andExpect(status().isUnauthorized());

        MockMultipartFile file = new MockMultipartFile(
            "file", "note.md", "text/markdown", "# Title\nBody text".getBytes(StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart("/api/rag/documents").file(file).with(user("alice")))
            .andExpect(status().isForbidden());
    }

    @Test
    void uploadsSearchesDownloadsReindexesAndDeletesDocument() throws Exception {
        byte[] content = "# Liquidity Cycles\n\nCentral bank balance sheets drive global dollar liquidity.\n"
            .getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "liquidity.md", "text/markdown", content);

        MvcResult uploadResult = mockMvc.perform(multipart("/api/rag/documents")
                .file(file)
                .with(user("alice"))
                .with(csrf()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.filename").value("liquidity.md"))
            .andExpect(jsonPath("$.title").value("Liquidity Cycles"))
            .andExpect(jsonPath("$.uploadedBy").value("alice"))
            .andReturn();

        JsonNode body = JsonMapper.builder().build().readTree(uploadResult.getResponse().getContentAsString());
        UUID id = UUID.fromString(body.get("id").asText());

        awaitReady(id);

        mockMvc.perform(get("/api/rag/documents").with(user("alice")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value("READY"));

        mockMvc.perform(get("/api/rag/search").param("q", "dollar liquidity cycles").with(user("alice")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.hits[0].documentTitle").value("Liquidity Cycles"));

        mockMvc.perform(get("/api/rag/documents/{id}/download", id).with(user("alice")))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", containsString("attachment")))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        mockMvc.perform(post("/api/rag/documents/{id}/reindex", id).with(user("alice")).with(csrf()))
            .andExpect(status().isAccepted());

        awaitReady(id);

        mockMvc.perform(delete("/api/rag/documents/{id}", id).with(user("alice")).with(csrf()))
            .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/rag/documents/{id}", id).with(user("alice")).with(csrf()))
            .andExpect(status().isNotFound());
    }

    @Test
    void rejectsUnsupportedFileTypesAndSupportsSyncEndpoint() throws Exception {
        MockMultipartFile bad = new MockMultipartFile(
            "file", "report.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart("/api/rag/documents").file(bad).with(user("alice")).with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("Unsupported file type")));

        storageClient.put("knowledge/test.csv", "pair,signal\nEUR/USD,breakout\n".getBytes(StandardCharsets.UTF_8), "text/csv");

        mockMvc.perform(post("/api/rag/sync").param("prefix", "knowledge").with(user("alice")).with(csrf()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.queued").value(1));

        mockMvc.perform(get("/api/rag/info").with(user("alice")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.provider").value("local"));

        UUID unknown = UUID.randomUUID();
        mockMvc.perform(get("/api/rag/documents/{id}", unknown).with(user("alice")))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/rag/documents/{id}/download", unknown).with(user("alice")))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/rag/documents/{id}/reindex", unknown).with(user("alice")).with(csrf()))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/rag/search").param("q", "   ").with(user("alice")))
            .andExpect(status().isBadRequest());
    }

    private void awaitReady(UUID id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (repository.findById(id).map(doc -> RagDocument.STATUS_READY.equals(doc.status())).orElse(false)) {
                return;
            }
            Thread.sleep(25);
        }
    }
}
