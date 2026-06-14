ALTER TABLE pages ADD COLUMN deleted_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_pages_created_at ON pages (created_at);
