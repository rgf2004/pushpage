-- Named API keys: an account can have several, each created and revoked on its own.
-- Only the SHA-256 hash of a key is stored; key_prefix is the first characters of the raw key, shown in lists.
CREATE TABLE api_keys (
    id           TEXT      PRIMARY KEY,
    user_id      TEXT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name         TEXT      NOT NULL,
    key_hash     TEXT      NOT NULL UNIQUE,
    key_prefix   TEXT      NOT NULL,
    created_at   TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP
);

CREATE INDEX idx_api_keys_user_id ON api_keys (user_id);

-- Carry over each existing single key as "Default key" (its prefix cannot be recovered from the hash).
INSERT INTO api_keys (id, user_id, name, key_hash, key_prefix, created_at)
SELECT substr(md5(id || api_key_hash), 1, 8), id, 'Default key', api_key_hash, 'pp_...', created_at
FROM users;

-- The single-key column is replaced by the table above.
ALTER TABLE users DROP COLUMN api_key_hash;
