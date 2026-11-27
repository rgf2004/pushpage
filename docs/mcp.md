# MCP Server

pushpage ships a cloud MCP server that lets any MCP-compatible client (Claude Desktop, Claude Code, Cursor, etc.) publish, update, list, and delete pages without any local install. Agents connect to it over HTTP using a single URL.

**Recommended path:** connect to `{APP_SERVER_URL}/mcp` (managed instance: `https://pushpage.link/mcp`) and sign in with the browser when the client asks. No API key is involved. Order of preference: 1) MCP with browser sign-in, 2) MCP as a guest (no account, "try it"), 3) MCP with an API key (headless/CI, local `uvx` server, clients without browser sign-in). The REST API ([api.md](api.md)) is for scripts and non-MCP integrations.

The MCP server starts automatically with the rest of the stack — no extra configuration is needed beyond the standard `docker compose up -d`.

## Tools

| Tool | Description |
|------|-------------|
| `publish_page(html, title?)` | Publish an HTML page, returns `{ url, id, expires_at }`. `title` is optional — omitted to let the service extract it from the `<title>` tag or fall back to `"Untitled"`. |
| `update_page(id, html, title?)` | Replace the HTML of one of your pages, keeping the same `id` and URL (`PUT /api/pages/{id}`). Overwrites the old content; the expiry restarts. Returns `{ url, id, expires_at }`. Needs an account. |
| `list_pages()` | List published pages scoped to the authenticated user (admins see all). |
| `delete_page(id)` | Delete a page by its ID. |
| `health()` | Check whether the pushpage service is reachable and healthy. |

## Authentication

There are three ways to connect, in order of preference:

