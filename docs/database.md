# Database

pushpage requires a database to store page records. It supports **SQLite** (the default, no extra setup) and **PostgreSQL** (opt-in via `SPRING_PROFILES_ACTIVE=postgres`). Schema migrations are managed by Flyway and the migration scripts are compatible with both engines.

## Schema

### pages

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| `id` | TEXT (PK) | no | Randomly generated 8-char hex identifier |
| `title` | TEXT | no | Human-readable page title |
| `created_at` | TIMESTAMP | no | Timestamp of when the page was published |
| `deleted_at` | TIMESTAMP | yes | Timestamp of soft-deletion; `NULL` for live pages |

**Indexes:**

| Name | Column | Purpose |
|------|--------|---------|
| `idx_pages_created_at` | `created_at` | Speeds up chronological ordering and range queries |

## Migrations

All schema changes must go through a versioned Flyway migration in `src/main/resources/db/migration/`. Never modify the schema directly.

Migration files follow the naming convention: `V{version}__{description}.sql`
