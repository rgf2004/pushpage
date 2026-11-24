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
            {"publish_page", "list_pages", "delete_page", "health"},
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

            get = delete = post

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

    def test_credentials_are_presented_to_the_api_by_type(self):
        self.assertEqual(self.server._auth_headers("pp_abc"), {"X-Api-Key": "pp_abc"})
        self.assertEqual(self.server._auth_headers("eyJ.a.b"), {"Authorization": "Bearer eyJ.a.b"})
        self.assertEqual(self.server._auth_headers(""), {})

    def test_guest_message_points_to_the_sign_in_endpoint(self):
        self.assertIn("https://my.example/mcp/signin", self.server._signup_message("x"))

    def test_http_server_is_wrapped_by_the_gateway(self):
        with patch.object(self.server.uvicorn, "run") as uv, patch.object(self.server.mcp, "http_app"):
            self.server.main(["--http"])
        self.assertIsInstance(uv.call_args.args[0], self.server.SignInGateway)


if __name__ == "__main__":
    unittest.main()
