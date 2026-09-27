-- Backfill existing users as verified (DEFAULT true fills current rows),
-- then flip the default so new sign-ups start out unverified.
ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE users ALTER COLUMN email_verified SET DEFAULT false;

ALTER TABLE users ADD COLUMN verification_token TEXT;
ALTER TABLE users ADD COLUMN verification_token_expires_at TIMESTAMP;

CREATE INDEX idx_users_verification_token ON users (verification_token);
