# API Reference

All endpoints are served under the `/api` Spring Boot context path.

## POST /api/publish

Publish an HTML page and receive a shareable URL. If the content does not start with `<!DOCTYPE>`, it is automatically wrapped in a minimal HTML shell.

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
| `413` | Payload exceeds `MAX_FILE_SIZE` |
| `500` | Failed to write file |

---

## GET /api/pages

List all published pages, ordered by publish date descending.

**Response:** array of page objects.

```json
[
  {
    "id": "abc123",
    "title": "My Report",
    "created_at": "2026-06-11T10:00:00Z",
    "url": "http://pushpage.homelab.local/pages/abc123.html"
  }
]
```

---

## DELETE /api/pages/{id}

Delete a published page by ID. Removes both the HTML file and the metadata record.

**Path parameter:** `id` — the page ID returned by `/api/publish`.

**Response:** `204 No Content` on success, `404 Not Found` if the ID does not exist.

---

## GET /api/health

Health check endpoint. Returns runtime statistics and storage info. Stats are cached for 30 seconds.

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
