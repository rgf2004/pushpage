-- Sessions (JWTs) issued before this instant are rejected; set whenever a password changes.
ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMP;

-- Single-use password reset tokens. Only the SHA-256 hash of the token is stored.
-- A user has at most one outstanding token (issuing a new one replaces the old).
CREATE TABLE password_reset_tokens (
    token_hash      TEXT      PRIMARY KEY,
    user_id         TEXT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    expires_at      TIMESTAMP NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    -- true when the token was delivered to the user's mailbox (proves ownership of the address)
    email_delivered BOOLEAN   NOT NULL DEFAULT false
);

CREATE INDEX idx_password_reset_tokens_user_id ON password_reset_tokens (user_id);
