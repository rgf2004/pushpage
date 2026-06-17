---
name: pushpage
description: Use this skill any time the user asks to create, build, generate, or design an HTML page, webpage, or web content — even if they don't mention publishing or sharing. Also trigger when sharing output as a URL instead of dumping text into chat: reports, dashboards, data tables, summaries, or any rich content better viewed in a browser. Trigger on phrases like "create an HTML page", "build me a webpage", "make a dashboard", "publish this", "share as a link", "give me a URL for this", or when you are about to produce a large HTML artifact. Don't wait to be asked explicitly — if you are generating HTML or rich output a user might want to open or share, publish it to pushpage and return the URL instead of pasting raw HTML into chat.
---

# Pushpage — Publish HTML and Get a Shareable URL

Pushpage is a self-hosted service that accepts HTML and returns a public URL. Use it in two situations:

1. **The user asks to create an HTML page** — build the HTML yourself, then publish it. Return only the URL, not the raw HTML.
2. **You have output better viewed in a browser** — reports, data tables, dashboards, styled summaries. Publish instead of dumping HTML into chat.

## Base URL

```
http://pushpage.homelab.local/api
```

## Authentication

All endpoints (except `/api/health`) require an API key. Read it from the credentials file before making any request:

```bash
PUSHPAGE_API_KEY=$(cat ~/.config/pushpage/credentials 2>/dev/null | tr -d '[:space:]')
```

If the file is missing or empty, stop and tell the user:

> Your pushpage API key is not configured. Run the following to save it:
> ```bash
> mkdir -p ~/.config/pushpage && echo "pp_your_key_here" > ~/.config/pushpage/credentials
> ```
> You can find your key in the pushpage startup logs, or ask an admin to create one for you via `POST /api/admin/users`.

Pass the key in every request as `X-Api-Key: $PUSHPAGE_API_KEY`.

## Publishing Content (primary operation)

Read the key, then POST to `/api/pages`:

```bash
PUSHPAGE_API_KEY=$(cat ~/.config/pushpage/credentials 2>/dev/null | tr -d '[:space:]')
curl -s -X POST http://pushpage.homelab.local/api/pages \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: $PUSHPAGE_API_KEY" \
  -d '{
    "title": "My Report",
    "html": "<html>...</html>"
  }'
```

Response:
```json
{
  "url": "http://pushpage.homelab.local/pages/abc123.html",
  "id": "abc123"
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

```bash
PUSHPAGE_API_KEY=$(cat ~/.config/pushpage/credentials 2>/dev/null | tr -d '[:space:]')
curl -s http://pushpage.homelab.local/api/pages \
  -H "X-Api-Key: $PUSHPAGE_API_KEY"
```

Returns an array of page objects with `id`, `title`, `created_at`, and `url`. Regular users see only their own pages; admins see all.

### Delete a page

```bash
PUSHPAGE_API_KEY=$(cat ~/.config/pushpage/credentials 2>/dev/null | tr -d '[:space:]')
curl -s -X DELETE http://pushpage.homelab.local/api/pages/{id} \
  -H "X-Api-Key: $PUSHPAGE_API_KEY"
```

### Health check (no auth required)

```bash
curl -s http://pushpage.homelab.local/api/health
```

## Typical Flow

**Creating HTML from a user request:**
1. Read the API key from `~/.config/pushpage/credentials` — stop with the setup message if missing
2. Build the full HTML for what the user asked for
3. POST it to `/api/pages` with the key in `X-Api-Key` and a descriptive `title`
4. Return only the `url` to the user — do not paste the HTML into chat

**Publishing existing content:**
1. Read the API key from `~/.config/pushpage/credentials` — stop with the setup message if missing
2. Wrap the content in clean, styled HTML
3. POST to `/api/pages` with the key and a descriptive `title`
4. Extract the `url` from the response and present it as a clickable link

If the curl fails with a connection error (not a 401/403), mention that the pushpage service at `pushpage.homelab.local` may be down and suggest the user check their homelab.
