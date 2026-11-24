# API Reference

All endpoints are served under the `/api` Spring Boot context path.

## Authentication

Most endpoints require authentication. Two credential types are accepted:

| Method | Header | Notes |
|--------|--------|-------|
| API key | `X-Api-Key: pp_<key>` | Long-lived. Create named keys via `POST /me/keys` (needs a signed-in session). |
| JWT Bearer | `Authorization: Bearer <jwt>` | Short-lived (24 h). Obtain via `POST /auth/login`. |

Missing or invalid credentials return `401 Unauthorized`. Calling an admin endpoint without admin privileges returns `403 Forbidden`.

### Bootstrap

On first run (no users in the database), the service generates an admin account and logs both credentials once:

```
==============================================================
No users found — bootstrap admin created.
Email    : admin@pushpage.link
Password : <random alphanumeric>
API Key  : pp_abc123...
Copy these credentials now. They will NOT appear again.
==============================================================
```

The admin can log in via `POST /auth/login` or authenticate directly with the API key.

---

## Auth (public)

### POST /api/auth/signup

Create a new account. No authentication required.

**Request body:**

```json
{ "email": "alice@example.com", "password": "s3cur3pass" }
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `email` | string | yes | Unique email address. |
| `password` | string | yes | Minimum 8 characters. Stored as BCrypt hash. |

**Response:** `201 No Content`

**Error responses:**

| Status | Reason |
|--------|--------|
| `400` | Invalid email format or password < 8 characters |
| `409` | Email already registered |

---

### POST /api/auth/login

> Rate-limited by nginx (`/api/auth/login`, `/signup`, `/forgot-password` and `/reset-password`): 10 requests/minute per IP, burst 5, then `429`. See [Security Hardening](configuration.md#security-hardening-nginx).

Authenticate with email and password. Returns a short-lived JWT.

**Request body:**

```json
{ "email": "alice@example.com", "password": "s3cur3pass" }
```

**Response `200`:**

```json
{ "jwt": "eyJ..." }
```

The JWT is valid for 24 hours (configurable via `app.jwt.expiration-hours`).

**Error responses:**

| Status | Reason |
|--------|--------|
| `401` | Invalid credentials or user has no password (pre-migration API-key-only accounts) |
| `401` | Account is deactivated |

---

### POST /api/auth/forgot-password

*(Only exists when `EMAIL_ENABLED=true`.)* Emails a password reset link if the address belongs to an active account. Always returns `202`, whether or not the account exists, so registered addresses can't be discovered. Rate-limited per IP (`429` with `Retry-After`) and per account. See [`password-reset.md`](password-reset.md).

**Request body:** `{ "email": "alice@example.com" }`

**Response:** `202 Accepted`

---

### POST /api/auth/reset-password

Sets a new password using a single-use token from a reset link (`/dashboard?reset_token=<token>`). Available in every mode. Invalidates all existing JWTs for the user; API keys keep working.

**Request body:**

```json
{ "token": "<token from the link>", "password": "n3w-s3cur3pass" }
```

**Response:** `204 No Content`

**Error responses:**

| Status | Reason |
|--------|--------|
| `400` | Token unknown, expired, already used, or the user is deactivated; or password < 8 characters |

---

## Pages

### POST /api/pages

Publish an HTML page and receive a shareable URL. Two content types are accepted — use whichever fits your workflow.

**Auth:** optional (guest or authenticated)

Guest pages (no auth) expire after 30 minutes. Authenticated pages expire after `CLEANUP_RETENTION_DAYS` (default 30 days). Retention is computed by the `RetentionPolicy` extension point (`me.projects.pushpage.service.RetentionPolicy`); self-hosted deployments use the single global default for every user, cloud deployments may vary it by plan.

**Response headers (both variants):**

| Header | Description |
|--------|-------------|
| `X-Max-File-Size` | The configured max file size limit in bytes |

**Response body (both variants):**

```json
{
  "url": "https://pushpage.link/pages/abc123.html",
  "id": "abc123",
  "expires_at": "2026-07-11T10:00:00Z"
}
```

#### Variant A — JSON (`Content-Type: application/json`)

```json
{
  "html": "<html>...</html>",
  "title": "My Report"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `html` | string | yes | Full HTML content. Max size controlled by `MAX_FILE_SIZE`. |
| `title` | string | no | Human-readable title. Extracted from the HTML `<title>` tag if omitted; falls back to `"Untitled"`. |

```bash
curl -s -X POST https://pushpage.link/api/pages \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: $PUSHPAGE_API_KEY" \
  -d '{"html": "<html>...</html>"}'
```

#### Variant B — File upload (`Content-Type: multipart/form-data`)

Upload an `.html` file directly — no JSON wrapping required.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `file` | file part | yes | HTML file to publish. Max size controlled by `MAX_FILE_SIZE`. |

Title resolution order:
1. `<title>` tag in the HTML content
2. Original filename without extension (`report.html` → `"report"`)
3. `"Untitled"`

```bash
curl -s -X POST https://pushpage.link/api/pages \
  -H "X-Api-Key: $PUSHPAGE_API_KEY" \
  -F "file=@report.html"
```

**Error responses:**

| Status | Reason |
|--------|--------|
| `400` | Missing or empty `file` part (multipart) / blank `html` field (JSON) |
| `413` | File exceeds the configured size limit |

---

### GET /api/pages

List published pages ordered by publish date descending. Regular users see only their own pages; admins see all.

**Auth:** any user

**Response:** array of page objects.

```json
[
  {
    "id": "abc123",
    "title": "My Report",
    "created_at": "2026-06-11T10:00:00Z",
    "deleted_at": null,
    "expires_at": "2026-07-11T10:00:00Z",
    "url": "https://pushpage.link/pages/abc123.html",
    "user_id": "a1b2c3d4"
  }
]
```

---

### DELETE /api/pages/{id}

Delete a published page. Users may only delete their own pages; admins can delete any page.

**Auth:** any user (owner or admin)

**Response:** `204 No Content`

**Error responses:**

| Status | Reason |
|--------|--------|
| `403` | Page belongs to a different user |
| `404` | Page not found |

---

## User

### GET /api/me/keys

List the caller's API keys: name, key prefix, creation time and last-used time. Raw keys are never returned.

**Auth:** JWT only. Requests authenticated with an API key get `403`, so a leaked key cannot manage keys.

**Response `200`:**

```json
[
  { "id": "a1b2c3d4", "name": "Claude Code on laptop", "key_prefix": "pp_ab12cd",
    "created_at": "2026-11-20T10:00:00Z", "last_used_at": "2026-11-23T08:15:00Z" }
]
```

`last_used_at` is `null` for a key that has never been used, and is updated at most every few minutes.

### POST /api/me/keys

Create a named API key. The raw key is returned once — store it securely. Other keys are not affected.

**Auth:** JWT only.

**Request:**

```json
{ "name": "Claude Code on laptop" }
```

`name` is required and at most 60 characters (`400` otherwise). An account can hold at most `API_KEYS_MAX_PER_USER` keys (default 10); beyond that the response is `409`.

**Response `201`:**

```json
{ "id": "a1b2c3d4", "name": "Claude Code on laptop", "api_key": "pp_abc123...", "key_prefix": "pp_abc123", "created_at": "2026-11-23T10:00:00Z" }
```

### DELETE /api/me/keys/{id}

Revoke one of your keys. Anything using it stops working immediately; your other keys keep working.

**Auth:** JWT only.

**Response:** `204` on success, `404` if the key does not exist or belongs to someone else.

---

## Health

### GET /api/health

Public health check: reports whether the service is up.

**Auth:** none (public)

Returns `200` with `{ "status": "UP" }` when healthy, `503` with `{ "status": "DOWN" }` when the database or storage check fails.

### GET /api/admin/health

Full runtime statistics and storage info. Stats are cached for 30 seconds. The dashboard's Users tab shows this as the **Service health** card.

**Auth:** admin (`401` without credentials, `403` for non-admins)

Returns `200` when healthy, `503` (same body shape, `status: "DOWN"`) otherwise.

**Response:**

```json
{
  "status": "UP",
  "version": "0.8.0",
  "uptimeSeconds": 3600,
  "livePages": 12,
  "deletedPages": 3,
  "oldestPage": "2026-05-01T08:00:00Z",
  "newestPage": "2026-06-11T10:00:00Z",
  "storage": {
    "usedBytes": 204800,
    "usedHuman": "200 KB",
    "freeBytes": 10737418240,
    "freeHuman": "10.0 GB"
  },
  "users": {
    "total": 8,
    "active": 7,
    "inactive": 1,
    "awaitingVerification": 2
  }
}
```

`users.awaitingVerification` (accounts whose email is not yet verified) is only present when `EMAIL_ENABLED=true`.

---

## Config

### GET /api/config

Public, non-sensitive server settings the dashboard uses to adapt its UI (for example, whether to tell new users to verify their email). Add new frontend-visible settings in `ClientConfigService`; never expose secrets or per-user data here.

**Auth:** none (public)

**Response:**

```json
{
  "features": {
    "plans": false,
    "email": true
  }
}
```

| Field | Meaning |
|-------|---------|
| `features.plans` | `PLANS_ENABLED` — daily publish quota and plan-based retention |
| `features.email` | `EMAIL_ENABLED` — emails are sent: sign-up requires email verification and "Forgot password?" is available |

---

## Admin

### GET /api/admin/users

List all users ordered by creation date.

**Auth:** admin

**Response:**

```json
[
  {
    "id": "a1b2c3d4",
    "email": "alice@example.com",
    "created_at": "2026-01-01T00:00:00Z",
    "active": true,
    "admin": false,
    "active_page_count": 3
  }
]
```

---

### PATCH /api/admin/users/{id}/deactivate

Mark a user as inactive. Their pages are retained but they can no longer authenticate.

**Auth:** admin

**Path parameter:** `id` — 8-character user ID.

**Response:** `204 No Content` on success, `404 Not Found` if the ID does not exist.

---

### POST /api/admin/users/{id}/reset-password

Creates a one-time password reset link for the user, replacing any earlier one. Works with or without email: the admin hands the link to the user. The dashboard Users tab has a "Reset password" button for this.

**Auth:** admin

**Response `200`:**

```json
{
  "reset_link": "https://pushpage.link/dashboard?reset_token=...",
  "expires_at": "2026-10-28T11:00:00Z"
}
```

**Error responses:** `404` user not found, `400` user is deactivated.

---

### PATCH /api/admin/users/{id}/promote

Grant admin role to a user.

**Auth:** admin

**Path parameter:** `id` — 8-character user ID.

**Response:** `204 No Content` on success, `404 Not Found` if the ID does not exist.
