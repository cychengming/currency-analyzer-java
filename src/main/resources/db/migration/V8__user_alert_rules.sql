CREATE TABLE alert_rules (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    pair VARCHAR(64) NOT NULL,
    nickname VARCHAR(120),
    condition_type VARCHAR(32) NOT NULL,
    target_value DOUBLE PRECISION,
    window_days INTEGER NOT NULL DEFAULT 30,
    ma_short_period INTEGER NOT NULL DEFAULT 20,
    ma_long_period INTEGER NOT NULL DEFAULT 50,
    signal_type VARCHAR(32) NOT NULL DEFAULT 'both',
    frequency VARCHAR(16) NOT NULL DEFAULT 'ONCE',
    cooldown_seconds INTEGER NOT NULL DEFAULT 3600,
    notify_email BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(16) NOT NULL DEFAULT 'ARMED',
    last_evaluated_rate DOUBLE PRECISION,
    last_triggered_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_alert_rules_condition CHECK (
        condition_type IN ('cross_above', 'cross_below', 'pct_move', 'ma_crossover')
    ),
    CONSTRAINT chk_alert_rules_frequency CHECK (
        frequency IN ('ONCE', 'RECURRING')
    ),
    CONSTRAINT chk_alert_rules_status CHECK (
        status IN ('ARMED', 'TRIGGERED', 'PAUSED')
    )
);

CREATE INDEX idx_alert_rules_user_created ON alert_rules (user_id, created_at DESC);
CREATE INDEX idx_alert_rules_status_pair ON alert_rules (status, pair);

CREATE TABLE user_alert_settings (
    user_id BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    alert_email VARCHAR(255) NOT NULL DEFAULT '',
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE user_favorite_pairs (
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    pair VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, pair)
);

ALTER TABLE alerts ADD COLUMN user_id BIGINT REFERENCES users (id) ON DELETE CASCADE;
ALTER TABLE alerts ADD COLUMN rule_id UUID;
ALTER TABLE alerts ADD COLUMN rule_name VARCHAR(120);
ALTER TABLE alerts ADD COLUMN read_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_alerts_user_timestamp ON alerts (user_id, timestamp DESC);
