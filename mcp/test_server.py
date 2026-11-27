"""Smoke tests — verify tools register correctly without a live pushpage instance."""
import asyncio
import importlib
import os
import sys
import types
import unittest
from unittest.mock import MagicMock, patch


def _load_server(url: str = "http://localhost:8080") -> types.ModuleType:
    """Import server.py with PUSHPAGE_URL set and external deps stubbed out."""
    os.environ["PUSHPAGE_URL"] = url
    os.environ.pop("PUSHPAGE_API_KEY", None)
    sys.modules["httpx"] = MagicMock()
    sys.modules["uvicorn"] = MagicMock()

    sys.modules.pop("pushpage_mcp.server", None)
    sys.path.insert(0, os.path.dirname(__file__))
    return importlib.import_module("pushpage_mcp.server")


class TestServerRegistration(unittest.TestCase):
    def setUp(self):
        self.server = _load_server()

    def _tool_names(self) -> set:
        return {t.name for t in asyncio.run(self.server.mcp.list_tools())}

    def test_all_tools_registered(self):
        self.assertEqual(
            self._tool_names(),
            {"publish_page", "list_pages", "update_page", "delete_page", "health"},
        )

    def test_missing_pushpage_url_defaults_to_public_instance(self):
        saved = os.environ.pop("PUSHPAGE_URL", None)
        sys.modules.pop("pushpage_mcp.server", None)
        mod = importlib.import_module("pushpage_mcp.server")
        self.assertEqual(mod._url, "https://pushpage.link")
        if saved:
            os.environ["PUSHPAGE_URL"] = saved

    def test_no_server_side_api_key_required(self):
        """Server must start without PUSHPAGE_API_KEY — keys come from clients."""
        os.environ.pop("PUSHPAGE_API_KEY", None)
        sys.modules.pop("pushpage_mcp.server", None)
        mod = importlib.import_module("pushpage_mcp.server")
        self.assertIsNotNone(mod.mcp)

    def test_api_key_read_from_request(self):
        """_api_key() extracts the Bearer token from the current HTTP request."""
        mock_request = MagicMock()
        mock_request.headers = {"authorization": "Bearer pp_test123"}
        with patch("pushpage_mcp.server.get_http_request", return_value=mock_request):
            key = self.server._api_key()
        self.assertEqual(key, "pp_test123")

    def test_api_key_from_env_when_no_http_request(self):
        """stdio mode: no HTTP request, so the key comes from the environment."""
        with patch("pushpage_mcp.server.get_http_request", side_effect=RuntimeError("no request")):
            with patch.dict(os.environ, {"PUSHPAGE_API_KEY": "pp_env"}):
                self.assertEqual(self.server._api_key(), "pp_env")
            with patch.dict(os.environ, {"AUTHORIZATION": "Bearer pp_auth"}, clear=False):
                os.environ.pop("PUSHPAGE_API_KEY", None)
                self.assertEqual(self.server._api_key(), "pp_auth")

    def test_api_key_from_mixed_case_authorization_env(self):
        """Launchers such as Glama pass the variable as 'Authorization'."""
        with patch("pushpage_mcp.server.get_http_request", side_effect=RuntimeError("no request")):
            with patch.dict(os.environ, {"Authorization": "Bearer pp_mixed"}, clear=False):
                for k in ("PUSHPAGE_API_KEY", "AUTHORIZATION"):
                    os.environ.pop(k, None)
                self.assertEqual(self.server._api_key(), "pp_mixed")

    def test_http_request_key_wins_over_env(self):
        mock_request = MagicMock()
        mock_request.headers = {"x-api-key": "pp_header"}
        with patch("pushpage_mcp.server.get_http_request", return_value=mock_request):
            with patch.dict(os.environ, {"PUSHPAGE_API_KEY": "pp_env"}):
                self.assertEqual(self.server._api_key(), "pp_header")


