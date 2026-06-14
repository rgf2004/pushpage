# API Reference

All endpoints are served under the `/api` Spring Boot context path.

## Authentication

All endpoints except `GET /api/health` require an API key.

Pass the key in one of two ways:

| Method | Header |
|--------|--------|
| API Key | `X-Api-Key: <key>` |
| Bearer token | `Authorization: Bearer <key>` |

Missing or invalid keys return `401 Unauthorized`. Calling an admin endpoint without admin privileges returns `403 Forbidden`.

### Bootstrap

On first run (no users in the database), the service generates an `admin` API key, stores its hash, and logs the raw key once:

```
==============================================================
No admin user found — bootstrap admin created.
API Key: pp_abc123...
Copy this key now. It will NOT appear again after restart.
==============================================================
```

Restart after copying. The key is never logged again once a user exists.

---

## POST /api/publish

Publish an HTML page and receive a shareable URL. If the content does not start with `<!DOCTYPE>`, it is automatically wrapped in a minimal HTML shell.

**Auth:** any user

**Request body:**

```json
{
  "title": "My Report",
  "html": "<html>...</html>"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `title` | string | yes | Human-readable title for the page |
| `html` | string | yes | Full HTML content. Max size controlled by `MAX_FILE_SIZE`. |

**Response headers:**

| Header | Description |
|--------|-------------|
| `X-Max-File-Size` | The configured max file size limit in bytes |

**Response body:**

```json
{
  "url": "http://pushpage.homelab.local/pages/abc123.html",
  "id": "abc123"
}
```

**Error responses:**

| Status | Reason |
|--------|--------|
| `401` | Missing or invalid API key |
| `413` | Payload exceeds `MAX_FILE_SIZE` |
| `500` | Failed to write file |

---

## GET /api/pages

List published pages ordered by publish date descending. Regular users see only their own pages; admins see all.

**Auth:** any user

**Response:** array of page objects.

```json
[
  {
    "id": "abc123",
    "title": "My Report",
    "created_at": "2026-06-11T10:00:00Z",
    "user_id": "a1b2c3d4",
    "url": "http://pushpage.homelab.local/pages/abc123.html"
  }
]
```

---

## DELETE /api/pages/{id}

Delete a published page. Users may only delete their own pages; admins can delete any page.

**Auth:** any user (owner or admin)

**Path parameter:** `id` — the page ID returned by `/api/publish`.

**Response:** `204 No Content` on success.

**Error responses:**

| Status | Reason |
|--------|--------|
| `403` | Page belongs to a different user |
| `404` | Page not found |

---

## GET /api/health

Health check endpoint. Returns runtime statistics and storage info. Stats are cached for 30 seconds.

**Auth:** none (public)

Returns `200` when healthy, `503` when a critical subsystem is unavailable.

**Response:**

```json
{
  "status": "UP",
  "version": "0.4.0",
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

| Field | Description |
|-------|-------------|
| `status` | `UP` or `DOWN` |
| `version` | Application version |
| `uptimeSeconds` | Seconds since the service started |
| `livePages` | Number of currently published pages |
| `deletedPages` | Number of pages deleted since startup |
| `oldestPage` | ISO-8601 timestamp of the oldest live page (omitted if no pages) |
| `newestPage` | ISO-8601 timestamp of the newest live page (omitted if no pages) |
| `storage.usedBytes` | Total bytes used by published HTML files |
| `storage.usedHuman` | Human-readable used storage |
| `storage.freeBytes` | Available disk space in the pages volume |
| `storage.freeHuman` | Human-readable free space |

When `status` is `DOWN`, `storage` is omitted and `livePages`/`deletedPages` are `0`.

---

## POST /api/admin/users

Create a new user. The returned `api_key` is shown only once.

**Auth:** admin

**Request body:**

```json
{
  "username": "myagent",
  "admin": false
}
```

**Response:** `201 Created`

```json
{
  "id": "a1b2c3d4",
  "username": "myagent",
  "api_key": "pp_abc123...",
  "created_at": "2026-01-01T00:00:00Z",
  "admin": false
}
```

**Error responses:**

| Status | Reason |
|--------|--------|
| `400` | Username is blank |
| `409` | Username already exists |

---

## GET /api/admin/users

List all users. API keys are not included.

**Auth:** admin

**Response:**

```json
[
  {
    "id": "a1b2c3d4",
    "username": "myagent",
    "created_at": "2026-01-01T00:00:00Z",
    "active": true,
    "admin": false
  }
]
```

---

## PATCH /api/admin/users/{id}/deactivate

Mark a user as inactive. Their pages are retained but they can no longer authenticate.

**Auth:** admin

**Path parameter:** `id` — 8-character user ID.

**Response:** `204 No Content` on success, `404 Not Found` if the ID does not exist.
