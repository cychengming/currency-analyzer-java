CREATE TABLE user_trade_settings (
    username VARCHAR(255) PRIMARY KEY,
    daily_risk_limit_pct DOUBLE PRECISION NOT NULL DEFAULT 3.0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