class TestEntryPoint(unittest.TestCase):
    def setUp(self):
        self.server = _load_server()
        os.environ.pop("PUSHPAGE_MCP_TRANSPORT", None)

    def test_console_command_defaults_to_stdio(self):
        with patch.object(self.server.mcp, "run") as run:
            self.server.main([])
        run.assert_called_once_with(transport="stdio")

    def test_http_flag_starts_uvicorn(self):
        with patch.object(self.server.uvicorn, "run") as uv, \
                patch.object(self.server.mcp, "http_app"), \
                patch.object(self.server.mcp, "run") as run:
            self.server.main(["--http", "--port", "9001"])
        run.assert_not_called()
        self.assertEqual(uv.call_args.kwargs["port"], 9001)
        self.assertEqual(uv.call_args.kwargs["host"], "127.0.0.1")

    def test_docker_entry_defaults_to_http_on_all_interfaces(self):
        with patch.object(self.server.uvicorn, "run") as uv, \
                patch.object(self.server.mcp, "http_app"):
            self.server.main([], default_transport="http", default_host="0.0.0.0")
        self.assertEqual(uv.call_args.kwargs["host"], "0.0.0.0")

    def test_stdio_flag_overrides_http_default(self):
        with patch.object(self.server.mcp, "run") as run:
            self.server.main(["--stdio"], default_transport="http")
        run.assert_called_once_with(transport="stdio")


class _FakeResponse:
    def __init__(self, data=None, status=200):
        self._data, self.status_code, self.text = data or {}, status, "body"

    def raise_for_status(self):
        if self.status_code >= 400:
            err = self._HTTPStatusError("boom")
            err.response = self
            raise err

    def json(self):
        return self._data


