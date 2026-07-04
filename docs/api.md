# API Reference

All endpoints are served under the `/api` Spring Boot context path.

## Authentication

Most endpoints require authentication. Two credential types are accepted:

| Method | Header | Notes |
|--------|--------|-------|
| API key | `X-Api-Key: pp_<key>` | Long-lived. Obtain via `POST /me/tokens`. |
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

### POST /api/me/tokens

Generate or rotate the caller's API key. The previous key is invalidated immediately. The raw key is returned once — store it securely.

**Auth:** any user (JWT or API key)

**Response `200`:**

```json
{ "api_key": "pp_abc123..." }
```

---

## Health

### GET /api/health

Health check endpoint. Returns runtime statistics and storage info. Stats are cached for 30 seconds.

**Auth:** none (public)

Returns `200` when healthy, `503` when a critical subsystem is unavailable.

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
  }
}
```

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

`active_page_count` is the number of the user's pages that are not soft-deleted and not expired. Cloud deployments may add extra nullable fields (e.g. `plan`, `email_verified`) that are absent in self-hosted responses.

---

### PATCH /api/admin/users/{id}/deactivate

Mark a user as inactive. Their pages are retained but they can no longer authenticate.

**Auth:** admin

**Path parameter:** `id` — 8-character user ID.

**Response:** `204 No Content` on success, `404 Not Found` if the ID does not exist.

---

### PATCH /api/admin/users/{id}/promote

Grant admin role to a user.

**Auth:** admin

**Path parameter:** `id` — 8-character user ID.

**Response:** `204 No Content` on success, `404 Not Found` if the ID does not exist.
