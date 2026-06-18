import os
import sys
import httpx
from fastmcp import FastMCP

_url = os.environ.get("PUSHPAGE_URL", "").rstrip("/")
_key = os.environ.get("PUSHPAGE_API_KEY", "")

if not _url or not _key:
    missing = [v for v, val in [("PUSHPAGE_URL", _url), ("PUSHPAGE_API_KEY", _key)] if not val]
    print(f"ERROR: missing required environment variables: {', '.join(missing)}", file=sys.stderr)
    sys.exit(1)

mcp = FastMCP("pushpage")

_headers = {"X-Api-Key": _key, "Content-Type": "application/json"}


def _client() -> httpx.Client:
    return httpx.Client(base_url=_url, headers=_headers, timeout=30)


@mcp.tool()
def publish_page(title: str, html: str) -> dict:
    """Publish an HTML page and return its shareable URL."""
    with _client() as client:
        r = client.post("/api/pages", json={"title": title, "html": html})
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
    mcp.run(transport="streamable-http", host="0.0.0.0", port=8000, path="/mcp")
