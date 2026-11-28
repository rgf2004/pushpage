# pushpage

[![rgf2004/pushpage MCP server](https://glama.ai/mcp/servers/rgf2004/pushpage/badges/score.svg)](https://glama.ai/mcp/servers/rgf2004/pushpage)
[![mcptry](https://mcptry.com/badge/io.github.rgf2004/pushpage)](https://mcptry.com/servers/io.github.rgf2004/pushpage)

**Publish HTML. Get a link.**

pushpage gives your AI agent a "publish" button. Instead of pasting a wall of text into chat, the agent builds a proper HTML page and hands you a URL you can open, share and forget about.

Jump to: [Try it](#try-it-in-30-seconds) · [Why I built this](#why-i-built-this) · [Connect your agent](#connect-your-agent) · [Self-hosting](#self-hosting) · [REST API](#rest-api)

## Try it in 30 seconds

```bash
claude mcp add --transport http pushpage https://pushpage.link/mcp
```

Then ask your agent to publish something: a report, a chart, a comparison table. It sends back a link.

- **Publishing just works** as a guest. Guest pages last 30 minutes.
- **Want to keep your pages?** Sign in first. Ask your agent to list your pages, and in Claude Code run `/mcp`, pick pushpage and hit Authenticate. Approve in the browser and you're done. No API key to copy, and you can revoke access any time under Account, Connected apps in the dashboard.
- **Heads up:** pages are public. Anyone with the link can read them, so don't publish secrets.

Using something other than Claude Code? See [Connect your agent](#connect-your-agent).

## Why I built this

I like Markdown. I can read it, I can write it, and I'm perfectly happy in a text editor.

But when an AI agent hands me a long report, I'd much rather get a web page: real headings, tables, charts and graphs, sections I can collapse, a layout that makes the thing easy to take in at a glance. Markdown in a chat window turns into a wall of text that I skim and forget.

The catch is that HTML is awkward to share. A file attachment doesn't get opened. A link does. pushpage closes that gap: the agent publishes the HTML, you get a URL.

This project was inspired by [The Unreasonable Effectiveness of HTML](https://claude.com/blog/using-claude-code-the-unreasonable-effectiveness-of-html) by Thariq Shihipar (Claude Code team). The short version of why HTML beats Markdown for reading:

- **It's easier on the eyes.** Visual hierarchy, tabs, tables and illustrations get read. Walls of Markdown get abandoned.
- **It can be interactive.** Sliders, toggles, copy buttons, live previews. A report you can poke at closes the feedback loop much faster.
- **It's shareable.** A clickable link gets reviewed. A `.md` attachment mostly doesn't.

## Connect your agent

There are three ways in. Pick the first one unless you have a reason not to.

| | Best for | How |
|---|---|---|
| **Browser sign-in** (recommended) | Your own agent and your own pages | Connect to the plain `/mcp` URL. Publishing works as a guest right away; the first time you list, update or delete pages, your client asks you to sign in in the browser. |
| **Guest** | Just trying it out | Connect to `/mcp-guest`. It never asks you to sign in. Publishing only, 30-minute pages. |
| **API key** | CI, scripts, the local `uvx` server, clients without browser sign-in | Send a key in the `Authorization: Bearer` or `X-Api-Key` header. No prompts, and pages follow your plan's retention. |

Self-hosting? Use `{APP_SERVER_URL}/mcp` instead of `https://pushpage.link/mcp`.

**Claude Code (browser sign-in):**

```bash
claude mcp add --transport http pushpage https://pushpage.link/mcp
```

The first call to a tool that needs an account fails once with `MCP server "pushpage" needs you to sign in again (run /mcp to re-authenticate)`. That's expected: run `/mcp`, pick pushpage, choose Authenticate.

**Which clients have I actually tested?** Only Claude Code and the MCP Inspector are confirmed with browser sign-in so far. For other clients (Claude Desktop, Cursor and friends) the API-key setup is the dependable path: see [Headless / CI setups](docs/mcp.md#headless--ci-setups-with-an-api-key).

<!-- VS Code link decodes to {"name":"pushpage","type":"http","url":"https://pushpage.link/mcp/signin"} (double URL-encoded by the vscode.dev wrapper). Update when /mcp/signin is removed, see CLAUDE.md. -->
**One-click install for VS Code/Cursor** (adds the sign-in URL `/mcp/signin`, deprecated; not yet verified with sign-in): [Add to VS Code](https://vscode.dev/redirect?url=vscode%3Amcp%2Finstall%3F%257B%2522name%2522%253A%2522pushpage%2522%252C%2522type%2522%253A%2522http%2522%252C%2522url%2522%253A%2522https%253A%252F%252Fpushpage.link%252Fmcp%252Fsignin%2522%257D) · Cursor: use the "Add to Cursor" button on the [MCP setup guide](https://pushpage.link/guides/mcp-server-publish-html) (GitHub doesn't render `cursor://` links). Both add `https://pushpage.link/mcp/signin` and ask you to confirm; the dashboard's Account tab shows the same buttons for a self-hosted instance. Claude Code (verified): `claude mcp add --transport http pushpage https://pushpage.link/mcp`.

More on the MCP side:

- **How sign-in works:** [`docs/oauth.md`](docs/oauth.md).
- **Everything else** (tool reference, `mcp-remote` for older clients, troubleshooting): [`docs/mcp.md`](docs/mcp.md).
- **Keeping an API key tidy:** [`docs/mcp.md`](docs/mcp.md#storing-your-api-key).
- **Local stdio server** (no clone, no Docker, no browser sign-in, so it takes an API key): `uvx pushpage-mcp` with `PUSHPAGE_API_KEY` set. PyPI package [`pushpage-mcp`](https://pypi.org/project/pushpage-mcp/), details in [`docs/mcp.md`](docs/mcp.md#run-locally-with-uvx-pypi-package).
- **Step-by-step guides:** [share HTML from Claude Code](https://pushpage.link/guides/share-html-from-claude-code), [MCP server setup](https://pushpage.link/guides/mcp-server-publish-html), [HTML hosting for AI agents](https://pushpage.link/guides/html-hosting-for-ai-agents).

### For the agent itself

- **Skill file:** [`skill/SKILL.md`](skill/SKILL.md) teaches an agent framework like Claude Code how to use pushpage: use the MCP tools when they're there, never ask you for an API key, write good HTML, and hand back the URL.
- **llms.txt:** [`llms.txt`](llms.txt) is served at `https://pushpage.link/llms.txt` (or `{APP_SERVER_URL}/llms.txt`) and follows the [llms.txt convention](https://llmstxt.org). An agent that finds your instance over HTTP can read it and know what to do without any setup.

## Self-hosting

pushpage is open source, and you can run your own. If you'd rather not, the managed instance at **https://pushpage.link** runs this exact same image, just with the `PLANS_ENABLED` and `EMAIL_ENABLED` flags on (subscription plans, sign-up verification and password-reset emails; see [`docs/configuration.md`](docs/configuration.md)).

### What you need

- Docker and Docker Compose
- A `.env` file in the project root with `APP_SERVER_URL` set to the URL where the service will be reachable:

```env
APP_SERVER_URL=http://localhost:8080
```

Everything else has sensible defaults. The full list is in [`docs/configuration.md`](docs/configuration.md).

### Run it

```bash
mkdir -p data/pages data/pgdata
docker compose up -d
```

That pulls the pre-built images and starts everything: the publisher, nginx, PostgreSQL and the MCP server. Check it's alive with `curl http://localhost:8080/api/health`, or open the dashboard at `/dashboard` or the Swagger UI at `/api/swagger-ui/index.html`.

```bash
docker compose down      # stop
docker compose down -v   # stop and wipe all pages and metadata
```

### First run (operators)

On first run, if the database has no users, the service creates an `admin` user and logs its API key **once**:

```
==============================================================
No admin user found — bootstrap admin created.
API Key: pp_abc123...
Copy this key now. It will NOT appear again after restart.
==============================================================
```

Copy it. This key is for you as the operator (administering the instance, scripts, headless setups). Everyone else connects through MCP as described above.

Try it from a script:

```bash
curl -X POST http://localhost:8080/api/pages \
  -H "X-Api-Key: pp_abc123..." \
  -H "Content-Type: application/json" \
  -d '{"html": "<html><head><title>My Report</title></head><body><h1>Hello</h1></body></html>"}'
```

You get `{ "url": "http://localhost:8080/pages/a1b2c3d4.html", "id": "a1b2c3d4" }` back. Open the URL: that's your page. (`POST /api/pages` also works with no key; those are guest pages that expire after 30 minutes.)

**Separate keys for separate agents or users?** Create a dedicated user. Regular users only see their own pages; admins see everything.

```bash
curl -X POST http://localhost:8080/api/admin/users \
  -H "X-Api-Key: pp_abc123..." \
  -H "Content-Type: application/json" \
  -d '{"username": "myagent", "email": "agent@example.com", "admin": false}'
```

The response includes an `api_key`. Save it, it's only shown once.

### Database

PostgreSQL, started for you by `docker-compose.yml` (`postgres:17-alpine`). To use your own instead, set `DB_HOST`, `DB_NAME`, `DB_USER` and `DB_PASSWORD` in `.env`. Reference: [`docs/configuration.md`](docs/configuration.md).

### Security in one paragraph

Published pages are served with `Content-Security-Policy: sandbox` (no `allow-same-origin`), so a page can't touch the dashboard session or call the API on a viewer's behalf. The dashboard can't be framed, login and sign-up are rate-limited per IP, and dot-prefixed paths like `/.env` are never served. Behind a reverse proxy or tunnel, set `TRUSTED_PROXIES` (and `REAL_IP_HEADER`) so rate limits see the real client IP. Details: [`docs/configuration.md`](docs/configuration.md#security-hardening-nginx).

## REST API

Agents should use [MCP](#connect-your-agent). The REST API is for scripts, CI and integrations that don't speak MCP. Everything lives under `/api`, and most calls need an API key (`X-Api-Key: <key>` or `Authorization: Bearer <key>`).

| Method | Path | Auth | What it does |
|--------|------|------|--------------|
| `POST` | `/api/pages` | Optional | Publish HTML (JSON or file upload), returns `{ url, id }` |
| `PUT` | `/api/pages/{id}` | User | Replace a page's HTML (and title), keeping the same id and URL; owner or admin only, expiry restarts |
| `POST` | `/api/oauth/register`, `/api/oauth/token` | None | OAuth for MCP browser sign-in (see `docs/oauth.md`) |
| `GET` | `/api/health` | None | Health check |
| `GET` | `/api/admin/health` | Admin | Runtime stats (version, uptime, page counts, storage, user counts) |
| `GET` | `/api/config` | None | Public frontend config (enabled feature flags) |

The full reference (page management, admin endpoints, schemas) is in the Swagger UI at `/api/swagger-ui/index.html`, and in [`docs/api.md`](docs/api.md).

## Contributing / local development

Build from source and run locally:

```bash
docker compose -f docker-compose.dev.yml up -d --build
```

That starts PostgreSQL (on tmpfs, so nothing persists between restarts) next to the publisher. Project layout and conventions are in [`CLAUDE.md`](CLAUDE.md).
