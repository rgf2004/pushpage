# Database

pushpage uses SQLite for metadata storage, managed by Flyway migrations.

## Location

The database file is stored at `/data/pushpage.db` inside the `data` Docker volume.

## Schema

### pages

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| `id` | TEXT (PK) | no | Randomly generated 8-char hex identifier |
| `title` | TEXT | no | Human-readable page title |
| `created_at` | TEXT | no | ISO-8601 timestamp of when the page was published |
| `deleted_at` | TEXT | yes | ISO-8601 timestamp of soft-deletion; `NULL` for live pages |

**Indexes:**

| Name | Column | Purpose |
|------|--------|---------|
| `idx_pages_created_at` | `created_at` | Speeds up chronological ordering and range queries |

## Migrations

All schema changes must go through a versioned Flyway migration in `src/main/resources/db/migration/`. Never modify the schema directly.

Migration files follow the naming convention: `V{version}__{description}.sql`
