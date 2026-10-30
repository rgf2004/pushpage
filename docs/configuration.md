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
| `TRUSTED_PROXIES` | Comma-separated IPs/CIDRs of reverse proxies, tunnels or load balancers in front of nginx. nginx takes the client IP from `REAL_IP_HEADER` only for requests from these addresses. Empty disables it. See [Running behind a proxy](#running-behind-a-proxy-or-tunnel). | _(empty)_ |
| `REAL_IP_HEADER` | Header that carries the client IP when `TRUSTED_PROXIES` is set. | `X-Forwarded-For` |

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

## Security Hardening (nginx)

These protections live in `nginx/default.conf`; the only related `.env` settings are `TRUSTED_PROXIES` and `REAL_IP_HEADER` (see [Running behind a proxy](#running-behind-a-proxy-or-tunnel)).

| Protection | Behavior |
|------------|----------|
| Page sandbox | `/pages/*.html` is served with `Content-Security-Policy: sandbox allow-scripts allow-popups allow-forms allow-modals` (plus `nosniff` and `Referrer-Policy: no-referrer`). Published pages are untrusted user content on the same origin as the dashboard and API. The sandbox omits `allow-same-origin`, so a page's scripts run in an opaque origin and cannot read the dashboard's `sessionStorage` JWT or call `/api` as the viewer. Side effect: pages cannot use `localStorage`, `sessionStorage` or cookies. Never add `allow-same-origin`. |
| Page bar | nginx injects a slim pushpage bar (with a dismiss button) at the top of every published page as it is served, by appending `nginx/static/pp-bar.html` after the page (`add_after_body`). It is always on. The page remains sandboxed by the same CSP, and its own `<head>` (title, Open Graph tags) and URL are unchanged. A page's own scripts can remove it. |
| Frame blocking | All other responses carry `X-Frame-Options: DENY` and `Content-Security-Policy: frame-ancestors 'none'`, so the dashboard cannot be framed. |
| Auth rate limit | `POST /api/auth/login`, `/signup`, `/forgot-password` and `/reset-password` are limited to 10 requests/minute per client IP (burst 5) via `limit_req`; excess requests get `429`. Login/signup share one bucket and forgot/reset-password another, so failed logins never block someone from resetting their password. If nginx runs behind another proxy, tunnel or load balancer, set `TRUSTED_PROXIES` so the limit keys on the real client IP rather than the proxy's. |
| Hidden files | Any path with a dot-prefixed segment (`/.env`, `/.git/config`, `/.aws/credentials`, ...) returns `404` and is not logged, except `/.well-known`. This is defense in depth against scanners; the files are not in the image anyway. |

Serving pages from a separate domain would give stronger isolation, but the sandbox header achieves the same protection without a second domain.

### Running behind a proxy or tunnel

Behind a reverse proxy, load balancer or tunnel (for example Cloudflare Tunnel), every request reaches nginx from the proxy's address. The per-IP auth rate limit then counts all visitors together, and the publisher's per-IP guest limit sees the proxy's address. Set:

```
TRUSTED_PROXIES=10.0.0.5          # the proxy/tunnel host(s), comma-separated IPs or CIDRs
REAL_IP_HEADER=X-Forwarded-For    # default; use CF-Connecting-IP for Cloudflare
```

| Setup | `TRUSTED_PROXIES` | `REAL_IP_HEADER` |
|-------|-------------------|------------------|
| No proxy (default) | _(empty)_ | _(unused)_ |
| Generic proxy / load balancer | its IP or subnet | `X-Forwarded-For` (or `X-Real-IP`) |
| Cloudflare Tunnel (`cloudflared` on another host) | the `cloudflared` host's IP | `CF-Connecting-IP` |

List **only** proxies you control. The header is trusted when the request comes from one of them; if an address you do not control is listed, clients behind it can spoof their IP and dodge the rate limits. Invalid entries make the nginx container fail at startup rather than being ignored. The settings are applied by `nginx/20-realip.sh` when the container starts, so restart nginx after changing them.

## Flyway Migrations

All migrations live in a single folder, `src/main/resources/db/migration/`, and are
applied in strictly increasing version order — there's a single timeline, so new migrations should
always take the next unused version number and never reuse or fall below one already
released.

## Optional features

Both are off by default and independent of each other.

| Variable | Description | Default |
|----------|-------------|---------|
| `PLANS_ENABLED` | Subscription plans: daily publish quota and plan-based page retention (`tier1`/`tier2`/`tier3`, set by an admin). Admin and guest requests are always exempt from quota, so a self-hosted instance where you're the only (admin) user is unaffected either way. See the "Subscription plans" section of `CLAUDE.md`. | `false` |
| `EMAIL_ENABLED` | Turns on everything that sends email: the SMTP sender, mandatory sign-up email verification (users must confirm their address before they can log in), and self-service password reset by email. Needs working `EMAIL_SMTP_*` settings; see [`docs/email-verification.md`](email-verification.md) and [`docs/password-reset.md`](password-reset.md). | `false` |

Password reset also works without email (admin-issued links, `ADMIN_RECOVERY`); its settings are in [`docs/password-reset.md`](password-reset.md#configuration).

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
