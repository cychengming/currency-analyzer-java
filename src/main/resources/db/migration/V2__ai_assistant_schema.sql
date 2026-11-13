CREATE TABLE ai_conversations (
    id UUID PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    title VARCHAR(255),
    provider VARCHAR(32) NOT NULL,
    model VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    deleted_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_ai_conversations_user_updated
    ON ai_conversations (username, updated_at DESC);

CREATE TABLE ai_messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES ai_conversations (id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    provider_response_id VARCHAR(128),
    prompt_version VARCHAR(32),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_ai_messages_role CHECK (role IN ('user', 'assistant', 'system'))
);

CREATE INDEX idx_ai_messages_conversation_created
    ON ai_messages (conversation_id, created_at);

CREATE TABLE ai_tool_invocations (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL REFERENCES ai_messages (id) ON DELETE CASCADE,
    tool_name VARCHAR(64) NOT NULL,
    arguments_json JSONB,
    result_summary JSONB,
    status VARCHAR(16) NOT NULL,
    duration_ms INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_ai_tool_invocations_message_created
    ON ai_tool_invocations (message_id, created_at);

CREATE TABLE ai_usage (
    id UUID PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    conversation_id UUID REFERENCES ai_conversations (id) ON DELETE SET NULL,
    model VARCHAR(64) NOT NULL,
    input_tokens INTEGER,
    output_tokens INTEGER,
    estimated_cost_usd NUMERIC(12, 6),
    request_count INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_ai_usage_user_created
    ON ai_usage (username, created_at);
