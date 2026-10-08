# pushpage

An HTML page publishing service for AI agents. AI agents POST HTML content and get a shareable URL back instead of dumping text into chat. A managed public instance is available at https://pushpage.link; it can also be self-hosted.

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
├── llms.txt                    # agent-facing integration guide (served at /llms.txt)
├── publisher/
│   └── Dockerfile              # multi-stage Maven build → JRE runtime
├── nginx/
│   ├── Dockerfile              # custom nginx image (bakes in config + all static pages)
│   ├── default.conf            # nginx routing config
│   ├── index.html              # landing page (/)
│   ├── dashboard.html          # browser dashboard (/dashboard)
│   ├── docs.html               # documentation page (/docs)
│   ├── terms.html              # Terms of Use page (/terms)
│   ├── 404.html                # custom 404 error page
│   ├── overlay/                # empty; downstream forks drop override files here (see below)
│   └── static/
│       ├── theme.css           # shared visual styles (header, footer, buttons, badges)
│       └── favicon.svg         # "pp" wordmark favicon
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
    ├── service/
    │   └── PublishService.java     # business logic
    ├── plans/                      # PLANS_ENABLED: Plan enum, PlanRepository, QuotaService, RetentionService, PlanAdminController
    └── emailverification/          # EMAIL_VERIFICATION_ENABLED: hooks, service, repository, SMTP email, controllers
```

### Optional features (feature flags)

Two independent features are compiled into every build and switched on with environment
variables (both default `false`). Each flag registers `@Primary` beans (in `plans` / `emailverification`) that override the
no-op defaults (`UserLifecycleHooks`, `QuotaPolicy`, `RetentionPolicy`,
`UserSummaryEnricher`), using the `@ConditionalOnPlansEnabled` / `@ConditionalOnEmailVerificationEnabled` annotations
(one in each feature package).

| Flag | Turns on |
|------|----------|
| `PLANS_ENABLED` | Daily publish quota, plan-based retention, `PATCH /api/admin/users/{id}/plan`. See "Subscription plans" below. |
| `EMAIL_VERIFICATION_ENABLED` | Mandatory sign-up email verification (SMTP), verify/resend endpoints. See [`docs/email-verification.md`](docs/email-verification.md). |

Leaving both unset keeps the default behavior (unlimited, no verification), which is
what most self-hosted deployments want. The managed `pushpage.link` instance sets both
to `true`.

All migrations, including the `V1000+` ones used by these features, live in the single
`db/migration/` folder and always run regardless of the flags (they just add unused
columns when a feature is off). New migrations take the next unused version number;
**never reuse or drop below a version number already released**.

### Adding a new nginx page

Every new static page requires **four** changes:
1. Create `nginx/<name>.html`
2. Add `COPY nginx/<name>.html /usr/share/nginx/html/<name>.html` to `nginx/Dockerfile`
3. Add a `location = /<name>` block to `nginx/default.conf` pointing to the file
4. Add a `<url>` entry to `nginx/sitemap.xml` (only for pages that should be indexed — exclude auth-required or noindex pages like the dashboard)

Shared visual styles (`theme.css`) are already linked from all pages — new pages should link to `/static/theme.css` and follow the same header/footer pattern as `docs.html`.

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
| `APP_SERVER_URL` | Full public URL of the service | `https://pushpage.link` |
| `NGINX_PORT` | Host port nginx binds to | `8080` |
| `CLEANUP_RETENTION_DAYS` | Days to retain pages before auto-cleanup | `30` |
| `CLEANUP_SCHEDULE` | Cron expression for the cleanup job | `0 0 * * * *` |
| `MAX_FILE_SIZE` | Max HTML payload the service accepts (app-level check) | `1MB` |
| `MAX_REQUEST_SIZE` | Servlet-level request size ceiling (last-resort fallback, should exceed `MAX_FILE_SIZE`) | `10MB` |
| `RATE_LIMIT_USER_RPM` | Max publish requests per minute for authenticated users. Admin users are exempt. | `10` |
| `RATE_LIMIT_GUEST_RPM` | Max publish requests per minute per IP for unauthenticated (guest) requests. | `5` |
| `JWT_SECRET` | **Required.** Secret for signing JWTs (HS256, min 32 chars). App fails fast at startup if missing. | — |
| `DB_HOST` | PostgreSQL hostname | `localhost` |
| `DB_PORT` | PostgreSQL port | `5432` |
| `DB_NAME` | PostgreSQL database name | `pushpage` |
| `DB_USER` | PostgreSQL username | `pushpage` |
| `DB_PASSWORD` | PostgreSQL password | `changeme` |

