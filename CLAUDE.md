# pushpage

An HTML page publishing service for a homelab. AI agents POST HTML content and get a shareable URL back instead of dumping text into chat.

## Stack

- **Spring Boot 3.4** — publisher service (Java 25)
- **PostgreSQL** — database, managed by Flyway
- **Flyway** — database migrations (all schema changes must go through a versioned migration in `src/main/resources/db/migration/`)
- **nginx** — static file serving + reverse proxy
- **FastMCP** (Python) — cloud MCP server, runs by default alongside the main stack
- **Docker Compose** — orchestration

## Project Structure

```
push-page/
├── docker-compose.yml          # prod — pulls images from Docker Hub
├── docker-compose.dev.yml      # dev  — builds image from source
├── .env                        # environment variables (see below)
├── publisher/
│   └── Dockerfile              # multi-stage Maven build → JRE runtime
├── nginx/
│   ├── Dockerfile              # custom nginx image (bakes in config + landing page)
│   ├── default.conf            # nginx routing config
│   └── index.html              # landing page
├── mcp/
│   ├── server.py               # FastMCP server (streamable-http transport)
│   ├── Dockerfile              # Python image for the MCP service
│   └── requirements.txt
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
    │   └── PageRepository.java     # JdbcTemplate
    └── service/
        └── PublishService.java     # business logic
```

## Git Workflow

Before starting any work, pull the latest `main` branch. Create a new feature branch from `main`, make changes, then open a PR. Never commit directly to `main`.

## Documentation Sync

Whenever a change requires documentation (new endpoint, config variable, schema change, behavior change), **both** `CLAUDE.md` and `README.md` must be updated in the same PR. Do not merge changes that leave either file stale.

Technical reference material (database schema, API contracts, architecture notes) lives in [`docs/`](docs/). Keep it up to date alongside code changes.

For every change, evaluate whether **`skill/SKILL.md`** and **`nginx/llms.txt`** need updating. These are agent-facing instruction files — any change to the API surface, auth mechanism, base URL, or agent workflow must be reflected in both. Stale agent instructions cause agents to call wrong endpoints or follow broken flows.

## Database

pushpage uses **PostgreSQL**. `docker-compose.yml` starts a co-located `postgres:17-alpine` container automatically. Schema migrations are managed by Flyway.

## Environment Variables (`.env`)

| Variable | Description | Example |
|----------|-------------|---------|
| `APP_SERVER_URL` | Full public URL of the service | `http://pushpage.homelab.local` |
| `NGINX_PORT` | Host port nginx binds to | `8080` |
| `CLEANUP_RETENTION_DAYS` | Days to retain pages before auto-cleanup | `30` |
| `CLEANUP_SCHEDULE` | Cron expression for the cleanup job | `0 0 * * * *` |
| `MAX_FILE_SIZE` | Max HTML payload the service accepts (app-level check) | `1MB` |
| `MAX_REQUEST_SIZE` | Servlet-level request size ceiling (last-resort fallback, should exceed `MAX_FILE_SIZE`) | `10MB` |
| `DB_HOST` | PostgreSQL hostname | `localhost` |
| `DB_PORT` | PostgreSQL port | `5432` |
| `DB_NAME` | PostgreSQL database name | `pushpage` |
| `DB_USER` | PostgreSQL username | `pushpage` |
| `DB_PASSWORD` | PostgreSQL password | `changeme` |

## Authentication

API key authentication is required for all endpoints except `/api/health`. Pass the key via `X-Api-Key: <key>` header or `Authorization: Bearer <key>`.

On first run, if no users exist the service auto-creates an `admin` user, logs the generated API key prominently, and prompts you to copy it before restarting. After restart the key is no longer logged. API keys are stored as SHA-256 hashes in the database.

## API

All endpoints are under `/api` (Spring Boot context path).

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/pages` | User | Publish HTML, returns `{ url, id }`. `title` is optional — extracted from `<title>` tag when absent, falls back to `"Untitled"` |
| `GET` | `/api/pages` | User | List pages scoped to caller (admin sees all) |
| `DELETE` | `/api/pages/{id}` | User | Delete own page (admin can delete any) |
| `GET` | `/api/me` | User | Returns the authenticated user's profile |
| `GET` | `/api/health` | None | Health check |
| `POST` | `/api/admin/users` | Admin | Create a user, response includes `api_key` |
| `GET` | `/api/admin/users` | Admin | List all users (no API keys) |
| `PATCH` | `/api/admin/users/{id}/deactivate` | Admin | Deactivate a user |

Swagger UI: `{APP_SERVER_URL}/api/swagger-ui/index.html`

## Dashboard

A browser-based dashboard is served by nginx at `/dashboard` (`nginx/dashboard.html`). It authenticates with an API key stored in `sessionStorage` and calls the REST endpoints, including `/api/me` to resolve the logged-in username on load.

**Tabs:**
- **Pages** — lists the caller's pages (admins see all), with delete and pagination
- **Account** — displays username, role, and a reveal/copy widget for the API key
- **Users** (admin only) — lists all users; create new user (shows generated key once); deactivate user

Shared visual styles live in `nginx/static/theme.css`, linked by both `index.html` and `dashboard.html`.

## Running

```bash
# Prod (create data dirs on first run)
mkdir -p data/pages data/pgdata
docker compose up -d

# Dev — build from source (no persistent data)
docker compose -f docker-compose.dev.yml up -d --build
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
podman build --no-cache --platform linux/amd64 -t rgf2004/pushpage-nginx:amd64 -f nginx/Dockerfile .
podman build --no-cache --platform linux/arm64 -t rgf2004/pushpage-nginx:arm64 -f nginx/Dockerfile .
podman manifest create rgf2004/pushpage-nginx:latest rgf2004/pushpage-nginx:amd64 rgf2004/pushpage-nginx:arm64
podman manifest push --all rgf2004/pushpage-nginx:latest docker://docker.io/rgf2004/pushpage-nginx:latest
```
