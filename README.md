# pushpage

A self-hosted HTML page publishing service. AI agents POST HTML content and get a shareable URL back instead of dumping text into chat.

## Why this project exists

AI agents are increasingly capable of generating rich, complex outputs, but they have been missing a key piece: a way to *publish* and *share* that output. pushpage is that missing piece. Instead of dumping walls of text or Markdown into chat, an agent can POST HTML to pushpage and hand back a clean, shareable URL.

This project was directly inspired by [The Unreasonable Effectiveness of HTML](https://claude.com/blog/using-claude-code-the-unreasonable-effectiveness-of-html) by Thariq Shihipar (Claude Code team). The core insight: HTML is a dramatically more effective medium for AI-generated output than Markdown.

**Why HTML beats Markdown for human consumption:**
- Markdown is a plaintext approximation. Large documents become walls of text that people skim or abandon. HTML lets agents express the same information with visual hierarchy, tabs, tables, and illustrations that readers actually engage with.
- HTML is interactive. Sliders, toggleable sections, copy buttons, real-time previews. None of that is possible in Markdown. A spec or report that lets the reader interact with it closes the feedback loop far faster.
- HTML is shareable. A clickable link gets reviewed; a `.md` attachment does not. pushpage turns every agent output into a URL a human can actually open.

pushpage gives AI agents a publish endpoint so that the richer, more effective HTML output format is no longer a dead end. It becomes something you can share with a single link.

## API

All endpoints are under `/api` (Spring Boot context path). Examples below use `{APP_SERVER_URL}`, which defaults to `http://localhost:8080`.

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/publish` | Publish HTML, returns `{ url, id }` |
| `GET` | `/api/pages` | List all published pages |
| `DELETE` | `/api/pages/{id}` | Delete a page |
| `GET` | `/api/health` | Health check |

Swagger UI: `{APP_SERVER_URL}/api/swagger-ui/index.html`

### Publish a page

```bash
curl -X POST {APP_SERVER_URL}/api/publish \
  -H "Content-Type: application/json" \
  -d '{"title": "My Report", "html": "<h1>Hello</h1><p>Some content here.</p>"}'
```

Response:

```json
{
  "url": "{APP_SERVER_URL}/pages/a1b2c3d4.html",
  "id": "a1b2c3d4"
}
```

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

To stop the stack:

```bash
docker compose down
```

To wipe all published pages and metadata:

```bash
docker compose down -v
```

## Agent Skill File

The `skill/` directory contains a skill file (`skill/SKILL.md`) for use with AI agent frameworks such as Claude Code. Loading this skill tells the agent how to interact with pushpage: when to publish, how to structure the HTML, and how to return the resulting URL to the user.

Once the skill is loaded, the agent will automatically publish rich HTML output to pushpage instead of dumping content into chat, and return a clickable link.

See [`skill/SKILL.md`](skill/SKILL.md) for the full skill definition and usage examples.

## Contributing / Local Development

To build from source and run locally:

```bash
docker compose -f docker-compose.dev.yml up -d --build
```
