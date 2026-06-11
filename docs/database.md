# Database

pushpage supports two metadata storage backends: **SQLite** (default) and **PostgreSQL**. Both are managed by Flyway migrations with a shared migration path — the schema uses only standard SQL compatible with both engines.

The active backend is selected via the `SPRING_PROFILES_ACTIVE` environment variable. See [`configuration.md`](configuration.md) for details.

## SQLite (default)

The database file is stored at `/data/pages.db` inside the `data` Docker volume.

## PostgreSQL

Activate with `SPRING_PROFILES_ACTIVE=postgres`. Flyway runs the same migration scripts on startup. Use the `docker-compose.postgres.yml` overlay to include a co-located Postgres container, or point `DB_HOST` at an external instance.

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
