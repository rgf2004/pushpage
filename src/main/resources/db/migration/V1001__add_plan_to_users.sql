-- Backfill existing users as tier1, then set tier1 as the default for new signups.
ALTER TABLE users ADD COLUMN plan TEXT NOT NULL DEFAULT 'tier1';
