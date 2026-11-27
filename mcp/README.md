# pushpage MCP server

[![rgf2004/pushpage MCP server](https://glama.ai/mcp/servers/rgf2004/pushpage/badges/score.svg)](https://glama.ai/mcp/servers/rgf2004/pushpage)
[![mcptry](https://mcptry.com/badge/io.github.rgf2004/pushpage)](https://mcptry.com/servers/io.github.rgf2004/pushpage)

MCP server for [pushpage](https://pushpage.link), an HTML publishing service for AI agents. An agent sends HTML and gets a shareable URL back, instead of pasting long output into chat.

<!-- mcp-name: io.github.rgf2004/pushpage -->
mcp-name: io.github.rgf2004/pushpage

**Tools:** `publish_page`, `update_page`, `list_pages`, `delete_page`, `health`.

## Recommended: hosted endpoint with browser sign-in

Connect your agent to `https://pushpage.link/mcp`. In Claude Code:

```bash
claude mcp add --transport http pushpage https://pushpage.link/mcp
```

No account or key is needed to start: `publish_page` and `health` work as a guest (pages expire after 30 minutes). The first time a tool that needs an account is called (`list_pages`, `update_page`, `delete_page`), the server answers `401` and a client that supports MCP sign-in prompts you to sign in in the browser. In Claude Code the call fails once with `needs you to sign in again (run /mcp to re-authenticate)`: run `/mcp`, pick pushpage and choose Authenticate. One approval grants publish, update, list and delete, and you can revoke it any time under Account, Connected apps in the [dashboard](https://pushpage.link/dashboard). No API key to copy. Claude Code and the MCP Inspector are confirmed to work with the sign-in flow.

To try it with no account and never be prompted (also for clients without sign-in support), use the keyless URL:

```bash
claude mcp add --transport http pushpage https://pushpage.link/mcp-guest
```

## Headless, CI and clients without browser sign-in: API key

Create a named key in the Account tab of https://pushpage.link/dashboard and send it as `X-Api-Key` or `Authorization: Bearer`:

```bash
claude mcp add --transport http pushpage https://pushpage.link/mcp --header "Authorization: Bearer pp_your_key_here"
```

## Run it locally (stdio) with uvx

The local stdio server has no browser sign-in, so for an account it takes an API key from the environment. Without one it runs in **guest mode** (`publish_page` works, pages expire after 30 minutes, and the result explains how to sign in). Add this to your MCP client:

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

Requires [uv](https://docs.astral.sh/uv/). To use a self-hosted instance, set `PUSHPAGE_URL` to its API address and `PUSHPAGE_PUBLIC_URL` to its public address (used for the sign-in links in guest mode). Both default to `https://pushpage.link`.

## Options

`pushpage-mcp --http [--host H] [--port P]` serves streamable-http at `/mcp` instead of stdio. In that mode each client supplies its own key in the request headers, or none for guest mode.

Source, docs and self-hosting: https://github.com/rgf2004/pushpage (MIT).
