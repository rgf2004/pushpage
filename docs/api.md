# API Reference

All endpoints are served under the `/api` Spring Boot context path.

## POST /api/publish

Publish an HTML page and receive a shareable URL.

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

**Response:**

```json
{
  "url": "http://pushpage.homelab.local/pages/abc123.html",
  "id": "abc123"
}
```

**Error responses:**

| Status | Reason |
|--------|--------|
| `400` | Missing or invalid request body |
| `413` | Payload exceeds `MAX_FILE_SIZE` |

---

## GET /api/pages

List all published pages.

**Response:** array of page objects.

```json
[
  {
    "id": "abc123",
    "title": "My Report",
    "createdAt": "2026-06-11T10:00:00Z"
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

Health check endpoint. Returns service status and basic statistics.

**Response:**

```json
{
  "status": "UP",
  "totalPages": 12
}
```
