"""Smoke tests — verify tools register correctly without a live pushpage instance."""
import asyncio
import importlib
import os
import sys
import types
import unittest
from unittest.mock import MagicMock


def _load_server(url: str = "http://localhost:8080") -> types.ModuleType:
    """Import server.py with PUSHPAGE_URL set and httpx/uvicorn stubbed out."""
    os.environ["PUSHPAGE_URL"] = url
    os.environ.pop("PUSHPAGE_API_KEY", None)  # must not be required anymore
    sys.modules["httpx"] = MagicMock()
    sys.modules["uvicorn"] = MagicMock()

    sys.modules.pop("server", None)
    sys.path.insert(0, os.path.dirname(__file__))
    return importlib.import_module("server")


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

    def test_missing_pushpage_url_exits(self):
        saved = os.environ.pop("PUSHPAGE_URL", None)
        sys.modules.pop("server", None)
        with self.assertRaises(SystemExit):
            importlib.import_module("server")
        if saved:
            os.environ["PUSHPAGE_URL"] = saved

    def test_no_server_side_api_key_required(self):
        """Server must start without PUSHPAGE_API_KEY — keys come from clients."""
        os.environ.pop("PUSHPAGE_API_KEY", None)
        sys.modules.pop("server", None)
        mod = importlib.import_module("server")
        self.assertIsNotNone(mod.mcp)


if __name__ == "__main__":
    unittest.main()
