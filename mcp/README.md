# pushpage MCP server

MCP server for [pushpage](https://pushpage.link), an HTML publishing service for AI agents. An agent sends HTML and gets a shareable URL back, instead of pasting long output into chat.

<!-- mcp-name: io.github.rgf2004/pushpage -->
mcp-name: io.github.rgf2004/pushpage

**Tools:** `publish_page`, `list_pages`, `delete_page`, `health`.

## Run it locally (stdio)

No key is needed to try it: without one the server runs in **guest mode** (`publish_page` works, pages expire after 30 minutes, and the result tells you to sign up or sign in). For permanent pages, `list_pages` and `delete_page`, get an API key from the Account tab of https://pushpage.link/dashboard and add this to your MCP client:

```json
{
  "mcpServers": {
    "pushpage": {
      "command": "uvx",
      "args": ["pushpage-mcp"],
      "env": { "PUSHPAGE_API_KEY": "pp_your_key_here" }
    }
  }
}
```

Claude Code:

```bash
claude mcp add pushpage -e PUSHPAGE_API_KEY=pp_your_key_here -- uvx pushpage-mcp
```

Requires [uv](https://docs.astral.sh/uv/). Set `PUSHPAGE_URL` to use a self-hosted instance (default `https://pushpage.link`).

## No install: hosted endpoint

`https://pushpage.link/mcp` (streamable-http). Try it in guest mode with one command and no key:

```bash
claude mcp add --transport http pushpage https://pushpage.link/mcp
```

With an account, send the key as `X-Api-Key` or `Authorization: Bearer`:

```bash
claude mcp add --transport http pushpage https://pushpage.link/mcp --header "Authorization: Bearer pp_your_key_here"
```

## Options

`pushpage-mcp --http [--host H] [--port P]` serves streamable-http at `/mcp` instead of stdio. In that mode each client supplies its own key in the request headers, or none for guest mode.

Source, docs and self-hosting: https://github.com/rgf2004/pushpage (MIT).