**1. Browser sign-in (recommended).** Connect to `/mcp` with just the URL (`claude mcp add --transport http pushpage https://pushpage.link/mcp`). Publishing works immediately as a guest and never prompts, so pages you only publish silently expire after 30 minutes: to sign in, ask your agent to list your pages before publishing pages you want to keep. The first time you use a tool that needs an account (`list_pages`, `update_page`, `delete_page`) the server answers `401` and a client that supports MCP sign-in prompts you to sign in in the browser; one approval grants publish, update, list and delete, and you can revoke it under Account, Connected apps in the dashboard. In Claude Code the first such call fails once with `MCP server "pushpage" needs you to sign in again (run /mcp to re-authenticate)`: run `/mcp`, pick pushpage and choose Authenticate. Only Claude Code and the MCP Inspector are confirmed to work with this flow so far; see [Signing in with the browser](#signing-in-with-the-browser-oauth).

**2. Guest mode (no account, "try it").** Connect with just the URL and no sign-in (`/mcp-guest` never prompts). `publish_page` works and creates an anonymous page that is deleted after 30 minutes (the same limits as `POST /api/pages` without a key, rate-limited per client IP). The result includes `guest_mode: true`, a `message` and a `signup_url`. The message depends on the endpoint: on `/mcp` it says to ask the agent to list pages to trigger the sign-in (Claude Code: `/mcp`, Authenticate); on `/mcp-guest` it says that endpoint never prompts and to re-add the server with `/mcp`; over local stdio it says there is no browser sign-in and an API key in `PUSHPAGE_API_KEY` is required. `list_pages`, `update_page` and `delete_page` need an account, so in guest mode they return a "Sign in required" result with the same message and make no API call. `health` works.

**3. API key (headless, CI, local `uvx`/stdio, clients without browser sign-in).** Each MCP client supplies its own pushpage API key via either header:

- `Authorization: Bearer <key>`
- `X-Api-Key: <key>`

Pages then follow your plan's retention, and `list_pages` / `update_page` / `delete_page` work without any prompt. An invalid or expired key is rejected by the API with `401`; it is never silently downgraded to guest mode. Create a named key in the dashboard's **Account** tab (a separate one per agent); see [Headless / CI setups](#headless--ci-setups-with-an-api-key) below.

### `PUSHPAGE_PUBLIC_URL` vs `PUSHPAGE_URL`

| Variable | Purpose | Default |
|----------|---------|---------|
| `PUSHPAGE_URL` | Where the MCP server sends its API calls (an internal address between containers, e.g. `http://publisher:8080`). | `https://pushpage.link` |
| `PUSHPAGE_PUBLIC_URL` | The address shown to users in guest-mode messages and `signup_url` (`<address>/dashboard`). Must be reachable from a browser. | `https://pushpage.link` |

In the compose files `PUSHPAGE_PUBLIC_URL` is set from `APP_SERVER_URL`, so a self-hosted deployment needs no extra setup. If you run the local `uvx` server against your own instance, set both: `PUSHPAGE_URL` to point at it and `PUSHPAGE_PUBLIC_URL` to its public address, otherwise the sign-up links still point to pushpage.link.

## Guest endpoint

`https://pushpage.link/mcp-guest` is always keyless: any API key or token a client sends to it is ignored, so the session is a guest session (30-minute pages, publish only). Use it for "try it with no account" setups and for clients that cannot do browser sign-in and have no key; use `/mcp` (browser sign-in or a key) for your own pages.

## Signing in with the browser (OAuth)

`https://pushpage.link/mcp` uses lazy authentication: anonymous callers get `initialize`, `tools/list`, `publish_page` and `health` (guest mode); calling `list_pages`, `update_page` or `delete_page` without a credential makes the server answer `401` with a pointer to the OAuth discovery document, so a client that supports the MCP authorization flow opens the browser, you sign in with your pushpage account and allow it, and the call is retried with no API key copied. An API key (`X-Api-Key` or `Authorization: Bearer pp_...`) keeps working with no OAuth challenge. `https://pushpage.link/mcp/signin` (deprecated, to be removed once clients are confirmed on `/mcp`) asks for sign-in on every call. Access is limited to publishing, updating, listing and deleting your pages. See [oauth.md](oauth.md) for the flow and its security model.

### One-click install links

<!-- The Cursor/VS Code links on the guide page contain /mcp/signin in encoded form (base64 / percent-encoding); see below for how to regenerate them. -->

The dashboard (Account, Connected apps) and the [MCP setup guide](https://pushpage.link/guides/mcp-server-publish-html) offer "Add to Cursor" and "Add to VS Code" buttons that add `<origin>/mcp/signin` with no key in the link. Formats, from the vendors' documentation:

| Client | Link | Source |
|--------|------|--------|
| Cursor | `cursor://anysphere.cursor-deeplink/mcp/install?name=pushpage&config=<base64 of {"url":"<origin>/mcp/signin"}>` | https://cursor.com/docs/context/mcp/install-links |
| VS Code | `vscode:mcp/install?<encodeURIComponent of {"name":"pushpage","type":"http","url":"<origin>/mcp/signin"}>` (Insiders: `vscode-insiders:`); web wrapper `https://vscode.dev/redirect?url=<encoded vscode: URL>` | https://code.visualstudio.com/api/extension-guides/ai/mcp |

The dashboard builds them in JS from `MCP_SIGNIN_URL`, so a self-hosted instance links to itself. Static pages hard-code the pushpage.link URL (update them with the `/mcp/signin` flip, see CLAUDE.md). To regenerate the Cursor payload after the URL changes: `node -e 'const U="https://pushpage.link/mcp/signin";console.log(btoa(JSON.stringify({url:U})))'` (decodes to `{"url":"https://pushpage.link/mcp/signin"}`); the VS Code one is `encodeURIComponent(JSON.stringify({name:"pushpage",type:"http",url:U}))`. Claude Code and Claude Desktop have no documented install deep link; Claude Code uses `claude mcp add --transport http pushpage <origin>/mcp/signin`.

## Connecting

The MCP server is available at `{APP_SERVER_URL}/mcp` (proxied by nginx). Replace `http://your-domain` with your actual `APP_SERVER_URL`.

### Claude Code (browser sign-in)

```bash
claude mcp add --transport http pushpage http://your-domain/mcp
```

Publishing works at once as a guest (30-minute pages, no prompt). To sign in, ask your agent to list your pages; when a tool that needs an account answers with `needs you to sign in again (run /mcp to re-authenticate)`, run `/mcp`, pick pushpage and choose Authenticate; after one approval list, update, delete and publish all work. For a setup that never prompts, use `http://your-domain/mcp-guest`.

### Other clients

Any client that supports MCP sign-in (OAuth) can connect to `http://your-domain/mcp` with the URL alone. Only Claude Code and the MCP Inspector are confirmed so far; for Cursor, VS Code, Claude Desktop and others you can try the one-click links above (they add the deprecated `/mcp/signin` and are not yet verified with sign-in), or the API-key configurations below, which are the dependable path.

### Headless / CI setups (with an API key)

Use these for CI, servers, scripts, the local server and clients that cannot do browser sign-in.

**Claude Desktop (`claude_desktop_config.json`):**

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

### Run locally with `uvx` (PyPI package)

The stdio server has no browser sign-in, so it takes an API key from the environment (without one it is a guest). The server is published to PyPI as [`pushpage-mcp`](https://pypi.org/project/pushpage-mcp/). With [uv](https://docs.astral.sh/uv/) installed, MCP clients can launch it over stdio with no clone or Docker:

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

Claude Code: `claude mcp add pushpage -e PUSHPAGE_API_KEY=pp_your_key_here -- uvx pushpage-mcp`. Set `PUSHPAGE_URL` (and `PUSHPAGE_PUBLIC_URL`, for the sign-up links in guest messages) to use a self-hosted instance. `pushpage-mcp --http [--host H] [--port P]` serves streamable-http instead (the Docker image runs `server.py`, which defaults to HTTP on `0.0.0.0:8000`).

Releases: pushing a `v*` tag runs `.github/workflows/pypi.yml`, which stamps the version from the tag and publishes with PyPI trusted publishing. `server.json` describes the package and the hosted endpoint for the official MCP Registry (`mcp-publisher publish`).

### Local stdio mode

For clients that launch MCP servers as local processes (and for registries that start the server to inspect it), `mcp/server.py` also runs over **stdio**. Pass `--stdio` (or set `PUSHPAGE_MCP_TRANSPORT=stdio`); the key then comes from the environment because there are no HTTP headers:

```bash
pip install -r mcp/requirements.txt
PUSHPAGE_API_KEY=pp_your_key_here PUSHPAGE_URL=https://pushpage.link python mcp/server.py --stdio
```

`PUSHPAGE_URL` defaults to `https://pushpage.link`. `AUTHORIZATION` or `Authorization` (`Bearer pp_...`) is accepted as an alternative to `PUSHPAGE_API_KEY`. In the default HTTP mode the key is still read only from each request's headers.

### Publishing a release to the MCP Registry

`server.json` describes the server (hosted endpoint, PyPI package, icons) for the [official MCP Registry](https://registry.modelcontextprotocol.io). Each registry version is published once, so every release needs its own publish. After a release is out, **deploy it first** (the icons in `server.json` are served by the nginx image from `/static/`), then:

```bash
mcp-publisher login github          # once
scripts/publish-registry.sh 1.9.0   # the release version, without the leading v
```

The script refuses to continue unless that version of `pushpage-mcp` is on PyPI and every icon URL in `server.json` answers `200`, stamps the version into a temporary copy (the checked-in `server.json` keeps a placeholder, the PyPI workflow stamps its own copy), validates it, and asks before publishing. Directories such as mcptry read the registry entry, so the icon appears there after the next publish.

### Storing your API key

Only needed for API-key setups (browser sign-in stores nothing on your machine). Avoid pasting the key into config files you might commit or share. Recommended setup:

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

Keys are shown only once, when you create them. Create a separate key for each agent or tool in the dashboard's Account tab, so you can revoke one without affecting the others.

### Cursor and clients not listed

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

### Sign-in prompts and permissions (browser sign-in)

- **"needs you to sign in again (run /mcp to re-authenticate)"** (Claude Code): expected the first time you call `list_pages`, `update_page` or `delete_page` on `/mcp`. Run `/mcp`, pick pushpage, choose Authenticate, finish in the browser, then retry once.
- **Signed in but `update_page` answers 403 about permissions:** the app was connected before the update permission existed. Remove it under Account, Connected apps in the dashboard and connect again (all four permissions are granted together).
- **Signed in again and again:** access tokens last `OAUTH_ACCESS_TOKEN_MINUTES` and clients that support it refresh them for up to `OAUTH_REFRESH_TOKEN_DAYS`; if the app was removed under Connected apps it must be re-authorized.
- **The client shows a plain `401` and no sign-in prompt:** it does not support MCP sign-in. Use `/mcp-guest` (publish only) or an API key.

### `401 Unauthorized` from tools

With an API key: the MCP server forwarded your request to the publisher but the key was rejected. Check that:

1. The key in your MCP client config starts with `pp_` and matches the one shown at bootstrap.
2. The user associated with the key is still active (`PATCH /api/admin/users/{id}/deactivate` marks users inactive).
3. You are not accidentally passing the key as a query parameter — only headers are accepted.

### Transport mismatch

The server speaks **streamable-http** exclusively. If your client defaults to SSE and does not fall back, connections will hang or fail. Pass `--transport http-only` (mcp-remote) or the equivalent option in your client to force the right transport.
