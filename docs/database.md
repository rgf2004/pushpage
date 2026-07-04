# Database

pushpage uses **PostgreSQL** for persistence. Schema migrations are managed by Flyway.

## Schema

### pages

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| `id` | TEXT (PK) | no | Randomly generated 8-char hex identifier |
| `title` | TEXT | no | Human-readable page title |
| `created_at` | TIMESTAMP | no | Timestamp of when the page was published |
| `deleted_at` | TIMESTAMP | yes | Timestamp of soft-deletion; `NULL` for live pages |
| `expires_at` | TIMESTAMP | yes | Scheduled deletion time. `NULL` only occurs on rows written before this column existed (pre-`V4`) and is swept up by the cleanup job's created-at fallback based on `created_at` — every row written by the app always has a concrete `expires_at`. |
| `user_id` | TEXT (FK → users.id) | yes | Owner of the page |

**Indexes:**

| Name | Column | Purpose |
|------|--------|---------|
| `idx_pages_created_at` | `created_at` | Speeds up chronological ordering and range queries |
| `idx_pages_expires_at` | `expires_at` | Speeds up the cleanup predicate scan |

---

### users

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| `id` | TEXT (PK) | no | Randomly generated 8-char hex identifier |
| `username` | TEXT | no | Unique login name |
| `email` | TEXT | yes | Optional contact email |
| `api_key_hash` | TEXT | no | SHA-256 hex digest of the raw API key |
| `created_at` | TIMESTAMP | no | Timestamp of account creation |
| `active` | BOOLEAN | no | `true` = active, `false` = deactivated (default: `true`) |
| `admin` | BOOLEAN | no | `true` = admin, `false` = regular user (default: `false`) |

**Indexes:**

| Name | Column | Purpose |
|------|--------|---------|
| `idx_users_api_key_hash` | `api_key_hash` | Fast lookup on every authenticated request |
| `idx_users_username` | `username` | Uniqueness check on user creation |
| `idx_users_email` | `email` | Optional lookup by email |

## Migrations

All schema changes must go through a versioned Flyway migration in `src/main/resources/db/migration/`. Never modify the schema directly.

Migration files follow the naming convention: `V{version}__{description}.sql`

| File | Description |
|------|-------------|
| `V1__create_pages_table.sql` | Initial `pages` table (`id`, `title`, `created_at`) |
| `V2__add_deleted_at_and_index.sql` | Adds `deleted_at` to `pages`; adds `idx_pages_created_at` |
| `V3__add_users_table.sql` | Creates `users` table; adds `user_id` FK column to `pages` |
| `V4__add_expires_at_to_pages.sql` | Adds `expires_at` to `pages`; adds `idx_pages_expires_at` |
| `V5__convert_active_admin_to_boolean.sql` | Converts `active` and `admin` columns in `users` from `INTEGER` to `BOOLEAN` |
