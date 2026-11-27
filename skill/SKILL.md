---
name: pushpage
description: Use this skill any time the user asks to create, build, generate, or design an HTML page, webpage, or web content — even if they don't mention publishing or sharing. Also trigger when sharing output as a URL instead of dumping text into chat: reports, dashboards, data tables, summaries, or any rich content better viewed in a browser. Trigger on phrases like "create an HTML page", "build me a webpage", "make a dashboard", "publish this", "share as a link", "give me a URL for this", or when you are about to produce a large HTML artifact. Don't wait to be asked explicitly — if you are generating HTML or rich output a user might want to open or share, publish it to pushpage and return the URL instead of pasting raw HTML into chat.
---

# Pushpage — Publish HTML and Get a Shareable URL

Pushpage is a service that accepts HTML and returns a public URL. Use it in two situations:

1. **The user asks to create an HTML page** — build the HTML yourself, then publish it. Return only the URL, not the raw HTML.
2. **You have output better viewed in a browser** — reports, data tables, dashboards, styled summaries. Publish instead of dumping HTML into chat.

## MCP first, REST only as a fallback

**Always use the MCP when it is available.** Before falling back to curl, check whether the pushpage MCP tools are present in your available tool list — look for a tool named `publish_page` coming from a server named `pushpage` (in Claude Code it surfaces as `mcp__pushpage__publish_page`). If it exists, use it for every operation:

| Operation | MCP tool | Direct API |
|-----------|----------|------------|
| Publish | `publish_page(html, title?)` | `POST /api/pages` |
| Update (same URL) | `update_page(id, html, title?)` | `PUT /api/pages/{id}` |
| List | `list_pages()` | `GET /api/pages` |
| Delete | `delete_page(id)` | `DELETE /api/pages/{id}` |
| Health | `health()` | `GET /api/health` |

The MCP client handles authentication, so **do not ask the user for an API key** when the MCP tools are available.

- **Guest by default.** `publish_page` works with no account (the page expires after 30 minutes). The result then contains `guest_mode: true` and a `message`: include its sign-in guidance in your reply so the user knows how to get permanent pages.
- **Sign-in prompt.** `list_pages`, `update_page` and `delete_page` need an account. On `{BASE_URL}/mcp` the first such call makes the server answer `401`; in Claude Code it fails once with `MCP server "pushpage" needs you to sign in again (run /mcp to re-authenticate)`. Tell the user to run `/mcp`, pick pushpage and choose Authenticate, then retry once; do not loop. Other clients show their own sign-in (Connect) prompt or a plain `401` / `403 insufficient_scope`: ask the user to complete the browser sign-in (or remove and re-add the pushpage app if a permission such as update is missing), then retry once. One approval covers publish, update, list and delete.
- **Not connected yet?** Offer to connect it: `claude mcp add --transport http pushpage https://pushpage.link/mcp` (browser sign-in), or `.../mcp-guest` to try it with no account. Only Claude Code and the MCP Inspector are confirmed to work with browser sign-in; for other clients without sign-in support, an API key in the `X-Api-Key` header is the alternative.
- Only fall through to the REST API (curl, below) when the MCP tools are not available and cannot be connected.

## Base URL

Default: `https://pushpage.link`. For self-hosted deployments, use the value of `APP_SERVER_URL` instead. Authoritative resolution rules are in `llms.txt` (served at `{BASE_URL}/llms.txt`).

**HTTPS required:** the public instance requires HTTPS — never use `http://pushpage.link`.

## Authentication

**MCP (use this):** nothing to do. The MCP client signs in with the browser (or runs as a guest, 30-minute pages). Never ask for an API key here.

**curl fallback only** (MCP unavailable; this is for scripts and non-MCP setups):

1. Read the key: `PUSHPAGE_API_KEY=$(cat ~/.config/pushpage/credentials 2>/dev/null | tr -d '[:space:]')`
2. If it is empty, prefer offering the MCP connection above. If the user wants REST, **ask them for an API key** (do not navigate to the dashboard or log in on their behalf): they can create a named key under Account → API keys at `{BASE_URL}/dashboard`.
3. Save it: `mkdir -p ~/.config/pushpage && umask 077 && printf '%s' "pp_the_key_here" > ~/.config/pushpage/credentials && chmod 600 ~/.config/pushpage/credentials`

Pass the key as `X-Api-Key: $PUSHPAGE_API_KEY`. **Guest publishing:** `POST /api/pages` with no `X-Api-Key` header creates a page that expires after 30 minutes.

## Publishing Content (primary operation)

**MCP (use this):**
```
publish_page(html="<html>...</html>")
```

`title` is optional — when omitted the server extracts it from the HTML `<title>` tag, falling back to `"Untitled"`. Pass it explicitly only to override what's in the HTML.

