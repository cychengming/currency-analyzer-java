package com.currencyanalyzer.ai.rag;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagDocumentRepositoryTests {

    @Test
    void detectsPgVectorAndRunsVectorInsertAndCosineSearch() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);

        when(jdbcTemplate.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(jdbcTemplate.queryForObject(contains("information_schema.columns"), eq(Integer.class))).thenReturn(1);

        UUID docId = UUID.randomUUID();
        RagChunk.RagSearchHit hit = new RagChunk.RagSearchHit(
            docId, "Guide", "guide.md", "builtin", 0, "Intro", "Body content", 0.88
        );
        when(jdbcTemplate.query(
            contains("c.embedding <=> CAST(? AS vector)"),
            any(RowMapper.class),
            any(), any(), any(), any(), any()
        )).thenReturn(List.of(hit));

        RagDocumentRepository repository = new RagDocumentRepository(jdbcTemplate);
        assertTrue(repository.isPgVectorAvailable());

        repository.replaceChunks(
            docId,
            "Guide",
            List.of(
                new RagDocumentRepository.ChunkRecord(0, "Intro", "Body content", new float[] {0.1f, 0.2f}),
                new RagDocumentRepository.ChunkRecord(1, null, "Plain chunk", null)
            ),
            Instant.parse("2026-11-22T12:00:00Z")
        );
        verify(jdbcTemplate).update(
            contains("CAST(? AS vector)"),
            any(), eq(docId), eq(0), eq("Intro"), eq("Body content"), eq(12), eq("[0.1,0.2]"), any()
        );

        List<RagChunk.RagSearchHit> hits = repository.searchByVector(new float[] {0.1f, 0.2f}, 0.45, 5);
        assertEquals(1, hits.size());
        assertEquals(0.88, hits.getFirst().score());

        assertTrue(repository.searchByVector(null, 0.45, 5).isEmpty());
        assertTrue(repository.searchByVector(new float[0], 0.45, 5).isEmpty());
        assertTrue(repository.searchByVector(new float[] {1f}, 0.45, 0).isEmpty());

        RagDocumentRepository.ChunkRecord r1 = new RagDocumentRepository.ChunkRecord(0, null, null, null);
        assertEquals(r1, r1);
        assertNotEquals(r1, "not-a-record");
        assertTrue(r1.toString().contains("contentLength=0"));
        assertTrue(r1.toString().contains("embeddingDim=0"));
    }

    @Test
    void returnsFalseWhenDataSourceIsMissingOrThrows() {
        JdbcTemplate noDs = mock(JdbcTemplate.class);
        when(noDs.getDataSource()).thenReturn(null);
        RagDocumentRepository repo = new RagDocumentRepository(noDs);
        assertFalse(repo.isPgVectorAvailable());
        assertTrue(repo.searchByVector(new float[] {1f}, 0.45, 3).isEmpty());
    }
}
