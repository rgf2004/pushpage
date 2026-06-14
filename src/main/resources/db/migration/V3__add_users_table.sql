CREATE TABLE IF NOT EXISTS users (
    id           TEXT PRIMARY KEY,
    username     TEXT NOT NULL UNIQUE,
    email        TEXT,
    api_key_hash TEXT NOT NULL UNIQUE,
    created_at   TIMESTAMP NOT NULL,
    active       INTEGER NOT NULL DEFAULT 1,
    admin        INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_users_api_key_hash ON users (api_key_hash);

ALTER TABLE pages ADD COLUMN user_id TEXT REFERENCES users(id);