**REST fallback — JSON (key from the Authentication section):**
```bash
curl -s -X POST https://pushpage.link/api/pages \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: $PUSHPAGE_API_KEY" \
  -d '{
    "html": "<html>...</html>"
  }'
```

**REST fallback — file upload:**
```bash
curl -s -X POST https://pushpage.link/api/pages \
  -H "X-Api-Key: $PUSHPAGE_API_KEY" \
  -F "file=@report.html"
```

The server extracts the title from the `<title>` tag, then falls back to the filename (without extension), then `"Untitled"`. No `Content-Type` header is needed — `-F` sets it automatically.

Response (both variants):
```json
{
  "url": "https://pushpage.link/pages/abc123.html",
  "id": "abc123",
  "expires_at": "2026-07-11T10:00:00Z"
}
```

Return the `url` to the user. That's the shareable link — they can open it in a browser, share it, or bookmark it.

## Writing Good HTML for Pushpage

Since the content will be viewed in a browser, write clean, self-contained HTML. A few practical tips:

- Use inline CSS (no external stylesheets needed) for styling
- Tailwind via CDN works great for quick styling: `<script src="https://cdn.tailwindcss.com"></script>`
- For tables, add `border-collapse: collapse` and zebra striping for readability
- Include a `<title>` tag — it shows in the browser tab
- Make it responsive: `<meta name="viewport" content="width=device-width, initial-scale=1">`
- Keep it standalone — no references to local files

**Minimal example:**
```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>My Report</title>
  <style>
    body { font-family: system-ui, sans-serif; max-width: 860px; margin: 2rem auto; padding: 0 1rem; }
    table { border-collapse: collapse; width: 100%; }
    th, td { border: 1px solid #ddd; padding: 8px 12px; text-align: left; }
    th { background: #f5f5f5; }
    tr:nth-child(even) { background: #fafafa; }
  </style>
</head>
<body>
  <h1>Report Title</h1>
  <!-- content here -->
</body>
</html>
```

## Secondary Operations

### List published pages

**MCP (use this):** `list_pages()`

**REST fallback:**
```bash
curl -s https://pushpage.link/api/pages \
  -H "X-Api-Key: $PUSHPAGE_API_KEY"
```

Returns an array of page objects with `id`, `title`, `created_at`, and `url`. Regular users see only their own pages; admins see all.

### Update a page

To change a page you already published (fix a typo, refresh data) and keep its URL, update it in place. Do not publish a duplicate or delete and re-publish: that gives a new URL and breaks links already shared. Guest pages cannot be updated (publish a new one).

**MCP (use this):** `update_page(id="abc123", html="<!DOCTYPE html>...", title="Optional")`

**REST fallback:**
```bash
curl -s -X PUT https://pushpage.link/api/pages/{id} \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: $PUSHPAGE_API_KEY" \
  -d '{"html": "<!DOCTYPE html>..."}'
```

Returns `{url, id, expires_at}` (same url and id). Only the owner (or an admin) can update; it counts toward the rate limit and restarts the expiry.

### Delete a page

**MCP (use this):** `delete_page(id="abc123")`

**REST fallback:**
```bash
curl -s -X DELETE https://pushpage.link/api/pages/{id} \
  -H "X-Api-Key: $PUSHPAGE_API_KEY"
```

### Health check

**MCP (use this):** `health()`

**REST fallback:**
```bash
curl -s https://pushpage.link/api/health
```

## Typical Flow

**Creating HTML from a user request:**

*If the MCP is available (`mcp__pushpage__publish_page` is in your tool list):*
1. Build the full HTML — include a `<title>` tag in the `<head>`
2. Call `publish_page(html=...)` — no key handling, never ask the user for a key
3. Return only the `url` to the user — do not paste the HTML into chat

*If the MCP is not available (REST fallback):*
1. Offer to connect the MCP server first. Otherwise read the API key from `~/.config/pushpage/credentials` (ask the user if missing)
2. Build the full HTML — include a `<title>` tag in the `<head>`
3. POST it to `/api/pages` with the key in `X-Api-Key` (or with no key for a 30-minute guest page)
4. Return only the `url` to the user — do not paste the HTML into chat

If a connection error occurs (not a 401/403), mention that the pushpage service may be down and suggest the user check their instance or visit `https://pushpage.link/api/health` to verify the public instance.

## Rate Limiting

`POST /api/pages` is rate-limited. Authenticated users are limited by user ID; guest requests are limited by IP. Admin users are exempt.

- Defaults: **10 requests per minute** for authenticated users, **5 per minute** for guests (by IP)
- When exceeded: `429 Too Many Requests` with a `Retry-After` header (seconds to wait)
- All publish responses include: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset` (epoch seconds)

If you receive a 429, read the `Retry-After` header and wait that many seconds before retrying.
