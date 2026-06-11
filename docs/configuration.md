# Configuration

All variables are set in a `.env` file in the project root and passed to the containers via `docker-compose.yml`.

## Core Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `APP_SERVER_URL` | Full public URL of the service. Used to build the `url` field in publish responses. | `http://localhost:8080` |
| `NGINX_PORT` | Host port nginx binds to. | `8080` |
| `CLEANUP_RETENTION_DAYS` | Days to retain pages before the cleanup job removes them. | `30` |
| `CLEANUP_SCHEDULE` | Cron expression controlling how often the cleanup job runs. | `0 0 * * * *` (hourly) |
| `MAX_FILE_SIZE` | Max HTML payload the publisher accepts (app-level check). | `1MB` |
| `MAX_REQUEST_SIZE` | Servlet-level request size ceiling. Should exceed `MAX_FILE_SIZE`. | `10MB` |

## Database Backend

The active database backend is selected via `SPRING_PROFILES_ACTIVE`.

| Value | Backend |
|-------|---------|
| _(unset)_ | SQLite (default) |
| `postgres` | PostgreSQL |

### PostgreSQL Variables (`SPRING_PROFILES_ACTIVE=postgres`)

| Variable | Description | Default |
|----------|-------------|---------|
| `DB_HOST` | PostgreSQL hostname | `localhost` |
| `DB_PORT` | PostgreSQL port | `5432` |
| `DB_NAME` | Database name | `pushpage` |
| `DB_USER` | Database username | `pushpage` |
| `DB_PASSWORD` | Database password | `pushpage` |
| `DB_POOL_SIZE` | HikariCP max connection pool size | `10` |
