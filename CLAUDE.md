# pushpage

A self-hosted HTML page publishing service for a homelab. AI agents POST HTML content and get a shareable URL back instead of dumping text into chat.

## Stack

- **Spring Boot 3.4** — publisher service (Java 25)
- **SQLite** — metadata store (page id, title, created_at)
- **Flyway** — database migrations (all schema changes must go through a versioned migration in `src/main/resources/db/migration/`)
- **nginx** — static file serving + reverse proxy
- **Docker Compose** — orchestration

## Project Structure

```
push-page/
├── docker-compose.yml          # prod — pulls image from Docker Hub (rgf2004/pushpage)
├── docker-compose.dev.yml      # dev  — builds image from source
├── .env                        # environment variables (see below)
├── publisher/
│   └── Dockerfile              # multi-stage Maven build → JRE runtime
├── nginx/
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

## Environment Variables (`.env`)

| Variable | Description | Example |
|----------|-------------|---------|
| `APP_SERVER_URL` | Full public URL of the service | `http://pushpage.homelab.local` |
| `NGINX_PORT` | Host port nginx binds to | `8080` |
| `CLEANUP_RETENTION_DAYS` | Days to retain pages before auto-cleanup | `30` |
| `CLEANUP_SCHEDULE` | Cron expression for the cleanup job | `0 0 * * * *` |
| `MAX_FILE_SIZE` | Max HTML payload the service accepts (app-level check) | `1MB` |
| `MAX_REQUEST_SIZE` | Servlet-level request size ceiling (last-resort fallback, should exceed `MAX_FILE_SIZE`) | `10MB` |

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

## Building & Pushing Multi-Arch Image

```bash
podman build --no-cache --platform linux/amd64 -t rgf2004/pushpage:amd64 -f publisher/Dockerfile .
podman build --no-cache --platform linux/arm64 -t rgf2004/pushpage:arm64 -f publisher/Dockerfile .
podman manifest create rgf2004/pushpage:latest rgf2004/pushpage:amd64 rgf2004/pushpage:arm64
podman manifest push --all rgf2004/pushpage:latest docker://docker.io/rgf2004/pushpage:latest
```
