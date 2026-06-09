---
name: pushpage
description: Use this skill whenever you need to share output — a report, summary, dashboard, data table, analysis, or any content — as a clickable URL instead of dumping text into chat. This skill publishes HTML to a self-hosted pushpage service and returns a shareable link. Trigger whenever the user or another agent says things like "publish this", "share as a link", "create a page", "push to pushpage", "give me a URL for this", or when presenting results that would be better experienced in a browser. Also trigger when generating HTML reports, structured summaries, or rich output that a user might want to open, bookmark, or share. Don't wait to be asked explicitly — if you're about to dump a large table or report into chat, proactively offer to publish it instead.
---

# Pushpage — Publish HTML and Get a Shareable URL

Pushpage is a self-hosted service that accepts HTML and returns a public URL. Use it any time content would be better viewed in a browser than read in chat — formatted reports, data tables, dashboards, summaries with styling, etc.

## Base URL

```
http://pushpage.homelab.local/api
```

## Publishing Content (primary operation)

POST to `/api/publish` with a JSON body:

```bash
curl -s -X POST http://pushpage.homelab.local/api/publish \
  -H "Content-Type: application/json" \
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

### List all published pages

```bash
curl -s http://pushpage.homelab.local/api/pages
```

Returns an array of page objects with `id`, `title`, and `createdAt`.

### Delete a page

```bash
curl -s -X DELETE http://pushpage.homelab.local/api/pages/{id}
```

### Health check

```bash
curl -s http://pushpage.homelab.local/api/health
```

## Typical Flow

1. Generate or receive the content (report text, data table, analysis results, etc.)
2. Wrap it in clean, styled HTML
3. POST to `/api/publish` with a descriptive `title`
4. Extract the `url` from the response
5. Present the URL to the user as a clickable link

If the curl fails (connection refused, host unreachable), mention that the pushpage service at `pushpage.homelab.local` may be down and suggest the user check their homelab.
