import json
import os
import sys

import httpx
import uvicorn
from fastmcp import FastMCP
from fastmcp.server.dependencies import get_http_request
from starlette.responses import Response

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


def _extract_api_key_from_headers(headers: dict) -> str:
    auth = headers.get("authorization", "")
    key = auth[len("Bearer "):].strip() if auth.lower().startswith("bearer ") else ""
    if not key:
        key = headers.get("x-api-key", "").strip()
    return key


class ApiKeyMiddleware:
    """Raw ASGI middleware — rejects requests that carry no API key."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        raw_headers = {k.lower().decode(): v.decode() for k, v in scope.get("headers", [])}
        key = _extract_api_key_from_headers(raw_headers)

        if not key:
            body = json.dumps(
                {"error": "Missing API key. Set Authorization: Bearer <key> in your MCP client config."},
                ensure_ascii=False,
            ).encode()
            response = Response(body, status_code=401, media_type="application/json")
            await response(scope, receive, send)
            return

        await self.app(scope, receive, send)


def _api_key() -> str:
    """Extract the pushpage API key from the current HTTP request."""
    request = get_http_request()
    return _extract_api_key_from_headers(dict(request.headers))


def _client() -> httpx.Client:
    return httpx.Client(
        base_url=_url,
        headers={"X-Api-Key": _api_key(), "Content-Type": "application/json"},
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
        try:
            r.raise_for_status()
        except httpx.HTTPStatusError as e:
            return {"error": "Failed to publish page", "status_code": e.response.status_code, "detail": e.response.text}
        data = r.json()
        return {"url": data["url"], "id": data["id"]}


@mcp.tool()
def list_pages() -> list[dict]:
    """List published pages visible to the authenticated user."""
    with _client() as client:
        r = client.get("/api/pages")
        try:
            r.raise_for_status()
        except httpx.HTTPStatusError as e:
            return [{"error": "Failed to list pages", "status_code": e.response.status_code, "detail": e.response.text}]
        return r.json()


@mcp.tool()
def delete_page(id: str) -> dict:
    """Delete a published page by its ID."""
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


if __name__ == "__main__":
    app = ApiKeyMiddleware(mcp.http_app(path="/mcp", transport="streamable-http"))
    uvicorn.run(app, host="0.0.0.0", port=8000)