class TestGuestMode(unittest.TestCase):
    """Keyless (guest) sessions: no key header sent, sign-up guidance returned."""

    def setUp(self):
        self.server = _load_server()
        self.created = []

        class HTTPStatusError(Exception):
            pass

        self.server.httpx.HTTPStatusError = HTTPStatusError
        _FakeResponse._HTTPStatusError = HTTPStatusError
        self.response = _FakeResponse({"url": "https://x/pages/a.html", "id": "a", "expires_at": "t"})
        outer = self

        class FakeClient:
            def __init__(self, **kwargs):
                outer.created.append(kwargs)

            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

            def post(self, *a, **k):
                return outer.response

            get = delete = put = post

        self.server.httpx.Client = FakeClient
        patcher = patch("pushpage_mcp.server.get_http_request", side_effect=RuntimeError("no request"))
        patcher.start()
        self.addCleanup(patcher.stop)
        for k in ("PUSHPAGE_API_KEY", "AUTHORIZATION", "Authorization"):
            os.environ.pop(k, None)

    def test_guest_publish_omits_key_header_and_explains_guest_mode(self):
        out = self.server.publish_page("<h1>hi</h1>")
        self.assertNotIn("X-Api-Key", self.created[0]["headers"])
        self.assertTrue(out["guest_mode"])
        self.assertIn("30 minutes", out["message"])
        self.assertIn("/dashboard", out["message"])
        self.assertTrue(out["signup_url"].endswith("/dashboard"))
        self.assertEqual(out["id"], "a")

    def test_keyed_publish_sends_key_and_has_no_guest_fields(self):
        with patch.dict(os.environ, {"PUSHPAGE_API_KEY": "pp_real"}):
            out = self.server.publish_page("<h1>hi</h1>")
        self.assertEqual(self.created[0]["headers"]["X-Api-Key"], "pp_real")
        self.assertNotIn("guest_mode", out)
        self.assertNotIn("message", out)

    def test_guest_rate_limit_error_suggests_signup(self):
        self.response = _FakeResponse(status=429)
        out = self.server.publish_page("<h1>hi</h1>")
        self.assertEqual(out["status_code"], 429)
        self.assertTrue(out["guest_mode"])
        self.assertIn("Sign up or sign in", out["message"])

    def test_invalid_key_error_is_not_downgraded_to_guest(self):
        self.response = _FakeResponse(status=401)
        with patch.dict(os.environ, {"PUSHPAGE_API_KEY": "pp_bad"}):
            out = self.server.publish_page("<h1>hi</h1>")
        self.assertEqual(out["status_code"], 401)
        self.assertNotIn("guest_mode", out)

    def test_guest_list_and_delete_ask_to_sign_in_without_calling_the_api(self):
        listed = self.server.list_pages()
        deleted = self.server.delete_page("abc")
        self.assertEqual(self.created, [])
        self.assertTrue(listed[0]["guest_mode"])
        self.assertTrue(deleted["guest_mode"])
        self.assertIn("Sign up or sign in", deleted["message"])

    def test_guest_update_asks_to_sign_in_without_calling_the_api(self):
        out = self.server.update_page("abc", "<h1>new</h1>")
        self.assertEqual(self.created, [])
        self.assertEqual(out["error"], "Sign in required")
        self.assertTrue(out["guest_mode"])
        self.assertIn("Sign up or sign in", out["message"])

    def test_keyed_update_puts_to_the_page_and_returns_same_url_and_id(self):
        calls = []
        self.response = _FakeResponse({"url": "https://x/pages/a.html", "id": "a", "expires_at": "t2"})
        self.server.httpx.Client.put = lambda s, path, json=None: (calls.append((path, json)) or self.response)
        with patch.dict(os.environ, {"PUSHPAGE_API_KEY": "pp_real"}):
            out = self.server.update_page("a", "<h1>new</h1>", title="T")
        self.assertEqual(calls, [("/api/pages/a", {"html": "<h1>new</h1>", "title": "T"})])
        self.assertEqual(out, {"url": "https://x/pages/a.html", "id": "a", "expires_at": "t2"})
        self.assertEqual(self.created[0]["headers"]["X-Api-Key"], "pp_real")

    def test_update_omits_title_when_not_given(self):
        calls = []
        self.server.httpx.Client.put = lambda s, path, json=None: (calls.append(json) or self.response)
        with patch.dict(os.environ, {"PUSHPAGE_API_KEY": "pp_real"}):
            self.server.update_page("a", "<h1>new</h1>")
        self.assertEqual(calls, [{"html": "<h1>new</h1>"}])

    def test_update_errors_are_mapped(self):
        for status in (403, 404, 413, 429):
            self.response = _FakeResponse(status=status)
            with patch.dict(os.environ, {"PUSHPAGE_API_KEY": "pp_real"}):
                out = self.server.update_page("a", "<h1>new</h1>")
            self.assertEqual(out["error"], "Failed to update page")
            self.assertEqual(out["status_code"], status)
            self.assertNotIn("guest_mode", out)

    def test_update_403_for_a_missing_oauth_scope_explains_how_to_reconnect(self):
        self.response = _FakeResponse(status=403)
        self.response.text = "This access token's scope does not allow this request"
        with patch.dict(os.environ, {"PUSHPAGE_API_KEY": "pp_real"}):
            out = self.server.update_page("a", "<h1>new</h1>")
        self.assertEqual(out["status_code"], 403)
        self.assertEqual(out["error"], "Failed to update page")
        self.assertIn("Connected apps", out["message"])

    def test_update_403_not_your_page_has_no_reconnect_hint(self):
        self.response = _FakeResponse(status=403)
        self.response.text = "You can only update your own pages"
        with patch.dict(os.environ, {"PUSHPAGE_API_KEY": "pp_real"}):
            out = self.server.update_page("a", "<h1>new</h1>")
        self.assertNotIn("message", out)

    def test_invalid_ids_are_rejected_without_calling_the_api(self):
        with patch.dict(os.environ, {"PUSHPAGE_API_KEY": "pp_real"}):
            for bad in ("../x", "a/b", "a?x=1", "a#f", "", " ", "abc def", "a%2fb"):
                self.assertEqual(self.server.update_page(bad, "<h1>x</h1>")["error"], "Invalid page id", bad)
                self.assertEqual(self.server.delete_page(bad)["error"], "Invalid page id", bad)
        self.assertEqual(self.created, [])

    def test_valid_id_still_reaches_the_api_for_delete(self):
        with patch.dict(os.environ, {"PUSHPAGE_API_KEY": "pp_real"}):
            out = self.server.delete_page("a1B2c3d4")
        self.assertEqual(out, {"deleted": True, "id": "a1B2c3d4"})

    def test_update_tool_annotations(self):
        tool = next(t for t in asyncio.run(self.server.mcp.list_tools()) if t.name == "update_page")
        ann = tool.annotations
        self.assertFalse(ann.readOnlyHint)
        self.assertTrue(ann.destructiveHint)
        self.assertTrue(ann.idempotentHint)
        self.assertFalse(ann.openWorldHint)

    def test_client_ip_is_forwarded_over_http(self):
        request = MagicMock()
        request.headers = {"x-real-ip": "203.0.113.9"}
        request.client.host = "172.18.0.5"
        with patch("pushpage_mcp.server.get_http_request", return_value=request):
            self.server.publish_page("<h1>hi</h1>")
        headers = self.created[0]["headers"]
        self.assertEqual(headers["X-Real-IP"], "203.0.113.9")
        self.assertEqual(headers["X-Forwarded-For"], "203.0.113.9")

    def test_public_url_used_in_signup_link(self):
        self.server._public_url = "https://my.example"
        self.assertEqual(self.server.list_pages()[0]["signup_url"], "https://my.example/dashboard")