`app.jwt.expiration-hours` (default `24`) is set in `application.properties` and does not need a `.env` entry. See `docs/configuration.md` for details.

| `PLANS_ENABLED` | Enable subscription plans: daily publish quota + plan-based retention. | `false` |
| `EMAIL_VERIFICATION_ENABLED` | Require sign-up email verification (needs the `EMAIL_SMTP_*` settings). | `false` |
| `EMAIL_SMTP_HOST` / `EMAIL_SMTP_PORT` / `EMAIL_SMTP_USERNAME` / `EMAIL_SMTP_PASSWORD` | SMTP credentials for sending verification emails. Only used when `EMAIL_VERIFICATION_ENABLED=true`. | — |
| `EMAIL_FROM` | From address on the confirmation email. | `no-reply@pushpage.link` |
| `EMAIL_VERIFICATION_TOKEN_TTL_HOURS` | How long a verification link stays valid. | `24` |
| `EMAIL_VERIFICATION_RATE_LIMIT_PER_MINUTE` | Max verification emails per account per minute. | `1` |
| `EMAIL_RESEND_IP_RATE_LIMIT_RPM` | Max resend-verification requests per IP per minute. | `5` |

## Authentication

Two credential types are accepted:

| Method | Header |
|--------|--------|
| API key | `X-Api-Key: pp_<key>` |
| JWT Bearer | `Authorization: Bearer <jwt>` |

JWTs are issued by `POST /api/auth/login` and are valid for 24 h. API keys are obtained via `POST /api/me/tokens` and are long-lived.

On first run, if no users exist the service auto-creates an `admin@pushpage.link` account with a random alphanumeric password **and** a random API key, logs both prominently, and never shows them again. API keys are stored as SHA-256 hashes; passwords as BCrypt hashes.

## API

