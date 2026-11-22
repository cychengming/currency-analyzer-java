package com.currencyanalyzer.ai.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RagDocumentRepository {

    private static final Logger log = LoggerFactory.getLogger(RagDocumentRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private volatile Boolean pgVectorAvailable;

    public RagDocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isPgVectorAvailable() {
        Boolean cached = pgVectorAvailable;
        if (cached != null) {
            return cached;
        }
        boolean detected = detectPgVector();
        pgVectorAvailable = detected;
        return detected;
    }

    public RagDocument insert(RagDocument document) {
        OffsetDateTime created = OffsetDateTime.ofInstant(document.createdAt(), ZoneOffset.UTC);
        OffsetDateTime updated = OffsetDateTime.ofInstant(document.updatedAt(), ZoneOffset.UTC);
        jdbcTemplate.update("""
                INSERT INTO rag_documents (
                    id, source, filename, title, content_type, size_bytes, sha256,
                    storage_provider, storage_bucket, storage_key, storage_generation,
                    status, chunk_count, error_message, uploaded_by, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            document.id(),
            document.source(),
            document.filename(),
            document.title(),
            document.contentType(),
            document.sizeBytes(),
            document.sha256(),
            document.storageProvider(),
            document.storageBucket(),
            document.storageKey(),
            document.storageGeneration(),
            document.status(),
            document.chunkCount(),
            document.errorMessage(),
            document.uploadedBy(),
            created,
            updated
        );
        return document;
    }

    public void updateForReindex(
        UUID id,
        String filename,
        String title,
        String contentType,
        long sizeBytes,
        String sha256,
        String storageGeneration,
        Instant now
    ) {
        jdbcTemplate.update("""
                UPDATE rag_documents
                SET filename = ?, title = ?, content_type = ?, size_bytes = ?, sha256 = ?,
                    storage_generation = ?, status = ?, error_message = NULL, updated_at = ?
                WHERE id = ?
                """,
            filename,
            title,
            contentType,
            sizeBytes,
            sha256,
            storageGeneration,
            RagDocument.STATUS_PENDING,
            OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
            id
        );
    }

    public void updateStatus(UUID id, String status, int chunkCount, String errorMessage, Instant now) {
        jdbcTemplate.update("""
                UPDATE rag_documents
                SET status = ?, chunk_count = ?, error_message = ?, updated_at = ?
                WHERE id = ?
                """,
            status,
            chunkCount,
            truncateError(errorMessage),
            OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
            id
        );
    }

    public void updateTitleAndStatus(UUID id, String title, String status, int chunkCount, Instant now) {
        jdbcTemplate.update("""
                UPDATE rag_documents
                SET title = ?, status = ?, chunk_count = ?, error_message = NULL, updated_at = ?
                WHERE id = ?
                """,
            title,
            status,
            chunkCount,
            OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
            id
        );
    }

    public List<RagDocument> findAll() {
        return jdbcTemplate.query("""
                SELECT id, source, filename, title, content_type, size_bytes, sha256,
                       storage_provider, storage_bucket, storage_key, storage_generation,
                       status, chunk_count, error_message, uploaded_by, created_at, updated_at
                FROM rag_documents
                ORDER BY source ASC, created_at DESC, id
                """,
            (rs, rowNum) -> mapDocument(rs)
        );
    }

    public List<RagDocument> findBySource(String source) {
        return jdbcTemplate.query("""
                SELECT id, source, filename, title, content_type, size_bytes, sha256,
                       storage_provider, storage_bucket, storage_key, storage_generation,
                       status, chunk_count, error_message, uploaded_by, created_at, updated_at
                FROM rag_documents
                WHERE source = ?
                ORDER BY created_at DESC, id
                """,
            (rs, rowNum) -> mapDocument(rs),
            source
        );
    }

    public Optional<RagDocument> findById(UUID id) {
        List<RagDocument> rows = jdbcTemplate.query("""
                SELECT id, source, filename, title, content_type, size_bytes, sha256,
                       storage_provider, storage_bucket, storage_key, storage_generation,
                       status, chunk_count, error_message, uploaded_by, created_at, updated_at
                FROM rag_documents
                WHERE id = ?
                """,
            (rs, rowNum) -> mapDocument(rs),
            id
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public Optional<RagDocument> findByStorage(String storageProvider, String storageKey) {
        List<RagDocument> rows = jdbcTemplate.query("""
                SELECT id, source, filename, title, content_type, size_bytes, sha256,
                       storage_provider, storage_bucket, storage_key, storage_generation,
                       status, chunk_count, error_message, uploaded_by, created_at, updated_at
                FROM rag_documents
                WHERE storage_provider = ? AND storage_key = ?
                """,
            (rs, rowNum) -> mapDocument(rs),
            storageProvider,
            storageKey
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public boolean deleteById(UUID id) {
        return jdbcTemplate.update("DELETE FROM rag_documents WHERE id = ?", id) > 0;
    }

    @Transactional
    public void replaceChunks(UUID documentId, String resolvedTitle, List<ChunkRecord> chunks, Instant now) {
        jdbcTemplate.update("DELETE FROM rag_chunks WHERE document_id = ?", documentId);
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        boolean vectorEnabled = isPgVectorAvailable();
        for (ChunkRecord chunk : chunks) {
            UUID chunkId = UUID.randomUUID();
            String heading = chunk.heading() == null ? null : truncate(chunk.heading(), 255);
            if (vectorEnabled && chunk.embedding() != null && chunk.embedding().length > 0) {
                jdbcTemplate.update("""
                        INSERT INTO rag_chunks (id, document_id, chunk_index, heading, content, char_count, embedding, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, CAST(? AS vector), ?)
                        """,
                    chunkId,
                    documentId,
                    chunk.chunkIndex(),
                    heading,
                    chunk.content(),
                    chunk.content().length(),
                    toVectorLiteral(chunk.embedding()),
                    timestamp
                );
            } else {
                jdbcTemplate.update("""
                        INSERT INTO rag_chunks (id, document_id, chunk_index, heading, content, char_count, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                    chunkId,
                    documentId,
                    chunk.chunkIndex(),
                    heading,
                    chunk.content(),
                    chunk.content().length(),
                    timestamp
                );
            }
        }
        updateTitleAndStatus(documentId, resolvedTitle, RagDocument.STATUS_READY, chunks.size(), now);
    }

    public List<RagChunk.RagSearchHit> searchByVector(float[] queryVector, double similarityThreshold, int limit) {
        if (!isPgVectorAvailable() || queryVector == null || queryVector.length == 0 || limit < 1) {
            return List.of();
        }
        String vectorLiteral = toVectorLiteral(queryVector);
        return jdbcTemplate.query("""
                SELECT d.id AS document_id,
                       d.title AS document_title,
                       d.filename AS filename,
                       d.source AS source,
                       c.chunk_index AS chunk_index,
                       c.heading AS heading,
                       c.content AS content,
                       (1.0 - (c.embedding <=> CAST(? AS vector))) AS similarity
                FROM rag_chunks c
                JOIN rag_documents d ON d.id = c.document_id
                WHERE d.status = 'READY'
                  AND c.embedding IS NOT NULL
                  AND (1.0 - (c.embedding <=> CAST(? AS vector))) >= ?
                ORDER BY c.embedding <=> CAST(? AS vector)
                LIMIT ?
                """,
            (rs, rowNum) -> new RagChunk.RagSearchHit(
                rs.getObject("document_id", UUID.class),
                rs.getString("document_title"),
                rs.getString("filename"),
                rs.getString("source"),
                rs.getInt("chunk_index"),
                rs.getString("heading"),
                rs.getString("content"),
                rs.getDouble("similarity")
            ),
            vectorLiteral,
            vectorLiteral,
            similarityThreshold,
            vectorLiteral,
            limit
        );
    }

    public List<ReadyChunk> findAllReadyChunks() {
        return jdbcTemplate.query("""
                SELECT d.id AS document_id,
                       d.title AS document_title,
                       d.filename AS filename,
                       d.source AS source,
                       c.chunk_index AS chunk_index,
                       c.heading AS heading,
                       c.content AS content
                FROM rag_chunks c
                JOIN rag_documents d ON d.id = c.document_id
                WHERE d.status = 'READY'
                ORDER BY d.created_at DESC, c.chunk_index ASC
                """,
            (rs, rowNum) -> new ReadyChunk(
                rs.getObject("document_id", UUID.class),
                rs.getString("document_title"),
                rs.getString("filename"),
                rs.getString("source"),
                rs.getInt("chunk_index"),
                rs.getString("heading"),
                rs.getString("content")
            )
        );
    }

    private boolean detectPgVector() {
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            return false;
        }
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase(Locale.ROOT).contains("postgres")) {
                return false;
            }
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_name = 'rag_chunks' AND column_name = 'embedding'
                    """,
                Integer.class
            );
            boolean available = count != null && count > 0;
            log.info("PostgreSQL pgvector support for rag_chunks: {}", available ? "enabled" : "not found");
            return available;
        } catch (SQLException | RuntimeException exception) {
            log.debug("pgvector detection skipped ({})", exception.getMessage());
            return false;
        }
    }

    static String toVectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 10 + 2);
        builder.append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            float value = Float.isFinite(vector[index]) ? vector[index] : 0.0f;
            builder.append(value);
        }
        builder.append(']');
        return builder.toString();
    }

    private static RagDocument mapDocument(ResultSet rs) throws SQLException {
        return new RagDocument(
            rs.getObject("id", UUID.class),
            rs.getString("source"),
            rs.getString("filename"),
            rs.getString("title"),
            rs.getString("content_type"),
            rs.getLong("size_bytes"),
            rs.getString("sha256"),
            rs.getString("storage_provider"),
            rs.getString("storage_bucket"),
            rs.getString("storage_key"),
            rs.getString("storage_generation"),
            rs.getString("status"),
            rs.getInt("chunk_count"),
            rs.getString("error_message"),
            rs.getString("uploaded_by"),
            instant(rs.getObject("created_at", OffsetDateTime.class)),
            instant(rs.getObject("updated_at", OffsetDateTime.class))
        );
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static String truncateError(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        return truncate(message.strip(), 1000);
    }

    private static String truncate(String value, int maxLen) {
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }

    public record ChunkRecord(int chunkIndex, String heading, String content, float[] embedding) {
    }

    public record ReadyChunk(
        UUID documentId,
        String documentTitle,
        String filename,
        String source,
        int chunkIndex,
        String heading,
        String content
    ) {
    }
}
