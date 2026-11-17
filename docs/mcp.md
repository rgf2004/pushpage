# MCP Server

pushpage ships a cloud MCP server that lets any MCP-compatible client (Claude Desktop, Claude Code, Cursor, etc.) publish, list, and delete pages without any local install. Agents connect to it over HTTP using a single URL.

The MCP server starts automatically with the rest of the stack — no extra configuration is needed beyond the standard `docker compose up -d`.

## Tools

| Tool | Description |
|------|-------------|
| `publish_page(html, title?)` | Publish an HTML page, returns `{ url, id, expires_at }`. `title` is optional — omitted to let the service extract it from the `<title>` tag or fall back to `"Untitled"`. |
| `list_pages()` | List published pages scoped to the authenticated user (admins see all). |
| `delete_page(id)` | Delete a page by its ID. |
| `health()` | Check whether the pushpage service is reachable and healthy. |

## Authentication

Each MCP client supplies its own pushpage API key. The server accepts it via either header:

- `Authorization: Bearer <key>`
- `X-Api-Key: <key>`

Requests with no key are rejected with `401` before they reach any tool.

## Connecting

The MCP server is available at `{APP_SERVER_URL}/mcp` (proxied by nginx). Replace `http://your-domain` with your actual `APP_SERVER_URL`.

### Claude Desktop (`claude_desktop_config.json`)

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

### Claude Code CLI

```bash
claude mcp add --transport http pushpage http://your-domain/mcp \
  --header "Authorization: Bearer pp_your_key_here"
```

### Local stdio mode

For clients that launch MCP servers as local processes (and for registries that start the server to inspect it), `mcp/server.py` also runs over **stdio**. Pass `--stdio` (or set `PUSHPAGE_MCP_TRANSPORT=stdio`); the key then comes from the environment because there are no HTTP headers:

```bash
pip install -r mcp/requirements.txt
PUSHPAGE_API_KEY=pp_your_key_here PUSHPAGE_URL=https://pushpage.link python mcp/server.py --stdio
```

`PUSHPAGE_URL` defaults to `https://pushpage.link`. `AUTHORIZATION` or `Authorization` (`Bearer pp_...`) is accepted as an alternative to `PUSHPAGE_API_KEY`. In the default HTTP mode the key is still read only from each request's headers.

### Storing your API key

Avoid pasting the key into config files you might commit or share. Recommended setup:

```bash
mkdir -p ~/.config/pushpage
umask 077 && printf '%s' 'pp_your_key_here' > ~/.config/pushpage/credentials
chmod 600 ~/.config/pushpage/credentials   # owner read/write only
```

This is the file the agent skill and the curl examples read. For MCP clients that expand environment variables, keep the key out of the config:

```bash
# in ~/.zshrc / ~/.bashrc
export PUSHPAGE_API_KEY=$(cat ~/.config/pushpage/credentials)
```

- **Claude Code** (`.mcp.json`): `"headers": { "X-Api-Key": "${PUSHPAGE_API_KEY}" }`
- **Cursor** (`mcp.json`): `"headers": { "X-Api-Key": "${env:PUSHPAGE_API_KEY}" }`
- **`mcp-remote`**: put `"env": { "PUSHPAGE_API_KEY": "pp_..." }` next to `args` and use `"--header", "X-Api-Key:${PUSHPAGE_API_KEY}"`.
- **Claude Desktop** does not expand variables in `headers`; the key must be written into `claude_desktop_config.json` (or use `mcp-remote` above). Keep that file private and never commit it.

Keys are shown only once. Rotating a key in the dashboard invalidates the old one, so update the file after rotating.

### Cursor / other clients

Refer to your client's documentation for remote HTTP MCP configuration. The server uses the **streamable-http** transport and does not require any SSE negotiation.

---

## Troubleshooting

### Native `url` + `headers` config not working

Claude Desktop's native remote MCP support (the `url` key in config) has two known limitations:

- **Requires HTTPS.** Plain `http://` URLs may be silently rejected. If your pushpage instance is HTTP-only (e.g. a local or homelab setup), the native format will not work.
- **`headers` field support varies by version.** Older Claude Desktop builds do not forward custom headers, so the API key is never sent and every request returns `401`.

If you run into either of these, use `mcp-remote` as a proxy instead (see below).

### Alternative: `mcp-remote` proxy

`mcp-remote` runs as a local stdio bridge. Claude talks to it over stdin/stdout (the well-supported path), and `mcp-remote` handles the HTTP connection to your server. This sidesteps the HTTPS requirement and the `headers` field limitation entirely.

Install it once globally:

```bash
npm install -g mcp-remote
```

Then use this config in `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "pushpage": {
      "command": "npx",
      "args": [
        "mcp-remote",
        "http://your-domain/mcp",
        "--transport", "http-only",
        "--header", "Authorization:Bearer pp_your_key_here"
      ]
    }
  }
}
```

> **Note:** The `Authorization:Bearer` value has no space after the colon — this is how `mcp-remote` expects the `--header` argument.

If your pushpage instance uses a self-signed TLS certificate, add `"NODE_TLS_REJECT_UNAUTHORIZED": "0"` to the `env` block:

```json
{
  "mcpServers": {
    "pushpage": {
      "command": "npx",
      "args": [
        "mcp-remote",
        "https://your-domain/mcp",
        "--transport", "http-only",
        "--header", "Authorization:Bearer pp_your_key_here"
      ],
      "env": {
        "NODE_TLS_REJECT_UNAUTHORIZED": "0"
      }
    }
  }
}
```

### Verifying the MCP server is up

```bash
curl http://your-domain/mcp/health
# or via the pushpage health endpoint directly:
curl http://your-domain/api/health
```

If the MCP container is not responding, check its logs:

```bash
docker compose logs mcp
```

### `401 Unauthorized` from tools

The MCP server forwarded your request to the publisher but the API key was rejected. Check that:

1. The key in your MCP client config starts with `pp_` and matches the one shown at bootstrap.
2. The user associated with the key is still active (`PATCH /api/admin/users/{id}/deactivate` marks users inactive).
3. You are not accidentally passing the key as a query parameter — only headers are accepted.

### Transport mismatch

The server speaks **streamable-http** exclusively. If your client defaults to SSE and does not fall back, connections will hang or fail. Pass `--transport http-only` (mcp-remote) or the equivalent option in your client to force the right transport.
