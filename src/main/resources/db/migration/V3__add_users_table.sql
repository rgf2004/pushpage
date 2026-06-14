CREATE TABLE IF NOT EXISTS users (
    id         TEXT PRIMARY KEY,
    username   TEXT NOT NULL UNIQUE,
    api_key    TEXT NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL,
    active     INTEGER NOT NULL DEFAULT 1,
    admin      INTEGER NOT NULL DEFAULT 0
);

ALTER TABLE pages ADD COLUMN user_id TEXT REFERENCES users(id);