All endpoints are under `/api` (Spring Boot context path).

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/auth/signup` | None | Create account (email + password). Returns `201 No Content`. |
| `POST` | `/api/auth/login` | None | Login (email + password). Returns `{ jwt }`. |
| `POST` | `/api/pages` | Optional | Publish HTML → `{ url, id, expires_at }`. Accepts `application/json` (`{ html, title? }`) or `multipart/form-data` (`file=@report.html`). Guest pages expire in 30 min. Authenticated pages expire per `RetentionPolicy` (default `CLEANUP_RETENTION_DAYS`). |
| `GET` | `/api/pages` | User | List pages scoped to caller (admin sees all). |
| `DELETE` | `/api/pages/{id}` | User | Delete own page (admin can delete any). |
| `POST` | `/api/me/tokens` | User | Generate / rotate API key → `{ api_key }`. Previous key is invalidated. |
| `GET` | `/api/health` | None | Health check. |
| `GET` | `/api/admin/users` | Admin | List all users. |
| `PATCH` | `/api/admin/users/{id}/deactivate` | Admin | Deactivate a user. |
| `PATCH` | `/api/admin/users/{id}/promote` | Admin | Grant admin role. |
| `GET` | `/api/me` | User | Current user's email/role, and (with `PLANS_ENABLED`) `plan`, `daily_limit`, `daily_usage`. |
| `GET` | `/api/auth/verify-email` | None | *(`EMAIL_VERIFICATION_ENABLED`)* Consumes a sign-up verification token, redirects to `/dashboard`. |
| `POST` | `/api/auth/resend-verification` | None | *(`EMAIL_VERIFICATION_ENABLED`)* Resend the verification email. Always `204`; rate-limited per IP. |
| `PATCH` | `/api/admin/users/{id}/verify-email` | Admin | *(`EMAIL_VERIFICATION_ENABLED`)* Manually mark a user's email verified. |
| `PATCH` | `/api/admin/users/{id}/plan` | Admin | *(`PLANS_ENABLED`)* Set a user's plan (`tier1`/`tier2`/`tier3`). |

Swagger UI: `{APP_SERVER_URL}/api/swagger-ui/index.html`

## Dashboard

A browser-based dashboard is served by nginx at `/dashboard` (`nginx/dashboard.html`). It authenticates via email + password → JWT (stored in `sessionStorage`) and calls the REST endpoints.

**Screens:**
- **Login** — email + password → JWT; link to sign-up
- **Sign-up** — email + password + Terms of Use checkbox (required) → account created → redirect to login. The checkbox links to `/terms`.

**Tabs:**
- **Pages** — lists the caller's pages, with delete and pagination
- **Account** — displays email, role; Generate / Rotate API Key button (shows key once); plan badge + quota progress bar (`PLANS_ENABLED` only — hidden otherwise)
- **Users** (admin only) — lists all users; Promote and Deactivate actions; plan column (`PLANS_ENABLED`), verified column and Verify button (`EMAIL_VERIFICATION_ENABLED`)

## Subscription plans (`PLANS_ENABLED`)

When `PLANS_ENABLED=true`, three tiers enforce a **daily publish
quota** (resets at UTC midnight) and a **page retention window**. Admins and guests
are exempt from the quota; guests always get the fixed 30-minute guest expiry
regardless of plan.

| Tier | Daily limit | Retention |
|------|-------------|-----------|
| `tier1` | 30 pages | 7 days |
| `tier2` | 100 pages | 30 days |
| `tier3` | 500 pages | 90 days |

There is no caller-settable "permanent"/never-expire flag — retention is always chosen
by the server from the user's plan. Plans are set manually via
`PATCH /api/admin/users/{id}/plan` — there is no self-serve billing yet.

### Key files

| File | Role |
|------|------|
| `src/main/java/me/projects/pushpage/plans/model/Plan.java` | Enum — tier names and their `dailyLimit` / `retentionDays` values |
| `src/main/java/me/projects/pushpage/plans/repository/PlanRepository.java` | DB access — reads/writes `plan` column on `users` |
| `src/main/java/me/projects/pushpage/plans/service/QuotaService.java` | `@Primary` `QuotaPolicy` — enforces daily limits, surfaces usage/limit/plan via `GET /api/me` |
| `src/main/java/me/projects/pushpage/plans/service/RetentionService.java` | `@Primary` `RetentionPolicy` — resolves the page's `expires_at` from the user's plan |
| `src/main/java/me/projects/pushpage/service/FeatureUserSummaryEnricher.java` | `@Primary` `UserSummaryEnricher` — adds `plan` field to admin user list |
| `src/main/java/me/projects/pushpage/plans/controller/PlanAdminController.java` | `PATCH /api/admin/users/{id}/plan` |
| `src/main/resources/db/migration/V1001__add_plan_to_users.sql` | Adds `plan TEXT NOT NULL DEFAULT 'tier1'` to `users` |
| `nginx/dashboard.html` | Plan badge + quota progress bar in the Account tab; plan column in admin Users tab |

### Changing a tier's quota limit or retention window

**Backend only — the frontend reads limits dynamically from `GET /api/me`, and per-page expiry from `GET /api/pages`.**

1. Edit `src/main/java/me/projects/pushpage/plans/model/Plan.java`:
   ```java
   tier1(50, 14),   // was tier1(30, 7)
   ```
   First argument is `dailyLimit`, second is `retentionDays`.
2. No DB migration needed — both values live in the enum, not the database.
3. No frontend change needed — the dashboard fetches `daily_limit` from the API and reads each page's `expires_at` at runtime.
4. Changing `retentionDays` only affects newly published pages; existing pages keep the `expires_at` computed at publish time.

### Adding a new tier

1. Add the new constant to `Plan.java` (e.g. `tier4(1000, 180)`).
2. Update `PlanAdminController.changePlan()` error message to list the new tier.
3. Add a CSS class for the new badge colour in `nginx/dashboard.html` (search for `.tier3`).
4. No migration needed — new users still default to `tier1`; admins set the plan via `PATCH /api/admin/users/{id}/plan`.

Shared visual styles live in `nginx/static/theme.css`, linked by all nginx-served HTML pages.

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
