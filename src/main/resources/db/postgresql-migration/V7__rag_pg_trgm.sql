DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'pg_trgm') THEN
        CREATE EXTENSION IF NOT EXISTS pg_trgm;
        CREATE INDEX IF NOT EXISTS idx_rag_chunks_content_trgm
            ON rag_chunks USING gin (content gin_trgm_ops);
        CREATE INDEX IF NOT EXISTS idx_rag_chunks_heading_trgm
            ON rag_chunks USING gin (heading gin_trgm_ops);
        CREATE INDEX IF NOT EXISTS idx_rag_documents_title_trgm
            ON rag_documents USING gin (title gin_trgm_ops);
    END IF;
END
$$;
