"""Docker / source entry point: serves streamable-http by default.

``python server.py``           -> HTTP on 0.0.0.0:8000 (what the Docker image runs)
``python server.py --stdio``   -> stdio (kept for registries that start the server)
The installable command is ``pushpage-mcp`` (see pyproject.toml), which defaults to stdio.
"""
from pushpage_mcp.server import main

if __name__ == "__main__":
    main(default_transport="http", default_host="0.0.0.0")
