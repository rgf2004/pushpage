import json
import os

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
    request = get_http_request()
    headers = dict(request.headers)
    auth = headers.get("authorization", "")
    key = auth[len("Bearer "):].strip() if auth.lower().startswith("bearer ") else ""
    return key or headers.get("x-api-key", "").strip()


def _client() -> httpx.Client:
    return httpx.Client(
        base_url=_url,
        headers={"X-Api-Key": _api_key(), "Content-Type": "application/json"},
        timeout=30,
    )


# ── Tools ─────────────────────────────────────────────────────────────────────

@mcp.tool()
def publish_page(html: str, title: str | None = None) -> dict:
    """Publish an HTML page and return its shareable URL.

    Returns {url, id, expires_at}. title is optional — when omitted it is
    extracted from the HTML <title> tag, or falls back to 'Untitled'.
    expires_at is an ISO-8601 timestamp indicating when the page will be
    automatically deleted.
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


@mcp.tool()
def list_pages() -> list[dict]:
    """List all pages published by the current user.

    Returns a list of page objects, each with id, title, url, and created_at.
    """
    with _client() as client:
        r = client.get("/api/pages")
        try:
            r.raise_for_status()
        except httpx.HTTPStatusError as e:
            return [{"error": "Failed to list pages", "status_code": e.response.status_code, "detail": e.response.text}]
        return r.json()


@mcp.tool()
def delete_page(id: str) -> dict:
    """Delete a page by its id.

    Use the id from publish_page or list_pages.
    """
    with _client() as client:
        r = client.delete(f"/api/pages/{id}")
        try:
            r.raise_for_status()
        except httpx.HTTPStatusError as e:
            return {"error": "Failed to delete page", "status_code": e.response.status_code, "detail": e.response.text}
        return {"deleted": True, "id": id}


@mcp.tool()
def health() -> dict:
    """Check whether the pushpage service is reachable and healthy."""
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
    app = ApiKeyMiddleware(mcp.http_app(path="/mcp", transport="streamable-http"))
    uvicorn.run(app, host="0.0.0.0", port=8000)
