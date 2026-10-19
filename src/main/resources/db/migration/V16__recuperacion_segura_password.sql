-- V16: tokens de recuperación de contraseña de un solo uso, almacenados como SHA-256.

CREATE TABLE IF NOT EXISTS password_reset_tokens (
    token_hash VARCHAR(64) PRIMARY KEY CHECK (length(token_hash) = 64),
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    used_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_user_id
    ON password_reset_tokens(user_id);

CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_expires_at
    ON password_reset_tokens(expires_at);