class TestSignInEndpoint(unittest.TestCase):
    """/mcp/signin: same server, but only for callers with a valid credential (OAuth discovery on 401)."""

    def setUp(self):
        self.server = _load_server()
        self.server._public_url = "https://my.example"
        self.sent = []
        self.forwarded = []

        async def inner(scope, receive, send):
            self.forwarded.append(scope["path"])

        self.gateway = self.server.SignInGateway(inner)

    def _call(self, path, headers=None):
        scope = {"type": "http", "path": path, "raw_path": path.encode(),
                 "headers": [(k.encode(), v.encode()) for k, v in (headers or {}).items()]}

        async def send(message):
            self.sent.append(message)

        asyncio.run(self.gateway(scope, None, send))

    def _status(self):
        return next(m["status"] for m in self.sent if m["type"] == "http.response.start")

    def _challenge(self):
        start = next(m for m in self.sent if m["type"] == "http.response.start")
        return dict(start["headers"])[b"www-authenticate"].decode()

    def test_without_credentials_answers_401_with_discovery_pointer(self):
        self._call("/mcp/signin")
        self.assertEqual(self._status(), 401)
        self.assertIn('resource_metadata="https://my.example/.well-known/oauth-protected-resource/mcp/signin"',
                      self._challenge())
        self.assertNotIn("invalid_token", self._challenge())
        self.assertEqual(self.forwarded, [])

    def test_invalid_token_answers_401_invalid_token(self):
        with patch("pushpage_mcp.server._token_is_valid", return_value=False) as check:
            async def no(token):
                return False
            check.side_effect = no
            self._call("/mcp/signin", {"authorization": "Bearer a.b.c"})
        self.assertEqual(self._status(), 401)
        self.assertIn('error="invalid_token"', self._challenge())
        self.assertEqual(self.forwarded, [])

    def test_api_outage_answers_503_not_invalid_token(self):
        async def boom(token):
            raise ConnectionError("api down")

        with patch("pushpage_mcp.server._token_is_valid", side_effect=boom):
            self._call("/mcp/signin", {"authorization": "Bearer a.b.c"})
        self.assertEqual(self._status(), 503)
        self.assertEqual(self.forwarded, [])

    def test_valid_token_is_passed_on_as_the_normal_mcp_route(self):
        async def yes(token):
            return True

        with patch("pushpage_mcp.server._token_is_valid", side_effect=yes):
            self._call("/mcp/signin", {"authorization": "Bearer a.b.c"})
        self.assertEqual(self.forwarded, ["/mcp"])
        self.assertEqual(self.sent, [])

    def test_plain_mcp_endpoint_is_untouched_and_stays_open_to_guests(self):
        self._call("/mcp")
        self.assertEqual(self.forwarded, ["/mcp"])
        self.assertEqual(self.sent, [])

    def test_guest_endpoint_drops_credentials_and_serves_the_mcp_route(self):
        seen = {}

        async def inner(scope, receive, send):
            seen["path"] = scope["path"]
            seen["headers"] = {k.decode(): v.decode() for k, v in scope["headers"]}

        gateway = self.server.SignInGateway(inner)
        scope = {"type": "http", "path": "/mcp-guest", "raw_path": b"/mcp-guest", "headers": [
            (b"authorization", b"Bearer pp_secret"), (b"x-api-key", b"pp_secret"), (b"accept", b"text/event-stream")]}
        asyncio.run(gateway(scope, None, None))
        self.assertEqual(seen["path"], "/mcp")
        self.assertEqual(seen["headers"], {"accept": "text/event-stream"})

    def test_credentials_are_presented_to_the_api_by_type(self):
        self.assertEqual(self.server._auth_headers("pp_abc"), {"X-Api-Key": "pp_abc"})
        self.assertEqual(self.server._auth_headers("eyJ.a.b"), {"Authorization": "Bearer eyJ.a.b"})
        self.assertEqual(self.server._auth_headers(""), {})

    def test_guest_message_points_to_mcp_and_browser_sign_in(self):
        msg = self.server._signup_message("x")
        self.assertIn("https://my.example/mcp and sign in with the browser when prompted", msg)
        self.assertNotIn("/mcp/signin", msg)
        self.assertIn("PUSHPAGE_API_KEY", msg)

    def test_http_server_is_wrapped_by_the_gateway(self):
        with patch.object(self.server.uvicorn, "run") as uv, patch.object(self.server.mcp, "http_app"):
            self.server.main(["--http"])
        self.assertIsInstance(uv.call_args.args[0], self.server.SignInGateway)


