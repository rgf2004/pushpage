# pushpage

An HTML page publishing service. AI agents POST HTML content and get a shareable URL back instead of dumping text into chat.

## Why this project exists

AI agents are increasingly capable of generating rich, complex outputs, but they have been missing a key piece: a way to *publish* and *share* that output. pushpage is that missing piece. Instead of dumping walls of text or Markdown into chat, an agent can POST HTML to pushpage and hand back a clean, shareable URL.

This project was directly inspired by [The Unreasonable Effectiveness of HTML](https://claude.com/blog/using-claude-code-the-unreasonable-effectiveness-of-html) by Thariq Shihipar (Claude Code team). The core insight: HTML is a dramatically more effective medium for AI-generated output than Markdown.

**Why HTML beats Markdown for human consumption:**
- Markdown is a plaintext approximation. Large documents become walls of text that people skim or abandon. HTML lets agents express the same information with visual hierarchy, tabs, tables, and illustrations that readers actually engage with.
- HTML is interactive. Sliders, toggleable sections, copy buttons, real-time previews. None of that is possible in Markdown. A spec or report that lets the reader interact with it closes the feedback loop far faster.
- HTML is shareable. A clickable link gets reviewed; a `.md` attachment does not. pushpage turns every agent output into a URL a human can actually open.

pushpage gives AI agents a publish endpoint so that the richer, more effective HTML output format is no longer a dead end. It becomes something you can share with a single link.

## API

All endpoints are under `/api` (Spring Boot context path). Most require an API key via `X-Api-Key: <key>` or `Authorization: Bearer <key>`.

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/pages` | User | Publish HTML, returns `{ url, id }` |
| `GET` | `/api/health` | None | Health check |

For the full API reference — page management, admin user endpoints, request/response schemas — see the Swagger UI: `{APP_SERVER_URL}/api/swagger-ui/index.html`

## Deployment

### Prerequisites

- Docker and Docker Compose
- A `.env` file in the project root. The one required variable is `APP_SERVER_URL`, which must match the URL where the service will be accessible. Set it to `http://localhost:8080` to get started quickly, or to a custom hostname (e.g. `http://pushpage.homelab.local`) if you have DNS set up for it:

```env
APP_SERVER_URL=http://localhost:8080
```

All other variables have sensible defaults. See [`docs/configuration.md`](docs/configuration.md) for the full list.

### Running

Pull the pre-built images from Docker Hub and start the stack:

```bash
docker compose up -d
```

Verify the service is running:

```bash
curl {APP_SERVER_URL}/api/health
```

Or open the Swagger UI in a browser: `{APP_SERVER_URL}/api/swagger-ui/index.html`

Or open the dashboard: `{APP_SERVER_URL}/dashboard`

To stop the stack:

```bash
docker compose down
```

To wipe all published pages and metadata:

```bash
docker compose down -v
```

## Getting Started

### 1. Get your API key

On first run, if the database has no users, the service automatically creates an `admin` user and logs its API key once:

```
==============================================================
No admin user found — bootstrap admin created.
API Key: pp_abc123...
Copy this key now. It will NOT appear again after restart.
==============================================================
```

Copy that key — you'll use it in the next step.

### 2. Open the dashboard (optional)

Navigate to `{APP_SERVER_URL}/dashboard` and enter your API key. The dashboard lets you browse and delete your pages, view account info, and (as admin) manage users — all without touching the API directly.

### 3. Publish your first page

You can start publishing immediately with the admin key. No extra setup required.

```bash
curl -X POST {APP_SERVER_URL}/api/pages \
  -H "X-Api-Key: pp_abc123..." \
  -H "Content-Type: application/json" \
  -d '{"html": "<html><head><title>My Report</title></head><body><h1>Hello</h1><p>Some content here.</p></body></html>"}'
```

Response:

```json
{
  "url": "{APP_SERVER_URL}/pages/a1b2c3d4.html",
  "id": "a1b2c3d4"
}
```

Open the `url` in your browser — that's your published page.

### 4. Create a dedicated user (optional)

The admin key is enough for a single-agent or personal setup. If you want to give a separate key to a different agent or user (so their pages are scoped independently), create a dedicated user:

```bash
curl -X POST {APP_SERVER_URL}/api/admin/users \
  -H "X-Api-Key: pp_abc123..." \
  -H "Content-Type: application/json" \
  -d '{"username": "myagent", "email": "agent@example.com", "admin": false}'
```

Response — save the `api_key`, it is only shown once:

```json
{
  "id": "a1b2c3d4",
  "username": "myagent",
  "email": "agent@example.com",
  "api_key": "pp_xyz789...",
  "created_at": "2026-01-01T00:00:00Z",
  "admin": false
}
```

That user can now publish pages with their own key. Regular users only see their own pages in `GET /api/pages`; admins see all.

## Agent Integration

### Skill file

The `skill/` directory contains a skill file (`skill/SKILL.md`) for use with AI agent frameworks such as Claude Code. Loading this skill tells the agent how to interact with pushpage: when to publish, how to structure the HTML, and how to return the resulting URL to the user.

Once the skill is loaded, the agent will automatically publish rich HTML output to pushpage instead of dumping content into chat, and return a clickable link.

See [`skill/SKILL.md`](skill/SKILL.md) for the full skill definition and usage examples.

### llms.txt

[`llms.txt`](llms.txt) is served at `{APP_SERVER_URL}/llms.txt` and follows the [llms.txt convention](https://llmstxt.org) — a plain-text file that describes what a service does and how to interact with it. An agent that discovers the pushpage instance via HTTP can read this file to understand the API, authentication, and typical usage flow without any prior configuration.

## MCP Server

pushpage ships a **cloud MCP server** that lets any MCP-compatible client (Claude Desktop, Claude Code, Cursor, etc.) publish, list, and delete pages — no local install required. Agents connect to it with a single URL.

### Tools exposed

| Tool | Description |
|------|-------------|
| `publish_page(title, html)` | Publish an HTML page, returns the shareable URL |
| `list_pages()` | List published pages visible to the authenticated user |
| `delete_page(id)` | Delete a page by ID |
| `health` | Check whether the pushpage service is reachable |

### Enabling the MCP server

The MCP server starts automatically with the rest of the stack — no extra configuration needed:

```bash
docker compose up -d
```

### Connecting agents

Each client supplies their own pushpage API key via the `Authorization` header in their MCP client config. Once running, the MCP server is available at `http://your-domain/mcp`.

**Claude Desktop `claude_desktop_config.json`:**
```json
{
  "mcpServers": {
    "pushpage": {
      "url": "http://your-domain/mcp",
      "headers": {
        "Authorization": "Bearer pp_your_key_here"
      }
    }
  }
}
```

**Claude Code CLI:**
```bash
claude mcp add --transport http pushpage http://your-domain/mcp \
  --header "Authorization: Bearer pp_your_key_here"
```

## Database

pushpage defaults to **SQLite** — no extra setup required. The database file lives in the `data` Docker volume.

To use **PostgreSQL** instead, start the stack with `docker-compose.postgres.yml`:

```bash
docker compose -f docker-compose.postgres.yml up -d
```

This brings up a complete stack including a co-located `postgres:17-alpine` container. To point at an external PostgreSQL instance instead, override the connection variables in your `.env`:

```env
DB_HOST=your-postgres-host
DB_NAME=pushpage
DB_USER=pushpage
DB_PASSWORD=changeme
```

See [`docs/configuration.md`](docs/configuration.md) for the full variable reference.

## Contributing / Local Development

To build from source and run locally (SQLite by default):

```bash
docker compose -f docker-compose.dev.yml up -d --build
```

To develop against PostgreSQL, uncomment the `postgres` service and the `SPRING_PROFILES_ACTIVE` environment variable in `docker-compose.dev.yml`.
