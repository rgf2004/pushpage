# pushpage

A self-hosted HTML page publishing service for a homelab. AI agents POST HTML content and get a shareable URL back instead of dumping text into chat.

## Stack

- **Spring Boot 3.4** — publisher service (Java 25)
- **SQLite** (default) or **PostgreSQL** (optional) — metadata store (page id, title, created_at)
- **Flyway** — database migrations (all schema changes must go through a versioned migration in `src/main/resources/db/migration/`)
- **nginx** — static file serving + reverse proxy
- **Docker Compose** — orchestration

## Project Structure

```
push-page/
├── docker-compose.yml          # prod — pulls images from Docker Hub (rgf2004/pushpage-publisher, rgf2004/pushpage-nginx)
├── docker-compose.dev.yml      # dev  — builds image from source
├── docker-compose.postgres.yml # overlay — adds PostgreSQL service (merge with base or dev file)
├── .env                        # environment variables (see below)
├── publisher/
│   └── Dockerfile              # multi-stage Maven build → JRE runtime
├── nginx/
│   ├── Dockerfile              # custom nginx image (bakes in config + landing page)
│   ├── default.conf            # nginx routing config
│   └── index.html              # landing page
└── src/main/java/me/projects/pushpage/
    ├── PushPageApplication.java
    ├── config/
    │   └── OpenApiConfig.java      # Swagger / OpenAPI setup
    ├── controller/
    │   └── PublishController.java  # REST endpoints
    ├── model/
    │   ├── Page.java
    │   ├── PublishRequest.java
    │   └── PublishResponse.java
    ├── repository/
    │   └── PageRepository.java     # SQLite via JdbcTemplate
    └── service/
        └── PublishService.java     # business logic
```

## Git Workflow

Before starting any work, pull the latest `main` branch. Create a new feature branch from `main`, make changes, then open a PR. Never commit directly to `main`.

## Documentation Sync

Whenever a change requires documentation (new endpoint, config variable, schema change, behavior change), **both** `CLAUDE.md` and `README.md` must be updated in the same PR. Do not merge changes that leave either file stale.

Technical reference material (database schema, API contracts, architecture notes) lives in [`docs/`](docs/). Keep it up to date alongside code changes.

## Database Backends

The service supports **SQLite** (default) and **PostgreSQL** via Spring profiles. The active profile is controlled by the `SPRING_PROFILES_ACTIVE` environment variable.

### SQLite (default)

No extra configuration required. The database file is written to `DB_PATH` (default `/data/pages.db`).

### PostgreSQL

Set `SPRING_PROFILES_ACTIVE=postgres` and provide the connection variables below. Use the `docker-compose.postgres.yml` overlay to spin up a co-located Postgres container:

```bash
# Prod with PostgreSQL
docker compose -f docker-compose.yml -f docker-compose.postgres.yml up -d

# Dev with PostgreSQL
docker compose -f docker-compose.dev.yml -f docker-compose.postgres.yml up -d --build
```

## Environment Variables (`.env`)

| Variable | Description | Example |
|----------|-------------|---------|
| `APP_SERVER_URL` | Full public URL of the service | `http://pushpage.homelab.local` |
| `NGINX_PORT` | Host port nginx binds to | `8080` |
| `CLEANUP_RETENTION_DAYS` | Days to retain pages before auto-cleanup | `30` |
| `CLEANUP_SCHEDULE` | Cron expression for the cleanup job | `0 0 * * * *` |
| `MAX_FILE_SIZE` | Max HTML payload the service accepts (app-level check) | `1MB` |
| `MAX_REQUEST_SIZE` | Servlet-level request size ceiling (last-resort fallback, should exceed `MAX_FILE_SIZE`) | `10MB` |
| `DB_HOST` | PostgreSQL host (postgres profile only) | `localhost` |
| `DB_PORT` | PostgreSQL port (postgres profile only) | `5432` |
| `DB_NAME` | PostgreSQL database name (postgres profile only) | `pushpage` |
| `DB_USER` | PostgreSQL username (postgres profile only) | `pushpage` |
| `DB_PASSWORD` | PostgreSQL password (postgres profile only) | `changeme` |
| `DB_POOL_SIZE` | HikariCP max pool size (postgres profile only) | `10` |

## API

All endpoints are under `/api` (Spring Boot context path).

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/publish` | Publish HTML, returns `{ url, id }` |
| `GET` | `/api/pages` | List all published pages |
| `DELETE` | `/api/pages/{id}` | Delete a page |
| `GET` | `/api/health` | Health check |

Swagger UI: `{APP_SERVER_URL}/api/swagger-ui/index.html`

## Running

```bash
# Dev (build from source)
docker compose -f docker-compose.dev.yml up -d --build

# Prod (pull from Docker Hub)
docker compose up -d
```

## Building & Pushing Multi-Arch Images

**Publisher image (`rgf2004/pushpage-publisher`):**
```bash
podman build --no-cache --platform linux/amd64 -t rgf2004/pushpage-publisher:amd64 -f publisher/Dockerfile .
podman build --no-cache --platform linux/arm64 -t rgf2004/pushpage-publisher:arm64 -f publisher/Dockerfile .
podman manifest create rgf2004/pushpage-publisher:latest rgf2004/pushpage-publisher:amd64 rgf2004/pushpage-publisher:arm64
podman manifest push --all rgf2004/pushpage-publisher:latest docker://docker.io/rgf2004/pushpage-publisher:latest
```

**Nginx image (`rgf2004/pushpage-nginx`):**
```bash
podman build --no-cache --platform linux/amd64 -t rgf2004/pushpage-nginx:amd64 -f nginx/Dockerfile nginx/
podman build --no-cache --platform linux/arm64 -t rgf2004/pushpage-nginx:arm64 -f nginx/Dockerfile nginx/
podman manifest create rgf2004/pushpage-nginx:latest rgf2004/pushpage-nginx:amd64 rgf2004/pushpage-nginx:arm64
podman manifest push --all rgf2004/pushpage-nginx:latest docker://docker.io/rgf2004/pushpage-nginx:latest
```
