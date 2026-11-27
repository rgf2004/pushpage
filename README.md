# pushpage

[![rgf2004/pushpage MCP server](https://glama.ai/mcp/servers/rgf2004/pushpage/badges/score.svg)](https://glama.ai/mcp/servers/rgf2004/pushpage)
[![mcptry](https://mcptry.com/badge/io.github.rgf2004/pushpage)](https://mcptry.com/servers/io.github.rgf2004/pushpage)

An HTML page publishing service for AI agents. Connect your agent once (MCP, with a browser sign-in) and it hands you a shareable URL instead of dumping text into chat.

**Quick start:** `claude mcp add --transport http pushpage https://pushpage.link/mcp`, then ask your agent to publish a page. Publishing works as a guest (30-minute pages) until you sign in. To sign in, ask your agent to list your pages: the first account-only call triggers the prompt (in Claude Code run `/mcp` and choose Authenticate). Do that before publishing pages you want to keep. See [MCP Server](#mcp-server).

## Why this project exists

AI agents are increasingly capable of generating rich, complex outputs, but they have been missing a key piece: a way to *publish* and *share* that output. pushpage is that missing piece. Instead of dumping walls of text or Markdown into chat, an agent can publish HTML to pushpage (over MCP, or the REST API) and hand back a clean, shareable URL.

This project was directly inspired by [The Unreasonable Effectiveness of HTML](https://claude.com/blog/using-claude-code-the-unreasonable-effectiveness-of-html) by Thariq Shihipar (Claude Code team). The core insight: HTML is a dramatically more effective medium for AI-generated output than Markdown.

**Why HTML beats Markdown for human consumption:**
- Markdown is a plaintext approximation. Large documents become walls of text that people skim or abandon. HTML lets agents express the same information with visual hierarchy, tabs, tables, and illustrations that readers actually engage with.
- HTML is interactive. Sliders, toggleable sections, copy buttons, real-time previews. None of that is possible in Markdown. A spec or report that lets the reader interact with it closes the feedback loop far faster.
- HTML is shareable. A clickable link gets reviewed; a `.md` attachment does not. pushpage turns every agent output into a URL a human can actually open.

pushpage gives AI agents a publish endpoint so that the richer, more effective HTML output format is no longer a dead end. It becomes something you can share with a single link.

## Deployment

### Prerequisites

- Docker and Docker Compose
- A `.env` file in the project root. The one required variable is `APP_SERVER_URL`, which must match the URL where the service will be accessible. Set it to `http://localhost:8080` to get started quickly, or to a custom hostname if you have DNS set up for it. If you'd rather skip self-hosting, a managed public instance is available at **https://pushpage.link** — it runs this exact same open-source image, just with the `PLANS_ENABLED` and `EMAIL_ENABLED` feature flags turned on (see [`docs/configuration.md`](docs/configuration.md)) to enable subscription plans and email (sign-up verification and password-reset emails).

```env
APP_SERVER_URL=http://localhost:8080
```

All other variables have sensible defaults. See [`docs/configuration.md`](docs/configuration.md) for the full list.

### Running

Create the data directories, then pull the pre-built images and start the stack:

```bash
mkdir -p data/pages data/pgdata
docker compose up -d
```

Verify the service is running:

```bash
curl https://pushpage.link/api/health
```

Or open the Swagger UI in a browser: `https://pushpage.link/api/swagger-ui/index.html`

Or open the dashboard: `https://pushpage.link/dashboard`

To stop the stack:

```bash
docker compose down
```

To wipe all published pages and metadata:

```bash
docker compose down -v
```

## Getting Started

### Connect your agent (everyone)

1. Add the MCP server (managed instance shown; self-hosted: use `{APP_SERVER_URL}/mcp`):

   ```bash
   claude mcp add --transport http pushpage https://pushpage.link/mcp
   ```

2. Ask your agent to publish a page. Publishing works right away as a guest (pages expire after 30 minutes) and never asks you to sign in.
3. To keep pages, sign in before publishing them: ask your agent to list your pages (the first account-only call triggers the prompt). Claude Code answers `needs you to sign in again (run /mcp to re-authenticate)`. Run `/mcp`, pick pushpage and choose Authenticate, and approve in the browser. No API key to copy; revoke it any time under Account, Connected apps in the dashboard.

Details, other clients and troubleshooting: [MCP Server](#mcp-server) and [`docs/mcp.md`](docs/mcp.md). Prefer scripts? See the [REST API](#rest-api-for-scripts-and-integrations) and [`docs/api.md`](docs/api.md).

### Self-hosting: first run (operators)

#### 1. Get the bootstrap admin key

On first run, if the database has no users, the service automatically creates an `admin` user and logs its API key once:

```
==============================================================
No admin user found — bootstrap admin created.
API Key: pp_abc123...
Copy this key now. It will NOT appear again after restart.
==============================================================
```

Copy that key. It is for operators (administering the instance, scripts, headless setups); ordinary users connect through MCP as above.

#### 2. Open the dashboard (optional)

Navigate to `https://pushpage.link/dashboard` (or your self-hosted equivalent) and sign in. The dashboard lets you browse and delete your pages, view account info, connect your agent, manage API keys and connected apps, and (as admin) manage users.

#### 3. Publish a page with the REST API (scripts)

You can publish with the admin key from a script. No extra setup required.

```bash
curl -X POST https://pushpage.link/api/pages \
  -H "X-Api-Key: pp_abc123..." \
  -H "Content-Type: application/json" \
  -d '{"html": "<html><head><title>My Report</title></head><body><h1>Hello</h1><p>Some content here.</p></body></html>"}'
```

Response:

```json
{
  "url": "https://pushpage.link/pages/a1b2c3d4.html",
  "id": "a1b2c3d4"
}
```

Open the `url` in your browser — that's your published page.

`POST /api/pages` also works without an API key — guest pages auto-expire after 30 minutes and are not tied to any account, ideal for quick one-off shares.

#### 4. Create a dedicated user (optional)

The admin key is enough for a single-agent or personal setup. If you want to give a separate key to a different agent or user (so their pages are scoped independently), create a dedicated user:

```bash
curl -X POST https://pushpage.link/api/admin/users \
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

The `skill/` directory contains a skill file (`skill/SKILL.md`) for use with AI agent frameworks such as Claude Code. Loading this skill tells the agent how to interact with pushpage (use the MCP tools when present and never ask the user for an API key; REST with a key only as a fallback): when to publish, how to structure the HTML, and how to return the resulting URL to the user.

Once the skill is loaded, the agent will automatically publish rich HTML output to pushpage instead of dumping content into chat, and return a clickable link.

See [`skill/SKILL.md`](skill/SKILL.md) for the full skill definition and usage examples.

### llms.txt

[`llms.txt`](llms.txt) is served at `https://pushpage.link/llms.txt` (or `{APP_SERVER_URL}/llms.txt` for self-hosted instances) and follows the [llms.txt convention](https://llmstxt.org) — a plain-text file that describes what a service does and how to interact with it. An agent that discovers the pushpage instance via HTTP can read this file to understand the API, authentication, and typical usage flow without any prior configuration.

## MCP Server

pushpage ships a **cloud MCP server** that lets any MCP-compatible client publish, update, list, and delete pages — no local install required. Agents connect to it with a single URL.

The MCP server starts automatically with the rest of the stack — no extra configuration needed:

```bash
docker compose up -d
```

Once running, the server is available at `https://pushpage.link/mcp` (or `{APP_SERVER_URL}/mcp` for self-hosted instances). How to connect, in order of preference:

1. **Browser sign-in (recommended).** Connect to `/mcp` with the URL alone. Publishing works at once as a guest (30-minute pages, no prompt); the first time a tool that needs an account is used (ask your agent to list your pages to trigger it) (`list_pages`, `update_page`, `delete_page`) the server answers `401` and a client that supports MCP sign-in prompts you to sign in in the browser. One approval grants publish, update, list and delete, with no key to copy. Only Claude Code and the MCP Inspector are confirmed to work with the flow so far.
2. **Guest (no account, "try it").** `/mcp-guest` never prompts: `publish_page` works, pages expire after 30 minutes, and the result tells the user to re-add the server with `/mcp` to sign in.
3. **API key (headless, CI, local `uvx`, clients without browser sign-in).** Send a key in the `Authorization` or `X-Api-Key` header: permanent retention per plan, `list_pages`, `update_page` and `delete_page` with no prompt.

**Claude Code CLI (browser sign-in):**
```bash
claude mcp add --transport http pushpage https://pushpage.link/mcp
```
In Claude Code the first call to a tool that needs an account fails once with `MCP server "pushpage" needs you to sign in again (run /mcp to re-authenticate)`: run `/mcp`, pick pushpage and choose Authenticate. For a keyless setup that never prompts, use `https://pushpage.link/mcp-guest`.

**Headless / CI: Claude Code CLI with an API key:**
```bash
claude mcp add --transport http pushpage http://your-domain/mcp \
  --header "Authorization: Bearer pp_your_key_here"
```

**Headless / CI: Claude Desktop `claude_desktop_config.json` with an API key** (browser sign-in in Claude Desktop is not verified; this is the dependable path):
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

<!-- VS Code link decodes to {"name":"pushpage","type":"http","url":"https://pushpage.link/mcp/signin"} (double URL-encoded by the vscode.dev wrapper). Update when /mcp/signin is removed, see CLAUDE.md. -->
**One-click install for VS Code/Cursor (adds the sign-in URL `/mcp/signin`, deprecated; not yet verified with sign-in):** [Add to VS Code](https://vscode.dev/redirect?url=vscode%3Amcp%2Finstall%3F%257B%2522name%2522%253A%2522pushpage%2522%252C%2522type%2522%253A%2522http%2522%252C%2522url%2522%253A%2522https%253A%252F%252Fpushpage.link%252Fmcp%252Fsignin%2522%257D) · Cursor: use the "Add to Cursor" button on the [MCP setup guide](https://pushpage.link/guides/mcp-server-publish-html) (GitHub does not render `cursor://` links). Both add `https://pushpage.link/mcp/signin` and ask you to confirm; the dashboard's Account tab shows the same buttons for a self-hosted instance. Claude Code (verified): `claude mcp add --transport http pushpage https://pushpage.link/mcp`.

**How sign-in works:** `/mcp` also works anonymously (`publish_page`, `health`); when a tool that needs an account is called without a credential, the server answers `401` and clients that support MCP OAuth open the browser sign-in and retry. `/mcp-guest` never prompts. See [`docs/oauth.md`](docs/oauth.md).

**Storing an API key (headless setups only):** keep it in `~/.config/pushpage/credentials` (`chmod 600`) and, where your client supports it (Claude Code, Cursor), reference it as an environment variable instead of pasting it into config. Details in [`docs/mcp.md`](docs/mcp.md#storing-your-api-key).

The MCP server can also run locally over stdio with no clone or Docker (no browser sign-in there, so it takes an API key): `uvx pushpage-mcp` with `PUSHPAGE_API_KEY` set (PyPI package [`pushpage-mcp`](https://pypi.org/project/pushpage-mcp/)); see [`docs/mcp.md`](docs/mcp.md#run-locally-with-uvx-pypi-package).

Step-by-step guides are on the site: [share HTML from Claude Code](https://pushpage.link/guides/share-html-from-claude-code), [MCP server setup](https://pushpage.link/guides/mcp-server-publish-html) and [HTML hosting for AI agents](https://pushpage.link/guides/html-hosting-for-ai-agents).

For the full tool reference, alternative connection methods (including `mcp-remote` for HTTP-only or older clients), and troubleshooting, see [`docs/mcp.md`](docs/mcp.md).

## REST API (for scripts and integrations)

Agents should use the [MCP server](#mcp-server) above. The REST API is for scripts, CI and non-MCP integrations. All endpoints are under `/api` (Spring Boot context path). Most require an API key via `X-Api-Key: <key>` or `Authorization: Bearer <key>`.

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/api/pages` | Optional | Publish HTML (JSON or file upload), returns `{ url, id }` |
| `PUT` | `/api/pages/{id}` | User | Replace a page's HTML (and title), keeping the same id and URL; owner or admin only, expiry restarts |
| `POST` | `/api/oauth/register`, `/api/oauth/token` | None | OAuth for MCP browser sign-in (see `docs/oauth.md`) |
| `GET` | `/api/health` | None | Health check |
| `GET` | `/api/admin/health` | Admin | Runtime stats (version, uptime, page counts, storage, user counts) |
| `GET` | `/api/config` | None | Public frontend config (enabled feature flags) |

For the full API reference — page management, admin user endpoints, request/response schemas — see the Swagger UI: `https://pushpage.link/api/swagger-ui/index.html`

## Security

Published pages are served with a `Content-Security-Policy: sandbox` header (without `allow-same-origin`), so scripts in a page cannot access the dashboard session or call the API on a viewer's behalf. The dashboard cannot be framed, and login/signup are rate-limited per IP by nginx, and dot-prefixed paths such as `/.env` are never served. Behind a reverse proxy or tunnel, set `TRUSTED_PROXIES` (and `REAL_IP_HEADER`) so rate limits use the real client IP. Details in [`docs/configuration.md`](docs/configuration.md#security-hardening-nginx).

## Database

pushpage uses **PostgreSQL**. `docker-compose.yml` starts a co-located `postgres:17-alpine` container automatically — no extra setup required.

To point at an external PostgreSQL instance instead, override the connection variables in your `.env`:

```env
DB_HOST=your-postgres-host
DB_NAME=pushpage
DB_USER=pushpage
DB_PASSWORD=changeme
```

See [`docs/configuration.md`](docs/configuration.md) for the full variable reference.

## Contributing / Local Development

To build from source and run locally:

```bash
docker compose -f docker-compose.dev.yml up -d --build
```

This starts a PostgreSQL container alongside the publisher (using tmpfs, so data is not persisted between restarts).
