# Database

pushpage uses SQLite for metadata storage, managed by Flyway migrations.

## Location

The database file is stored at `/data/pushpage.db` inside the `data` Docker volume.

## Schema

### pages

| Column | Type | Description |
|--------|------|-------------|
| `id` | TEXT (PK) | Randomly generated 8-char hex identifier |
| `title` | TEXT | Human-readable page title |
| `created_at` | TEXT | ISO-8601 timestamp of when the page was published |

## Migrations

All schema changes must go through a versioned Flyway migration in `src/main/resources/db/migration/`. Never modify the schema directly.

Migration files follow the naming convention: `V{version}__{description}.sql`
