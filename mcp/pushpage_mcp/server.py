import argparse
import os

import httpx
import uvicorn
from fastmcp import FastMCP
from fastmcp.server.dependencies import get_http_request

_url = os.environ.get("PUSHPAGE_URL", "https://pushpage.link").rstrip("/")
# Public address users should open to sign up / sign in (differs from _url when the MCP
# container talks to the publisher over an internal address).
_public_url = os.environ.get("PUSHPAGE_PUBLIC_URL", "https://pushpage.link").rstrip("/")

mcp = FastMCP(
    "pushpage",
    instructions=(
        "Publish HTML as shareable pages, list your pages, and delete them by ID. "
        "The id returned by publish_page can be passed directly to delete_page. "
        "No API key is needed to try it: without one, publish_page works in guest mode "
        "(pages expire after 30 minutes). Sign up or sign in to the pushpage dashboard to "
        "get an API key for permanent accounts, list_pages and delete_page."
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


def _client_ip_headers() -> dict:
    """Forward the real client IP so the publisher rate-limits per client, not per MCP container.

    nginx sets X-Real-IP from the connecting address, so it is trustworthy here. Not available
    over stdio (the caller is local), where the publisher sees this machine's own address.
    """
    try:
        request = get_http_request()
    except RuntimeError:
        return {}
    ip = request.headers.get("x-real-ip") or (request.client.host if request.client else "")
    return {"X-Real-IP": ip, "X-Forwarded-For": ip} if ip else {}


def _client() -> httpx.Client:
    headers = {"Content-Type": "application/json", **_client_ip_headers()}
    key = _api_key()
    if key:
        # Omit the header entirely for guests: the API treats an empty key as invalid.
        headers["X-Api-Key"] = key
    return httpx.Client(base_url=_url, headers=headers, timeout=30)


def _signup_message(reason: str) -> str:
    return (
        f"{reason} Sign up or sign in at {_public_url}/dashboard to get an API key, "
        "then set it as the X-Api-Key header (or the PUSHPAGE_API_KEY environment variable "
        "for the local uvx server) and reconnect."
    )


def _guest_sign_in_required(what: str) -> dict:
    return {
        "error": "Sign in required",
        "guest_mode": True,
        "message": _signup_message(f"{what} needs a pushpage account, and this session is in guest mode (no API key)."),
        "signup_url": f"{_public_url}/dashboard",
    }


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

    Works without an API key in GUEST MODE: the page is created anonymously and is deleted
    after 30 minutes. In that case the result includes guest_mode=true and a message telling
    the user to sign up or sign in at the pushpage dashboard for an API key (permanent
    retention per plan, list_pages and delete_page). Always relay that message to the user.
    An invalid API key is rejected with 401; it is never silently downgraded to guest mode.

    Args:
        html: Complete HTML document (inline CSS/JS; external resources are fine).
            Maximum size is set by the service (1 MB by default).
        title: Optional display title shown in list_pages. If omitted it is taken from
            the HTML <title> tag, falling back to 'Untitled'.

    Returns:
        {url, id, expires_at} on success: the public URL, the page id (pass it to
        delete_page), and the ISO-8601 time the page is deleted automatically (set by the
        account's retention plan, or 30 minutes in guest mode). Guest results also carry
        guest_mode=true, message and signup_url. On failure: {error, status_code, detail},
        e.g. 401 for an invalid API key, 400 if html is empty, 413 if the HTML is too large,
        429 if the quota or rate limit is exceeded (guest results then also carry a sign-up
        message, since accounts have higher limits).
    """
    guest = not _api_key()
    with _client() as client:
        payload = {"html": html}
        if title is not None:
            payload["title"] = title
        r = client.post("/api/pages", json=payload)
        try:
            r.raise_for_status()
        except httpx.HTTPStatusError as e:
            result = {"error": "Failed to publish page", "status_code": e.response.status_code, "detail": e.response.text}
            if guest and e.response.status_code == 429:
                result["guest_mode"] = True
                result["message"] = _signup_message("Guest publishing is rate limited.")
                result["signup_url"] = f"{_public_url}/dashboard"
            return result
        data = r.json()
        result = {"url": data["url"], "id": data["id"], "expires_at": data["expires_at"]}
        if guest:
            result["guest_mode"] = True
            result["message"] = _signup_message(
                "Published in guest mode: this page is deleted after 30 minutes (see expires_at)."
            )
            result["signup_url"] = f"{_public_url}/dashboard"
        return result


@mcp.tool(annotations={"title": "List my pages", "readOnlyHint": True,
                       "destructiveHint": False, "idempotentHint": True,
                       "openWorldHint": False})
def list_pages() -> list[dict]:
    """List the pages published with the current API key's account, including expiry.

    Read-only. Takes no arguments. Admin accounts see every user's pages. Requires an API key:
    in guest mode (no key) it returns a one-element list with guest_mode=true and a message
    telling the user to sign up or sign in at the pushpage dashboard; relay that message.

    Returns:
        A list of page objects, each with id, title, url, created_at and expires_at
        (ISO-8601). Use an id with delete_page. On failure a one-element list
        [{error, status_code, detail}], e.g. status 401 for a missing or invalid key.
    """
    if not _api_key():
        return [_guest_sign_in_required("Listing pages")]
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
    any page). Requires an API key: in guest mode (no key) it returns guest_mode=true and a
    message telling the user to sign up or sign in at the pushpage dashboard; relay that
    message. Guest pages cannot be deleted; they expire after 30 minutes.

    Args:
        id: The page id returned by publish_page or listed by list_pages (for example
            'a1b2c3d4'), not the full URL.

    Returns:
        {deleted: true, id} on success. On failure {error, status_code, detail}, e.g. 404
        if the id does not exist, 403 if the page is not yours, 401 if the key is invalid.
    """
    if not _api_key():
        return _guest_sign_in_required("Deleting pages")
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
        {status: 'UP'} when the service is healthy. Nothing else is reported publicly
        (statistics are admin-only). On failure {error, status_code, detail}.
    """
    with _client() as client:
        r = client.get("/api/health")
        try:
            r.raise_for_status()
        except httpx.HTTPStatusError as e:
            return {"error": "Service unreachable", "status_code": e.response.status_code, "detail": e.response.text}
        return r.json()


# ── Entry point ───────────────────────────────────────────────────────────────

def main(argv: list[str] | None = None, *, default_transport: str = "stdio",
         default_host: str = "127.0.0.1") -> None:
    """Run the MCP server.

    ``pushpage-mcp`` (the PyPI command) defaults to stdio for local MCP clients.
    The Docker image runs ``server.py``, which defaults to streamable-http.
    """
    parser = argparse.ArgumentParser(
        prog="pushpage-mcp",
        description="pushpage MCP server: publish, list and delete HTML pages.",
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--stdio", action="store_true", help="serve over stdio (local clients)")
    mode.add_argument("--http", action="store_true", help="serve streamable-http at /mcp")
    parser.add_argument("--host", default=os.environ.get("HOST", default_host),
                        help="HTTP bind address (default: %(default)s)")
    parser.add_argument("--port", type=int, default=int(os.environ.get("PORT", "8000")),
                        help="HTTP port (default: %(default)s)")
    args = parser.parse_args(argv)

    if args.stdio:
        transport = "stdio"
    elif args.http:
        transport = "http"
    else:
        transport = os.environ.get("PUSHPAGE_MCP_TRANSPORT", default_transport).lower()

    if transport == "stdio":
        # Launched by an MCP client; the API key comes from the environment.
        mcp.run(transport="stdio")
        return
    app = mcp.http_app(path="/mcp", transport="streamable-http")
    uvicorn.run(app, host=args.host, port=args.port)


def cli() -> None:
    """Console-script entry point (``pushpage-mcp``)."""
    main()
