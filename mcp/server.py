import json
import os
import sys

import httpx
import uvicorn
from fastmcp import FastMCP
from fastmcp.server.dependencies import get_http_request
from starlette.responses import Response

_url = os.environ.get("PUSHPAGE_URL", "https://pushpage.link").rstrip("/")

mcp = FastMCP(
    "pushpage",
    instructions=(
        "Publish HTML as shareable pages, list your pages, and delete them by ID. "
        "The id returned by publish_page can be passed directly to delete_page."
    ),
)


# ── Helpers ───────────────────────────────────────────────────────────────────

def _api_key() -> str:
    """API key for the current call.

    HTTP transport: taken from the request headers (each client sends its own).
    stdio transport (no HTTP request): taken from PUSHPAGE_API_KEY, or from
    AUTHORIZATION (``Bearer <key>``), set by the client launching the process.
    """
    try:
        request = get_http_request()
    except RuntimeError:
        return _env_api_key()
    headers = dict(request.headers)
    auth = headers.get("authorization", "")
    key = auth[len("Bearer "):].strip() if auth.lower().startswith("bearer ") else ""
    return key or headers.get("x-api-key", "").strip()


def _env_api_key() -> str:
    key = os.environ.get("PUSHPAGE_API_KEY", "").strip()
    if key:
        return key
    # Env var names are case-sensitive on Linux; some launchers (e.g. Glama) pass "Authorization".
    auth = (os.environ.get("AUTHORIZATION") or os.environ.get("Authorization") or "").strip()
    return auth[len("Bearer "):].strip() if auth.lower().startswith("bearer ") else auth


def _client() -> httpx.Client:
    return httpx.Client(
        base_url=_url,
        headers={"X-Api-Key": _api_key(), "Content-Type": "application/json"},
        timeout=30,
    )


# ── Tools ─────────────────────────────────────────────────────────────────────

@mcp.tool(annotations={"title": "Publish HTML page", "readOnlyHint": False,
                       "destructiveHint": False, "idempotentHint": False,
                       "openWorldHint": False})
def publish_page(html: str, title: str | None = None) -> dict:
    """Publish a self-contained HTML document as a public page and return its shareable URL.

    Use this to share rich output (reports, dashboards, prototypes) as a link instead of
    pasting it into chat. Each call creates a NEW page with a new id; it never updates an
    existing one (delete the old page with delete_page if you are replacing it).
    The page is served publicly at the returned URL, sandboxed (scripts run, but cannot
    access the pushpage dashboard or API), so do not include secrets.

    Args:
        html: Complete HTML document (inline CSS/JS; external resources are fine).
            Maximum size is set by the service (1 MB by default).
        title: Optional display title shown in list_pages. If omitted it is taken from
            the HTML <title> tag, falling back to 'Untitled'.

    Returns:
        {url, id, expires_at} on success: the public URL, the page id (pass it to
        delete_page), and the ISO-8601 time the page is deleted automatically (set by the
        account's retention plan). On failure: {error, status_code, detail}, e.g. 401 for
        a missing or invalid API key, 400 if html is empty, 413 if the HTML is too large, 429 if
        the daily quota or rate limit is exceeded.
    """
    with _client() as client:
        payload = {"html": html}
        if title is not None:
            payload["title"] = title
        r = client.post("/api/pages", json=payload)
        try:
            r.raise_for_status()
        except httpx.HTTPStatusError as e:
            return {"error": "Failed to publish page", "status_code": e.response.status_code, "detail": e.response.text}
        data = r.json()
        return {"url": data["url"], "id": data["id"], "expires_at": data["expires_at"]}


@mcp.tool(annotations={"title": "List my pages", "readOnlyHint": True,
                       "destructiveHint": False, "idempotentHint": True,
                       "openWorldHint": False})
def list_pages() -> list[dict]:
    """List the pages published with the current API key's account, including expiry.

    Read-only. Takes no arguments. Admin accounts see every user's pages.

    Returns:
        A list of page objects, each with id, title, url, created_at and expires_at
        (ISO-8601). Use an id with delete_page. On failure a one-element list
        [{error, status_code, detail}], e.g. status 401 for a missing or invalid key.
    """
    with _client() as client:
        r = client.get("/api/pages")
        try:
            r.raise_for_status()
        except httpx.HTTPStatusError as e:
            return [{"error": "Failed to list pages", "status_code": e.response.status_code, "detail": e.response.text}]
        return r.json()


@mcp.tool(annotations={"title": "Delete a page", "readOnlyHint": False,
                       "destructiveHint": True, "idempotentHint": False,
                       "openWorldHint": False})
def delete_page(id: str) -> dict:
    """Permanently delete one of your published pages, making its URL stop working.

    This cannot be undone. You can only delete your own pages (admin accounts can delete
    any page).

    Args:
        id: The page id returned by publish_page or listed by list_pages (for example
            'a1b2c3d4'), not the full URL.

    Returns:
        {deleted: true, id} on success. On failure {error, status_code, detail}, e.g. 404
        if the id does not exist, 403 if the page is not yours, 401 if the key is invalid.
    """
    with _client() as client:
        r = client.delete(f"/api/pages/{id}")
        try:
            r.raise_for_status()
        except httpx.HTTPStatusError as e:
            return {"error": "Failed to delete page", "status_code": e.response.status_code, "detail": e.response.text}
        return {"deleted": True, "id": id}


@mcp.tool(annotations={"title": "Service health", "readOnlyHint": True,
                       "destructiveHint": False, "idempotentHint": True,
                       "openWorldHint": False})
def health() -> dict:
    """Check whether the pushpage service is reachable and healthy.

    Read-only. Takes no arguments and does not require a valid API key.

    Returns:
        The service status object, e.g. {status: 'UP', version, uptimeSeconds, livePages,
        storage}. On failure {error, status_code, detail}.
    """
    with _client() as client:
        r = client.get("/api/health")
        try:
            r.raise_for_status()
        except httpx.HTTPStatusError as e:
            return {"error": "Service unreachable", "status_code": e.response.status_code, "detail": e.response.text}
        return r.json()


# ── Middleware ─────────────────────────────────────────────────────────────────

class ApiKeyMiddleware:
    """Raw ASGI middleware — rejects requests that carry no API key."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        raw_headers = {k.lower().decode(): v.decode() for k, v in scope.get("headers", [])}
        auth = raw_headers.get("authorization", "")
        key = auth[len("Bearer "):].strip() if auth.lower().startswith("bearer ") else ""
        if not key:
            key = raw_headers.get("x-api-key", "").strip()

        if not key:
            body = json.dumps(
                {"error": "Missing API key. Set Authorization: Bearer <key> in your MCP client config."},
                ensure_ascii=False,
            ).encode()
            await Response(body, status_code=401, media_type="application/json")(scope, receive, send)
            return

        await self.app(scope, receive, send)


# ── Entry point ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    if "--stdio" in sys.argv[1:] or os.environ.get("PUSHPAGE_MCP_TRANSPORT", "http").lower() == "stdio":
        # Local/stdio mode: launched by an MCP client; the key comes from the environment.
        mcp.run(transport="stdio")
        raise SystemExit(0)
    app = ApiKeyMiddleware(mcp.http_app(path="/mcp", transport="streamable-http"))
    uvicorn.run(app, host="0.0.0.0", port=8000)
