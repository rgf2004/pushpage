# OAuth sign-in for the hosted MCP endpoint

MCP clients that implement the [MCP authorization spec](https://modelcontextprotocol.io/specification/draft/basic/authorization) (OAuth 2.1 with PKCE and dynamic client registration) can connect to pushpage with a browser sign-in instead of a copied API key.

| Endpoint | Behavior |
|----------|----------|
| `https://pushpage.link/mcp` | Lazy authentication. Anonymous callers can initialize, list tools and use `publish_page` and `health` (guest mode). A call to `list_pages`, `update_page` or `delete_page` without a credential gets `401` with the discovery pointer, which makes the client open the browser sign-in and retry. `X-Api-Key` / `Authorization: Bearer pp_...` work as before, with no OAuth challenge. |
| `https://pushpage.link/mcp-guest` | Always a guest, whatever the client sends: any key or token is dropped. The URL to give people for trying it with no account. |
| `https://pushpage.link/mcp/signin` | The same MCP server, but only for signed-in callers. Without a valid credential it answers `401` with a `WWW-Authenticate` header pointing at the discovery document, which is what makes the client open the browser. |

`/mcp/signin` is deprecated: it stays unchanged until real clients are confirmed with lazy authentication on `/mcp`, then it is removed.

## Lazy authentication on `/mcp`

The gateway reads the JSON-RPC body of each `POST /mcp` (single message or batch) and decides per call; the body is then replayed unchanged to the MCP app. `GET` (event stream) and `DELETE` (end session) are not inspected. Bodies over 10 MiB are passed on without inspection.

| Credential | Public call (`initialize`, `tools/list`, `publish_page`, `health`, ...) | Protected call (`list_pages` needs `pages:read`, `delete_page` `pages:delete`, `update_page` `pages:update`) |
|---|---|---|
| none | passes (guest) | `401` challenge |
| `pp_` API key | passes | passes (the API checks the key; an invalid one is a tool error) |
| OAuth token, valid, has the scopes | passes | passes |
| OAuth token, valid, lacks a scope | passes, except `publish_page` without `pages:publish`: `403` `insufficient_scope` | `403` `insufficient_scope` |
| OAuth token, invalid or expired | `401` | `401` |
| OAuth token, API unreachable | `503` | `503` |

Challenges (`resource_metadata` is `<public url>/.well-known/oauth-protected-resource/mcp`). Every challenge asks for the **full** scope set, `pages:publish pages:update pages:read pages:delete`, never just the scope of the tool that was called: one consent then covers all account tools, and a client that follows the challenge cannot end up with a token that lists pages but cannot publish:

```
401  WWW-Authenticate: Bearer error="invalid_token", error_description="Authentication required for this tool", resource_metadata="...", scope="pages:publish pages:update pages:read pages:delete"
401  WWW-Authenticate: Bearer error="invalid_token", error_description="The access token is invalid or expired", resource_metadata="...", scope="pages:publish pages:update pages:read pages:delete"
403  WWW-Authenticate: Bearer error="insufficient_scope", resource_metadata="...", scope="pages:publish pages:update pages:read pages:delete"
```

Known behaviours:

- A client that does not support MCP OAuth gets a plain `401` on `list_pages`, `update_page` and `delete_page` on `/mcp`. Such clients should use `/mcp-guest` (never prompts) or an API key instead.
- `publish_page` stays public for anonymous callers and `pp_` keys, but a valid OAuth token whose scope lacks `pages:publish` gets the same `403 insufficient_scope` challenge (the client re-authorizes) instead of an API tool error.

An OAuth token (a JWT) is validated against the API on every POST, with no cache, so removing a connected app takes effect at once. The `scope` claim is read from the token only after the API accepted it; a token without one is a dashboard session and is unrestricted. The protected tools and scopes live in `_PROTECTED_TOOL_SCOPES` in `mcp/pushpage_mcp/server.py`.

## Flow

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant C as MCP client<br/>(Claude Code, Cursor...)
    participant G as /mcp/signin<br/>(MCP gateway)
    participant B as Browser<br/>(/oauth/authorize)
    participant P as Publisher<br/>(OAuth server + API)

    Note over C,G: First connection, no token yet
    C->>G: initialize
    G-->>C: 401 + WWW-Authenticate: resource_metadata=...
    C->>P: GET /.well-known/oauth-protected-resource/mcp/signin
    P-->>C: resource + authorization server
    C->>P: GET /.well-known/oauth-authorization-server
    P-->>C: authorize, token and register endpoints, PKCE S256
    C->>P: POST /api/oauth/register (redirect URI)
    P-->>C: client_id

    Note over U,P: Sign-in in the browser
    C->>B: open /oauth/authorize (client_id, code_challenge, state)
    B->>P: GET /api/oauth/client (is this client and redirect URI registered?)
    P-->>B: client name
    U->>B: email + password
    B->>P: POST /api/auth/login
    P-->>B: session JWT
    U->>B: Allow
    B->>P: POST /api/oauth/authorize (session JWT)
    P-->>B: redirect_to = redirect URI + one-time code
    B->>C: redirect back with code + state

    Note over C,P: Tokens
    C->>P: POST /api/oauth/token (code + code_verifier)
    P-->>C: access token (60 min, scoped) + refresh token (30 days)

    Note over C,P: Using it
    C->>G: initialize / tools/call (Authorization: Bearer access token)
    G->>P: GET /api/me (is the token valid and the account active?)
    P-->>G: 200
    G->>P: POST /api/pages, PUT /api/pages/id, GET /api/pages, DELETE /api/pages/id
    P-->>G: result
    G-->>C: tool result

    Note over C,P: Later, when the access token expires
    C->>P: POST /api/oauth/token (refresh_token)
    P-->>C: new access token + new refresh token (old one is dead)
```

**Where each URL fits:** `/mcp-guest` (always keyless) skips all of this. `/mcp` starts the flow above only when a protected tool is called without a credential (step 1 is then that `401`, with `/.well-known/oauth-protected-resource/mcp`); `/mcp/signin` starts it on any call (deprecated). An OAuth token can only publish, update, list and delete pages; admin, API-key management and issuing more tokens return `403`.

The same flow in words:

1. The client calls `/mcp/signin`, gets `401` with `resource_metadata="…/.well-known/oauth-protected-resource/mcp/signin"`.
2. It reads the protected-resource metadata, then the authorization-server metadata at `/.well-known/oauth-authorization-server`.
3. It registers itself at `POST /api/oauth/register` (RFC 7591) and receives a `client_id`.
4. It opens `/oauth/authorize?...&code_challenge=…&code_challenge_method=S256` in the browser. The page checks the client and redirect URI, signs the user in with their existing email and password (or reuses the dashboard session), and asks them to allow the app.
5. The browser returns to the client's redirect URI with a one-time `code`; the client exchanges it at `POST /api/oauth/token` (with the PKCE `code_verifier`) for an access token and a refresh token.
6. The client calls `/mcp/signin` with `Authorization: Bearer <access token>`.

## Design

- **The publisher is the authorization server.** Identity is the existing email/password account; there is no separate user store. Self-hosted instances get it with no extra service: the issuer is `APP_SERVER_URL`.
- **Public clients only**, no client secret. PKCE with `S256` is required. Redirect URIs must match a registered one exactly; registration accepts `https`, `http` on localhost/127.0.0.1/[::1], and custom app schemes, never `javascript:`/`data:`/`file:` or a fragment.
- **Access tokens** are the same signed JWTs as dashboard sessions, with a `scope` claim and a short life (`OAUTH_ACCESS_TOKEN_MINUTES`, default 60). **Refresh tokens** are random, stored hashed, rotate on every use (the old one stops working) and last `OAUTH_REFRESH_TOKEN_DAYS` (default 30). Authorization codes live 2 minutes and work once.
- **Scopes:** `pages:publish` (`POST /api/pages`), `pages:update` (`PUT /api/pages/{id}`, replacing the content of an existing page), `pages:read` (`GET /api/pages`), `pages:delete` (`DELETE /api/pages/{id}`). A client that asks for none gets all four. Grants made before `pages:update` existed keep exactly the scopes they were given: they keep working but cannot update pages until the app is removed under Connected apps and connected again. A token can also read `GET /api/me`. **Everything else is refused with `403`**: admin endpoints, API key management and `/api/oauth/authorize`, so a leaked token cannot mint keys or more tokens.
- **Grants and revocation:** each app a user allows gets one *grant* (re-authorizing the same app reuses it, so reconnecting does not pile up entries). Every access token names its grant, and the API checks that it still exists. The user sees their grants under **Account → Connected apps** in the dashboard and can **Remove** one: it loses access **immediately** (its access tokens stop working even though they have not expired, and its refresh tokens are deleted), other apps and API keys are untouched, and the app can ask to be connected again. A password change or reset also invalidates every access and refresh token, and so does deactivating the account.
- **API keys and guest mode are unaffected.** The MCP server presents `pp_` keys in `X-Api-Key` and OAuth tokens (JWTs) in `Authorization` to the API.

## Endpoints

All under `/api` except the discovery documents and the consent page, which nginx serves at the site root.

| Method | Path | Auth |
|--------|------|------|
| `GET` | `/.well-known/oauth-protected-resource/mcp` (→ `/api/oauth/protected-resource/mcp`), `resource` = `<base>/mcp` | none |
| `GET` | `/.well-known/oauth-protected-resource/mcp/signin` (→ `/api/oauth/protected-resource`), `resource` = `<base>/mcp/signin` | none |
| `GET` | `/.well-known/oauth-authorization-server` (→ `/api/oauth/metadata`) | none |
| `POST` | `/api/oauth/register` | none, rate-limited per IP (`RATE_LIMIT_SIGNUP_RPM`) |
| `GET` | `/oauth/authorize` | the consent page (static HTML, `noindex`) |
| `GET` | `/api/oauth/client?client_id&redirect_uri` | none; returns the client name for the consent page |
| `POST` | `/api/oauth/authorize` | signed-in session (JWT); an API key or OAuth token gets `403` |
| `GET` | `/api/me/connected-apps` | signed-in session (JWT); an API key or OAuth token gets `403` |
| `DELETE` | `/api/me/connected-apps/{id}` | signed-in session (JWT); `404` if the grant is not yours |
| `POST` | `/api/oauth/token` | none (`authorization_code` and `refresh_token` grants, form-encoded) |

Errors from the token and registration endpoints follow RFC 6749 / 7591: `{"error": "...", "error_description": "..."}`.

## Storage

Migration `V1004__oauth_authorization_server.sql` adds `oauth_clients`, `oauth_grants`, `oauth_codes` and `oauth_refresh_tokens` (codes and refresh tokens as SHA-256 hashes). Expired codes and refresh tokens are removed hourly. Registered clients are kept (they are small, and clients cache their `client_id`).
