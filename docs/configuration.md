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
| `RATE_LIMIT_USER_RPM` | Max publish requests per minute for authenticated users. Admin users are exempt. `429 Too Many Requests` is returned when exceeded. | `10` |
| `RATE_LIMIT_GUEST_RPM` | Max publish requests per minute per IP for unauthenticated (guest) requests. | `5` |

## Guest Publishing

`POST /api/pages` accepts unauthenticated requests. Guest pages are auto-expired after a short window and are never shown in any user's page listing (admins see them highlighted in the dashboard).

The expiry duration is controlled by an application property in `application.properties`:

```properties
app.guest.expiration-minutes=30
```

Change this value and rebuild to adjust how long guest pages live.

## JWT Authentication

| Variable / Property | Where | Description | Default |
|---------------------|-------|-------------|---------|
| `JWT_SECRET` | `.env` | **Required.** Secret used to sign JWTs (HS256). Must be at least 32 characters. The app fails fast at startup if this is missing or blank. See [Generating a strong secret](#generating-a-strong-secret) below. | — |
| `app.jwt.expiration-hours` | `application.properties` | How long issued JWTs remain valid. Override in `application.properties` if needed. | `24` |

### Generating a strong secret

Generate a cryptographically random 256-bit (32-byte) secret and base64-encode it:

```bash
# OpenSSL (recommended)
openssl rand -base64 32

# Python
python3 -c "import secrets, base64; print(base64.b64encode(secrets.token_bytes(32)).decode())"
```

Copy the output into your `.env`:

```
JWT_SECRET=<output from above>
```

Never use a short, predictable, or human-typed string — the secret protects all authenticated sessions.

## Database Variables

pushpage uses PostgreSQL. The following variables configure the connection:

| Variable | Description | Default |
|----------|-------------|---------|
| `DB_HOST` | PostgreSQL hostname | `localhost` |
| `DB_PORT` | PostgreSQL port | `5432` |
| `DB_NAME` | Database name | `pushpage` |
| `DB_USER` | Database username | `pushpage` |
| `DB_PASSWORD` | Database password | `pushpage` |

`docker-compose.yml` starts a co-located `postgres:17-alpine` container automatically. To point at an external PostgreSQL instance instead, override `DB_HOST` (and optionally `DB_PORT`) in your `.env`.
