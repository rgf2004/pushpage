"""Smoke tests — verify tools register correctly without a live pushpage instance."""
import asyncio
import importlib
import os
import sys
import types
import unittest
from unittest.mock import MagicMock


def _load_server(url: str = "http://localhost:8080", key: str = "pp_test") -> types.ModuleType:
    """Import server.py with required env vars set and httpx stubbed out."""
    os.environ["PUSHPAGE_URL"] = url
    os.environ["PUSHPAGE_API_KEY"] = key
    sys.modules["httpx"] = MagicMock()

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

    def test_missing_env_vars_exits(self):
        saved = {k: os.environ.pop(k, None) for k in ("PUSHPAGE_URL", "PUSHPAGE_API_KEY")}
        sys.modules.pop("server", None)
        with self.assertRaises(SystemExit):
            importlib.import_module("server")
        for k, v in saved.items():
            if v is not None:
                os.environ[k] = v


if __name__ == "__main__":
    unittest.main()
