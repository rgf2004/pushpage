import contextvars
import os
import sys

import httpx
import uvicorn
from fastmcp import FastMCP
from starlette.responses import JSONResponse

_url = os.environ.get("PUSHPAGE_URL", "").rstrip("/")

if not _url:
    print("ERROR: missing required environment variable: PUSHPAGE_URL", file=sys.stderr)
    sys.exit(1)

mcp = FastMCP(
    "pushpage",
    instructions=(
        "This MCP server lets you publish, list, and delete HTML pages using the pushpage service. "
        "Use publish_page to POST HTML and receive a shareable URL, list_pages to see your published pages, "
        "delete_page to remove a page by ID, and health to verify the service is reachable."
    ),
)

# Per-request API key extracted from the incoming Authorization / X-Api-Key header.
_request_api_key: contextvars.ContextVar[str] = contextvars.ContextVar("request_api_key", default="")


class ApiKeyMiddleware:
    """Raw ASGI middleware — extracts the client's API key per request without buffering responses."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        headers = {k.lower(): v for k, v in scope.get("headers", [])}
        auth = headers.get(b"authorization", b"").decode()
        key = auth[len("Bearer "):].strip() if auth.startswith("Bearer ") else ""
        if not key:
            key = headers.get(b"x-api-key", b"").decode().strip()

        if not key:
            response = JSONResponse(
                {"error": "Missing API key. Set Authorization: Bearer <key> in your MCP client config."},
                status_code=401,
            )
            await response(scope, receive, send)
            return

        token = _request_api_key.set(key)
        try:
            await self.app(scope, receive, send)
        finally:
            _request_api_key.reset(token)


def _client() -> httpx.Client:
    key = _request_api_key.get()
    return httpx.Client(
        base_url=_url,
        headers={"X-Api-Key": key, "Content-Type": "application/json"},
        timeout=30,
    )


@mcp.tool()
def publish_page(html: str, title: str | None = None) -> dict:
    """Publish an HTML page and return its shareable URL.

    title is optional — omit it to let the service extract it from the <title> tag,
    or fall back to 'Untitled'.
    """
    with _client() as client:
        payload = {"html": html}
        if title is not None:
            payload["title"] = title
        r = client.post("/api/pages", json=payload)
        r.raise_for_status()
        data = r.json()
        return {"url": data["url"], "id": data["id"]}


@mcp.tool()
def list_pages() -> list:
    """List published pages visible to the authenticated user."""
    with _client() as client:
        r = client.get("/api/pages")
        r.raise_for_status()
        return r.json()


@mcp.tool()
def delete_page(id: str) -> dict:
    """Delete a published page by its ID."""
    with _client() as client:
        r = client.delete(f"/api/pages/{id}")
        r.raise_for_status()
        return {"deleted": True, "id": id}


@mcp.tool()
def health() -> dict:
    """Check whether the pushpage service is reachable and healthy."""
    with _client() as client:
        r = client.get("/api/health")
        r.raise_for_status()
        return r.json()


if __name__ == "__main__":
    app = ApiKeyMiddleware(mcp.http_app(path="/mcp", transport="streamable-http"))
    uvicorn.run(app, host="0.0.0.0", port=8000)
