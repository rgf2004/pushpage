ALTER TABLE users ADD COLUMN password_hash TEXT;
ALTER TABLE users DROP COLUMN IF EXISTS username;

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email_unique ON users (email) WHERE email IS NOT NULL;
