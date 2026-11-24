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
│   ├── guides/                 # how-to / explainer pages (/guides/<slug>)
│   ├── 404.html                # custom 404 error page
│   ├── overlay/                # empty; downstream forks drop override files here (see below)
│   └── static/
│       ├── theme.css           # shared visual styles (header, footer, buttons, badges)
│       ├── favicon.svg         # "pp" wordmark favicon
│       ├── favicon.ico         # same icon (16/32/48 px), served at /favicon.ico for pages that declare none
│       └── pp-bar.html         # bar appended to every published page (add_after_body)
├── mcp/
│   ├── pushpage_mcp/server.py  # FastMCP server (tools, guest mode, stdio/HTTP entry point)
│   ├── server.py               # shim: `python server.py` serves streamable-http (Docker); `pushpage-mcp` (PyPI) defaults to stdio
│   ├── pyproject.toml          # PyPI package `pushpage-mcp` (published on v* tags by .github/workflows/pypi.yml)
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
    └── email/                      # EMAIL_ENABLED: sign-up verification hooks, forgot-password, SMTP sender (email/sender), controllers
```

### Optional features (feature flags)

Two independent features are compiled into every build and switched on with environment
variables (both default `false`). Each flag registers `@Primary` beans (in `plans` / `email`) that override the
no-op defaults (`UserLifecycleHooks`, `QuotaPolicy`, `RetentionPolicy`,
`UserSummaryEnricher`), using the `@ConditionalOnPlansEnabled` / `@ConditionalOnEmailEnabled` annotations
(one in each feature package).

| Flag | Turns on |
|------|----------|
| `PLANS_ENABLED` | Daily publish quota, plan-based retention, `PATCH /api/admin/users/{id}/plan`. See "Subscription plans" below. |
| `EMAIL_ENABLED` | Everything that sends email: SMTP sender, mandatory sign-up email verification, verify/resend endpoints, `forgot-password`. See [`docs/email-verification.md`](docs/email-verification.md) and [`docs/password-reset.md`](docs/password-reset.md). |

Leaving both unset keeps the default behavior (unlimited, no verification), which is
what most self-hosted deployments want. The managed `pushpage.link` instance sets both
to `true`.

All migrations, including the `V1000+` ones used by these features, live in the single
`db/migration/` folder and always run regardless of the flags (they just add unused
columns when a feature is off). New migrations take the next unused version number;
**never reuse or drop below a version number already released**.

### Pages: one topic, one page

**Rule:** every topic that should be findable on its own gets its own static HTML page with its own URL. Do not fold it into `docs.html` as a section, and do not render it with JavaScript (tabs, accordions). A search engine indexes and ranks URLs, so a topic that lives inside another page cannot rank for itself or be linked to precisely.

Every such page needs:
- a unique `<title>` and meta description, a canonical URL, Open Graph tags (and Article JSON-LD for guides);
- an entry in `nginx/sitemap.xml` with the real last-change date as `lastmod` (update it when the page changes);
- internal links to it: the footers of `index.html`, `docs.html`, `terms.html` and `dashboard.html`, and related pages;
- facts consistent with `docs/mcp.md` and the docs page (commands, guest vs key behavior, limits), and no claims about other products that we have not verified.

Footers must not link to raw API endpoints such as `/mcp`: they answer crawlers with an error.

**Where the files go**
- **Site pages** (landing, docs, terms): `nginx/<name>.html`, which needs **four** changes:
  1. Create `nginx/<name>.html`
  2. Add `COPY nginx/<name>.html /usr/share/nginx/html/<name>.html` to `nginx/Dockerfile`
  3. Add a `location = /<name>` block to `nginx/default.conf` pointing to the file
  4. Add a `<url>` entry to `nginx/sitemap.xml` (only for pages that should be indexed — exclude auth-required or noindex pages like the dashboard)
- **Topic pages, grouped by kind** in a folder under `nginx/`, served under the same URL prefix. Today there is `nginx/guides/` (`/guides/share-html-from-claude-code`, `/guides/mcp-server-publish-html`, `/guides/html-hosting-for-ai-agents`) for how-to and explainer content. A new kind of content (comparisons, use cases, ...) gets its own folder and prefix by the same rule. The Dockerfile copies each folder as a whole directory; each page still needs its own exact `location = /<folder>/<slug>` block in `default.conf` and a `sitemap.xml` entry.

Shared visual styles (`theme.css`) are already linked from all pages; new pages link to `/static/theme.css` and follow the same header/footer pattern as `docs.html`.

### Security headers and rate limiting (nginx)

- **User pages are sandboxed.** `/pages/*.html` is untrusted user HTML on the same origin as the dashboard (which keeps its JWT in `sessionStorage`) and the API. The `location` serving it sets `Content-Security-Policy: sandbox allow-scripts allow-popups allow-forms allow-modals`. **Never add `allow-same-origin`**, and remember nginx `add_header` is not inherited into a location that defines its own `add_header`.
- **Page bar.** nginx appends a small fixed top bar (pushpage name, "Publish yours" link, dismiss button) to every published page as it is served, with `add_after_body /static/pp-bar.html` in the pages location. The bar is a plain HTML file (`nginx/static/pp-bar.html`, `<style>` + `#pp-bar`), so edit it as HTML; no image or config change is needed beyond rebuilding. It is always on, with no flag. Browsers move content after `</html>` into the body. The page stays sandboxed by the same CSP header, so the bar is just markup inside the untrusted document, and the page's own `<head>` (title, Open Graph tags) and URL are untouched. A page can hide the bar, and a page with its own fixed top header sits under it. Never use SSI (`ssi on`) in that location: it would execute directives found in user HTML.
- **Real client IP behind a proxy/tunnel.** `nginx/20-realip.sh` (run by the nginx image entrypoint) renders `/etc/nginx/realip.conf` from `TRUSTED_PROXIES` / `REAL_IP_HEADER`; `default.conf` includes it in the server block. Empty `TRUSTED_PROXIES` (default) writes an empty file. This makes the `auth` rate limit and the `X-Real-IP` / `X-Forwarded-For` passed to the publisher use the real client. nginx **overwrites** `X-Forwarded-For` with `$remote_addr` (it never appends the client's own header), because the publisher's rate limiters (`RateLimitFilter`, resend-verification, forgot-password) read its first value; appending would let a client choose its own rate-limit bucket (#184). Behind Cloudflare or another proxy you must set `TRUSTED_PROXIES` (and `REAL_IP_HEADER`), otherwise every client shares the proxy's address. Only list proxies you control, or clients can spoof their IP.
- **Dotfiles are never served.** A `location ~ /\.(?!well-known)` returns `404` with logging off (declared before the other regex locations). It returns a body on purpose, which skips the global `404` `error_page` whose internal redirect would log the request anyway.
- **Framing is denied** server-wide (`X-Frame-Options: DENY`, CSP `frame-ancestors 'none'`); the pages location overrides this so pages stay embeddable.
- **`/dashboard` is served with `Cache-Control: no-cache`** (via `expires -1`, deliberately not `add_header`, which would drop the server-level frame-blocking headers in that location) so browsers pick up a redeployed dashboard instead of reusing a stale copy.
- **`/api/auth/login`, `/signup`, `/forgot-password` and `/reset-password`** have a `limit_req` zone (`auth`, 10 r/m per IP, burst 5, `429`), keyed per IP *and* bucket (`$auth_bucket`: login/signup vs forgot/reset-password) so failed logins can't block the reset flow. These locations repeat the `proxy_pass` block from `/api/`; keep them in sync.

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
| `API_KEYS_MAX_PER_USER` | Max named API keys an account can hold. | `10` |
| `TRUSTED_PROXIES` | Comma-separated IPs/CIDRs of reverse proxies or tunnels in front of nginx whose forwarded client IP nginx should trust. Empty = off. | — |
| `REAL_IP_HEADER` | Header carrying the client IP when `TRUSTED_PROXIES` is set (`CF-Connecting-IP` for Cloudflare). | `X-Forwarded-For` |
| `JWT_SECRET` | **Required.** Secret for signing JWTs (HS256, min 32 chars). App fails fast at startup if missing. | — |
| `DB_HOST` | PostgreSQL hostname | `localhost` |
| `DB_PORT` | PostgreSQL port | `5432` |
| `DB_NAME` | PostgreSQL database name | `pushpage` |
| `DB_USER` | PostgreSQL username | `pushpage` |
| `DB_PASSWORD` | PostgreSQL password | `changeme` |

`app.jwt.expiration-hours` (default `24`) is set in `application.properties` and does not need a `.env` entry. See `docs/configuration.md` for details.

| `PLANS_ENABLED` | Enable subscription plans: daily publish quota + plan-based retention. | `false` |
| `EMAIL_ENABLED` | Enable email: sign-up verification and password-reset emails (needs the `EMAIL_SMTP_*` settings). | `false` |
| `EMAIL_SMTP_HOST` / `EMAIL_SMTP_PORT` / `EMAIL_SMTP_USERNAME` / `EMAIL_SMTP_PASSWORD` | SMTP credentials for sending verification and password-reset emails. Only used when `EMAIL_ENABLED=true`. | — |
| `EMAIL_FROM` | From address on the confirmation email. | `no-reply@pushpage.link` |
| `EMAIL_VERIFICATION_TOKEN_TTL_HOURS` | How long a verification link stays valid. | `24` |
| `EMAIL_VERIFICATION_RATE_LIMIT_PER_MINUTE` | Max verification emails per account per minute. | `1` |
| `EMAIL_RESEND_IP_RATE_LIMIT_RPM` | Max resend-verification requests per IP per minute. | `5` |
| `PASSWORD_RESET_TOKEN_TTL_MINUTES` | How long a password-reset link stays valid (emailed and admin-issued). | `60` |
| `EMAIL_PASSWORD_RESET_RATE_LIMIT_PER_MINUTE` | Max password-reset emails per account per minute. | `1` |
| `EMAIL_PASSWORD_RESET_IP_RATE_LIMIT_RPM` | Max forgot-password requests per IP per minute. | `5` |
| `ADMIN_RECOVERY` | Log a one-time password-reset link for every active admin at startup (locked-out admin, no email). Temporary: unset after use. | `false` |

## Authentication

Two credential types are accepted:

| Method | Header |
|--------|--------|
| API key | `X-Api-Key: pp_<key>` |
| JWT Bearer | `Authorization: Bearer <jwt>` |

JWTs are issued by `POST /api/auth/login` and are valid for 24 h. They carry a `pwd_v` claim (the user's `password_changed_at`), so a password reset invalidates every earlier JWT; API keys are unaffected. An account has any number of named API keys (up to `API_KEYS_MAX_PER_USER`), created with `POST /api/me/keys` and revoked individually; they are long-lived. Key management (`/api/me/keys`) requires a JWT session: `ApiKeyAuthFilter` answers `403` when it is attempted with an API key, so a leaked key cannot mint replacements. Keys live in the `api_keys` table (SHA-256 hash, `key_prefix`, `last_used_at` written at most every 5 minutes per key); sign-up no longer creates a key.

On first run, if no users exist the service auto-creates an `admin@pushpage.link` account with a random alphanumeric password **and** a random API key, logs both prominently (the key is named "Bootstrap key"), and never shows them again. API keys are stored as SHA-256 hashes; passwords as BCrypt hashes.

**MCP guest mode.** The MCP server (`mcp/pushpage_mcp/server.py`) does not require a key. Without one it calls `POST /api/pages` as a guest (the wrapper omits `X-Api-Key` entirely, because the API rejects an empty value, and forwards the real client IP from nginx's `X-Real-IP` so the per-IP guest limit is per client, not per MCP container). Guest `publish_page` results carry `guest_mode`, `message` and `signup_url` (built from `PUSHPAGE_PUBLIC_URL`, set from `APP_SERVER_URL` in the compose files); guest `list_pages` / `delete_page` return a "Sign in required" result without calling the API. A key that is present but invalid still fails with `401`, never downgraded to guest.

**MCP Registry.** `server.json` (repo root) is the registry entry: hosted remote, PyPI package, and `icons` (`nginx/static/icon-512.png` and `favicon.svg`, served from `https://pushpage.link/static/`). The checked-in version is a placeholder; after each release is deployed run `scripts/publish-registry.sh <version>` (checks PyPI and the icon URLs, stamps the version, validates, asks before publishing). Registry versions cannot be republished, so a changed `server.json` only reaches the registry with the next version.

## API

All endpoints are under `/api` (Spring Boot context path).

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/auth/signup` | None | Create account (email + password). Returns `201 No Content`. |
| `POST` | `/api/auth/login` | None | Login (email + password). Returns `{ jwt }`. |
| `POST` | `/api/pages` | Optional | Publish HTML → `{ url, id, expires_at }`. Accepts `application/json` (`{ html, title? }`) or `multipart/form-data` (`file=@report.html`). Guest pages expire in 30 min. Authenticated pages expire per `RetentionPolicy` (default `CLEANUP_RETENTION_DAYS`). |
| `GET` | `/api/pages` | User | List pages scoped to caller (admin sees all). |
| `DELETE` | `/api/pages/{id}` | User | Delete own page (admin can delete any). |
| `GET` | `/api/me/keys` | User (JWT) | List your API keys: `id`, `name`, `key_prefix`, `created_at`, `last_used_at` (never the raw key). |
| `POST` | `/api/me/keys` | User (JWT) | Create a named API key (`{ name }`, ≤60 chars) → `201 { id, name, api_key, key_prefix, created_at }`; raw key returned once. Other keys are unaffected. Max `API_KEYS_MAX_PER_USER` (409 beyond). |
| `DELETE` | `/api/me/keys/{id}` | User (JWT) | Revoke one key (404 if not yours). |
| `GET` | `/api/health` | None | Health check: `{ "status": "UP" }` (200) or `{ "status": "DOWN" }` (503). |
| `GET` | `/api/admin/health` | Admin | Full runtime details: version, uptime, live/deleted page counts, oldest/newest page, storage. Shown on the dashboard's Users tab. |
| `GET` | `/api/config` | None | Public frontend config: `{ features: { plans, email } }`. Add new frontend-visible settings in `ClientConfigService`. |
| `GET` | `/api/admin/users` | Admin | List all users. |
| `PATCH` | `/api/admin/users/{id}/deactivate` | Admin | Deactivate a user. |
| `PATCH` | `/api/admin/users/{id}/promote` | Admin | Grant admin role. |
| `GET` | `/api/me` | User | Current user's email/role, and (with `PLANS_ENABLED`) `plan`, `daily_limit`, `daily_usage`. |
| `GET` | `/api/auth/verify-email` | None | *(`EMAIL_ENABLED`)* Consumes a sign-up verification token, redirects to `/dashboard`. |
| `POST` | `/api/auth/resend-verification` | None | *(`EMAIL_ENABLED`)* Resend the verification email. Always `204`; rate-limited per IP. |
| `PATCH` | `/api/admin/users/{id}/verify-email` | Admin | *(`EMAIL_ENABLED`)* Manually mark a user's email verified. |
| `POST` | `/api/auth/forgot-password` | None | *(`EMAIL_ENABLED`)* Email a password-reset link. Always `202` (no account enumeration); rate-limited per IP and account. |
| `POST` | `/api/auth/reset-password` | None | Set a new password with a reset token (`{ token, password }`) → `204`. Single-use; invalidates the user's existing JWTs. Works with or without email. |
| `POST` | `/api/admin/users/{id}/reset-password` | Admin | Create a one-time reset link → `{ reset_link, expires_at }`. Works with or without email. |
| `PATCH` | `/api/admin/users/{id}/plan` | Admin | *(`PLANS_ENABLED`)* Set a user's plan (`tier1`/`tier2`/`tier3`). |

Swagger UI: `{APP_SERVER_URL}/api/swagger-ui/index.html`

## Dashboard

A browser-based dashboard is served by nginx at `/dashboard` (`nginx/dashboard.html`). It authenticates via email + password → JWT (stored in `sessionStorage`) and calls the REST endpoints.

**Screens:**
- **Login** — email + password → JWT; link to sign-up
- **Sign-up** — email + password + Terms of Use checkbox (required) → account created → redirect to login. The checkbox links to `/terms`.
- **Forgot password** (`EMAIL_ENABLED`) — email → "if an account exists…" message. Without email the login page says to ask an administrator.
- **Reset password** — reached via `/dashboard?reset_token=…` (from an email or an admin); new password + confirmation. The token is removed from the URL immediately.

**Tabs:**
- **Pages** — lists the caller's pages, with delete and pagination
- **Account** — displays email, role; **API keys** list (name, key prefix, created, last used, Revoke with confirmation) and a create form (a required name; the raw key is shown once); plan badge + quota progress bar (`PLANS_ENABLED` only — hidden otherwise); **Connect your AI agent (MCP)** section with copyable setup commands for Claude Code, Cursor, Claude Desktop (`mcp-remote`) and the local `uvx pushpage-mcp` server. They are built in the browser (`mcpSnippet()` in `dashboard.html`) with the key created in this session filled in (kept only in memory, cleared on sign-out/reload), show a `pp_your_key_here` placeholder before that, use `location.origin` for the endpoint (self-hosted instances also get `PUSHPAGE_URL` / `PUSHPAGE_PUBLIC_URL` in the local command), and include a no-key guest-mode command
- **Users** (admin only) — a **Service health** card (version, uptime, page counts, storage, from `GET /api/admin/health`), then lists all users; Promote and Deactivate actions; plan column (`PLANS_ENABLED`), verified column and Verify button (`EMAIL_ENABLED`), and a Reset password button that shows a one-time link to hand to the user

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

The dashboard decides which optional UI to show (plan badge, quota bar, Plan/Verified columns, verification messaging) **only** from `GET /api/config` (`features.*`), never by inferring from the presence of fields in other responses. Code that renders feature-dependent UI must `await configReady` first.

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
