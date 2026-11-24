-- OAuth 2.1 authorization server for the hosted MCP endpoint (public clients, PKCE).
-- Codes and refresh tokens are stored as SHA-256 hashes only.
CREATE TABLE oauth_clients (
    client_id     TEXT      PRIMARY KEY,
    client_name   TEXT      NOT NULL,
    redirect_uris TEXT      NOT NULL,  -- JSON array
    created_at    TIMESTAMP NOT NULL
);

CREATE TABLE oauth_codes (
    code_hash      TEXT      PRIMARY KEY,
    client_id      TEXT      NOT NULL REFERENCES oauth_clients (client_id) ON DELETE CASCADE,
    user_id        TEXT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    redirect_uri   TEXT      NOT NULL,
    code_challenge TEXT      NOT NULL,
    scope          TEXT      NOT NULL,
    expires_at     TIMESTAMP NOT NULL
);

CREATE TABLE oauth_refresh_tokens (
    token_hash       TEXT      PRIMARY KEY,
    client_id        TEXT      NOT NULL REFERENCES oauth_clients (client_id) ON DELETE CASCADE,
    user_id          TEXT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    scope            TEXT      NOT NULL,
    password_version BIGINT    NOT NULL,  -- the user's password version at issue; a password reset invalidates the token
    created_at       TIMESTAMP NOT NULL,
    expires_at       TIMESTAMP NOT NULL
);

CREATE INDEX idx_oauth_refresh_user ON oauth_refresh_tokens (user_id);
