CREATE TABLE rag_documents (
    id UUID PRIMARY KEY,
    source VARCHAR(16) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    storage_provider VARCHAR(32) NOT NULL,
    storage_bucket VARCHAR(255),
    storage_key VARCHAR(512) NOT NULL,
    storage_generation VARCHAR(64),
    status VARCHAR(24) NOT NULL,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    uploaded_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_rag_documents_source CHECK (source IN ('builtin', 'upload')),
    CONSTRAINT chk_rag_documents_status CHECK (status IN ('PENDING', 'INDEXING', 'READY', 'FAILED')),
    CONSTRAINT uq_rag_documents_storage UNIQUE (storage_provider, storage_key)
);

CREATE INDEX idx_rag_documents_created ON rag_documents (created_at DESC);
CREATE INDEX idx_rag_documents_status ON rag_documents (status);

CREATE TABLE rag_chunks (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES rag_documents (id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    heading VARCHAR(255),
    content TEXT NOT NULL,
    char_count INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_rag_chunks_document_order ON rag_chunks (document_id, chunk_index);
