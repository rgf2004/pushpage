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


if __name__ == "__main__":
    unittest.main()