def _jwt(scope=None) -> str:
    import base64
    import json
    claims = {"sub": "u"} if scope is None else {"sub": "u", "scope": scope}
    payload = base64.urlsafe_b64encode(json.dumps(claims).encode()).decode().rstrip("=")
    return f"eyJhbGciOiJIUzI1NiJ9.{payload}.sig"


def _rpc(method, name=None, **params):
    msg = {"jsonrpc": "2.0", "id": 1, "method": method}
    if name:
        msg["params"] = {"name": name, "arguments": params}
    return msg


class TestLazyAuthOnMcp(unittest.TestCase):
    """/mcp: public calls pass anonymously; protected tools get a 401/403 challenge."""

    ALL = "pages:publish pages:update pages:read pages:delete"
    META = 'resource_metadata="https://my.example/.well-known/oauth-protected-resource/mcp"'

    def setUp(self):
        self.server = _load_server()
        self.server._public_url = "https://my.example"
        self.sent = []
        self.forwarded = []
        self.valid = True
        self.api_down = False

        async def inner(scope, receive, send):
            body = b""
            while True:
                m = await receive()
                body += m.get("body", b"")
                if not m.get("more_body", False):
                    break
            self.forwarded.append((scope["path"], body))

        self.gateway = self.server.SignInGateway(inner)

        async def check(token):
            if self.api_down:
                raise ConnectionError("down")
            return self.valid

        patcher = patch("pushpage_mcp.server._token_is_valid", side_effect=check)
        patcher.start()
        self.addCleanup(patcher.stop)

    def _post(self, payload, headers=None, path="/mcp", method="POST", chunks=None):
        import json
        body = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
        parts = chunks or [body]
        scope = {"type": "http", "method": method, "path": path, "raw_path": path.encode(),
                 "headers": [(k.encode(), v.encode()) for k, v in (headers or {}).items()]}
        queue = [{"type": "http.request", "body": c, "more_body": i < len(parts) - 1} for i, c in enumerate(parts)]

        async def receive():
            return queue.pop(0)

        async def send(message):
            self.sent.append(message)

        asyncio.run(self.gateway(scope, receive, send))
        return body

    def _status(self):
        return next(m["status"] for m in self.sent if m["type"] == "http.response.start")

    def _challenge(self):
        start = next(m for m in self.sent if m["type"] == "http.response.start")
        return dict(start["headers"])[b"www-authenticate"].decode()

    def assertPassed(self, body):
        self.assertEqual(self.sent, [])
        self.assertEqual(self.forwarded, [("/mcp", body)])

    def test_anonymous_public_calls_pass_and_body_is_replayed_byte_for_byte(self):
        for payload in (_rpc("initialize"), _rpc("ping"), _rpc("tools/list"),
                        {"jsonrpc": "2.0", "method": "notifications/initialized"},
                        _rpc("tools/call", "publish_page", html="<h1>é</h1>"), _rpc("tools/call", "health")):
            self.sent.clear()
            self.forwarded.clear()
            body = self._post(payload)
            self.assertPassed(body)

    def test_large_publish_body_is_replayed_intact_across_chunks(self):
        body = ('{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"publish_page","arguments":{"html":"'
                + "x" * 3_000_000 + '"}}}').encode()
        self._post(body, chunks=[body[:1_000_000], body[1_000_000:2_000_000], body[2_000_000:]])
        self.assertPassed(body)

    def test_anonymous_protected_tools_get_401_with_exact_challenge(self):
        for tool in ("list_pages", "delete_page", "update_page"):
            self.sent.clear()
            self._post(_rpc("tools/call", tool))
            self.assertEqual(self._status(), 401)
            self.assertEqual(self._challenge(),
                             'Bearer error="invalid_token", error_description="Authentication required for this tool", '
                             + self.META + f', scope="{self.ALL}"')
        self.assertEqual(self.forwarded, [])

    def test_batch_with_protected_call_is_challenged_with_all_scopes(self):
        self._post([_rpc("tools/call", "publish_page"), _rpc("tools/call", "list_pages"),
                    _rpc("tools/call", "update_page"), _rpc("tools/call", "list_pages")])
        self.assertEqual(self._status(), 401)
        self.assertIn(f'scope="{self.ALL}"', self._challenge())
        self.assertEqual(self.forwarded, [])

    def test_batch_of_only_public_calls_passes(self):
        body = self._post([_rpc("tools/list"), _rpc("tools/call", "health")])
        self.assertPassed(body)

    def test_jwt_with_required_scope_passes(self):
        body = self._post(_rpc("tools/call", "list_pages"), {"authorization": "Bearer " + _jwt("pages:read pages:publish")})
        self.assertPassed(body)

    def test_jwt_without_scope_claim_is_a_dashboard_session_and_passes(self):
        body = self._post(_rpc("tools/call", "delete_page"), {"authorization": "Bearer " + _jwt()})
        self.assertPassed(body)

    def test_invalid_jwt_gets_401_even_for_a_public_call(self):
        self.valid = False
        for payload in (_rpc("tools/list"), _rpc("tools/call", "list_pages")):
            self.sent.clear()
            self._post(payload, {"authorization": "Bearer " + _jwt("pages:read")})
            self.assertEqual(self._status(), 401)
            self.assertEqual(self._challenge(),
                             'Bearer error="invalid_token", error_description="The access token is invalid or expired", '
                             + self.META + f', scope="{self.ALL}"')
        self.assertEqual(self.forwarded, [])

    def test_api_down_answers_503_never_401(self):
        self.api_down = True
        self._post(_rpc("tools/call", "list_pages"), {"authorization": "Bearer " + _jwt("pages:read")})
        self.assertEqual(self._status(), 503)
        self.assertEqual(self.forwarded, [])

    def test_jwt_without_needed_scope_gets_403_insufficient_scope_with_all_scopes(self):
        self._post([_rpc("tools/call", "list_pages"), _rpc("tools/call", "update_page")],
                   {"authorization": "Bearer " + _jwt("pages:publish pages:read")})
        self.assertEqual(self._status(), 403)
        self.assertEqual(self._challenge(),
                         'Bearer error="insufficient_scope", ' + self.META + f', scope="{self.ALL}"')
        self.assertEqual(self.forwarded, [])

    def test_read_only_jwt_calling_publish_page_gets_403_with_all_scopes(self):
        self._post(_rpc("tools/call", "publish_page"), {"authorization": "Bearer " + _jwt("pages:read")})
        self.assertEqual(self._status(), 403)
        self.assertEqual(self._challenge(),
                         'Bearer error="insufficient_scope", ' + self.META + f', scope="{self.ALL}"')
        self.assertEqual(self.forwarded, [])

    def test_jwt_with_publish_scope_and_other_public_calls_pass(self):
        for payload, scope in ((_rpc("tools/call", "publish_page"), "pages:publish"),
                               (_rpc("tools/call", "health"), "pages:read"),
                               (_rpc("tools/list"), "pages:read")):
            self.sent.clear()
            self.forwarded.clear()
            body = self._post(payload, {"authorization": "Bearer " + _jwt(scope)})
            self.assertPassed(body)

    def test_dashboard_session_jwt_without_scope_claim_can_publish(self):
        body = self._post(_rpc("tools/call", "publish_page"), {"authorization": "Bearer " + _jwt()})
        self.assertPassed(body)

    def test_pp_key_publish_untouched(self):
        body = self._post(_rpc("tools/call", "publish_page"), {"x-api-key": "pp_abc"})
        self.assertPassed(body)

    def test_challenge_scope_set_covers_every_tool_scope(self):
        needed = set(self.server._PROTECTED_TOOL_SCOPES.values()) | set(self.server._TOKEN_HOLDER_TOOL_SCOPES.values())
        self.assertEqual(set(self.server._ALL_ACCOUNT_SCOPES), needed)

    def test_api_keys_pass_through_in_both_header_forms_without_api_call(self):
        for headers in ({"authorization": "Bearer pp_abc"}, {"x-api-key": "pp_abc"}):
            self.sent.clear()
            self.forwarded.clear()
            self.valid = False  # would 401 if the key were treated as a JWT
            body = self._post(_rpc("tools/call", "delete_page"), headers)
            self.assertPassed(body)

    def test_non_json_body_passes_through(self):
        body = self._post(b"not json at all")
        self.assertPassed(body)

    def test_oversized_body_passes_through_uninspected_and_intact(self):
        self.server._MAX_INSPECT_BYTES = 100
        body = (b'{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_pages","arguments":{"pad":"'
                + b"x" * 500 + b'"}}}')
        self._post(body, chunks=[body[:200], body[200:]])
        self.assertPassed(body)

    def test_get_and_delete_are_untouched(self):
        for method in ("GET", "DELETE"):
            self.forwarded.clear()
            self._post(b"", {"authorization": "Bearer " + _jwt()}, method=method)
            self.assertEqual(self.forwarded, [("/mcp", b"")])
            self.assertEqual(self.sent, [])

    def test_trailing_slash_is_also_covered(self):
        self._post(_rpc("tools/call", "list_pages"), path="/mcp/")
        self.assertEqual(self._status(), 401)

    def test_guest_endpoint_never_challenges_and_drops_credentials(self):
        self.valid = False
        self._post(_rpc("tools/call", "list_pages"), {"authorization": "Bearer " + _jwt("x")}, path="/mcp-guest")
        self.assertEqual(self.sent, [])
        self.assertEqual(self.forwarded[0][0], "/mcp")

    def test_deeply_nested_body_is_passed_through_not_crashed(self):
        body = b"[" * 200000
        self._post(body)
        self.assertPassed(body)
        self.sent.clear()
        self.forwarded.clear()
        self._post(body, {"authorization": "Bearer " + _jwt("pages:read")})
        self.assertPassed(body)

    def test_malformed_jwt_scope_claim_does_not_crash(self):
        self.assertIsNone(self.server._token_scopes("a.%%%.c"))
        self.assertIsNone(self.server._token_scopes("nodots"))

    def test_signin_endpoint_still_requires_a_credential_for_everything(self):
        self._post(_rpc("tools/list"), path="/mcp/signin")
        self.assertEqual(self._status(), 401)
        self.assertIn("oauth-protected-resource/mcp/signin", self._challenge())
        self.assertEqual(self.forwarded, [])


if __name__ == "__main__":
    unittest.main()
