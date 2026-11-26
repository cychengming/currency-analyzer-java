ALTER TABLE users ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'MEMBER';
ALTER TABLE users ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE users ADD COLUMN display_name VARCHAR(120);
ALTER TABLE users ADD COLUMN last_login_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE users ADD CONSTRAINT chk_users_role
    CHECK (role IN ('ADMIN', 'MEMBER', 'VIEWER'));
ALTER TABLE users ADD CONSTRAINT chk_users_status
    CHECK (status IN ('ACTIVE', 'DISABLED'));

UPDATE users
SET role = 'ADMIN'
WHERE LOWER(username) = 'admin'
   OR id IN (SELECT MIN(id) FROM users);

CREATE TABLE user_invites (
    id UUID PRIMARY KEY,
    code_prefix VARCHAR(16) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    role VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
    note VARCHAR(255),
    created_by VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_by VARCHAR(255),
    used_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_user_invites_role CHECK (role IN ('ADMIN', 'MEMBER', 'VIEWER'))
);

CREATE INDEX idx_user_invites_created ON user_invites (created_at DESC);
