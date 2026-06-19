# Database

pushpage uses a relational database for persistence. It supports **SQLite** (the default, no extra setup) and **PostgreSQL** (opt-in via `SPRING_PROFILES_ACTIVE=postgres`). Schema migrations are managed by Flyway and the migration scripts are compatible with both engines.

## Schema

### pages

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| `id` | TEXT (PK) | no | Randomly generated 8-char hex identifier |
| `title` | TEXT | no | Human-readable page title |
| `created_at` | TIMESTAMP | no | Timestamp of when the page was published |
| `deleted_at` | TIMESTAMP | yes | Timestamp of soft-deletion; `NULL` for live pages |
| `expires_at` | TIMESTAMP | yes | Scheduled deletion time; `NULL` means the page does not have an explicit expiry |
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
| `active` | INTEGER | no | `1` = active, `0` = deactivated (default: `1`) |
| `admin` | INTEGER | no | `1` = admin, `0` = regular user (default: `0`) |

`active` and `admin` are stored as `INTEGER` (not `BOOLEAN`) for SQLite compatibility. The application reads them with `getInt() != 0`.

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
