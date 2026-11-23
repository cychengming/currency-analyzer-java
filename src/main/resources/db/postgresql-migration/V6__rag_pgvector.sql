DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'vector') THEN
        CREATE EXTENSION IF NOT EXISTS vector;
        ALTER TABLE rag_chunks ADD COLUMN IF NOT EXISTS embedding vector(384);
        CREATE INDEX IF NOT EXISTS idx_rag_chunks_embedding_hnsw
            ON rag_chunks USING hnsw (embedding vector_cosine_ops);
    END IF;
END
$$;
