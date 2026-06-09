# postpage

Self-hosted HTML page publishing service for a homelab. Post HTML, get a URL back.

## Start

```bash
docker compose up -d --build
```

The service will be available at `http://localhost`.

## Publish a page

```bash
curl -X POST http://localhost/publish \
  -H "Content-Type: application/json" \
  -d '{"title": "My Research", "html": "<h1>Hello</h1><p>Some content here.</p>"}'
```

Response:

```json
{
  "url": "http://localhost/pages/a1b2c3d4.html",
  "id": "a1b2c3d4"
}
```

If the HTML doesn't start with `<!DOCTYPE`, it's automatically wrapped in a minimal styled shell.

## API

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/publish` | Publish HTML, returns `{ url, id }` |
| `GET` | `/pages` | List all pages as JSON |
| `DELETE` | `/pages/{id}` | Delete a page (404 if not found) |
| `GET` | `/` | Browser index of all published pages |
| `GET` | `/health` | Health check |

## Custom hostname

To use `http://homelab.local` instead of `localhost`:

1. Edit `.env`:
   ```
   BASE_URL=http://homelab.local/pages
   ```

2. Point the hostname at your server (e.g. in `/etc/hosts` or your router's DNS):
   ```
   192.168.1.x  homelab.local
   ```

3. Restart: `docker compose up -d`

## Data

- Published HTML files are stored in a Docker named volume (`pages`), served directly by nginx.
- Page metadata (id, title, timestamp) is stored in a SQLite database in the `data` volume.
- Both volumes persist across restarts. To wipe everything: `docker compose down -v`.
