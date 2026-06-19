ALTER TABLE pages ADD COLUMN expires_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_pages_expires_at ON pages (expires_at);
