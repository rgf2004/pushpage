#!/usr/bin/env bash
# Publish a released version of the MCP server to the official MCP Registry.
#
#   scripts/publish-registry.sh 1.9.0
#
# Run it after the release is out: the PyPI package for that version must exist, and the
# production site must already serve the icons listed in server.json (deploy first).
# Log in once beforehand with:  mcp-publisher login github
# The checked-in server.json keeps a placeholder version; this stamps the real one into a temporary copy.
set -euo pipefail

VERSION="${1:-}"
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "usage: $0 <version>   e.g. $0 1.9.0 (no leading v)" >&2
  exit 2
fi
command -v mcp-publisher >/dev/null || { echo "mcp-publisher not found (brew install mcp-publisher)" >&2; exit 1; }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "Checking that pushpage-mcp $VERSION is on PyPI..."
code="$(curl -s -o /dev/null -w '%{http_code}' "https://pypi.org/pypi/pushpage-mcp/$VERSION/json")"
[[ "$code" == "200" ]] || { echo "pushpage-mcp $VERSION is not on PyPI yet (HTTP $code). Release first." >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
sed -E "s/\"version\": \"[0-9.]+\"/\"version\": \"$VERSION\"/g" "$ROOT/server.json" > "$WORK/server.json"

echo "Checking the icons are served..."
grep -o '"src": "[^"]*"' "$WORK/server.json" | sed 's/"src": "//;s/"$//' | while read -r url; do
  icon_code="$(curl -s -o /dev/null -w '%{http_code}' "$url")"
  [[ "$icon_code" == "200" ]] || { echo "Icon not reachable: $url (HTTP $icon_code). Deploy the site first." >&2; exit 1; }
done

cd "$WORK"
mcp-publisher validate server.json
read -r -p "Publish io.github.rgf2004/pushpage@$VERSION to the MCP Registry? [y/N] " answer
[[ "$answer" == "y" || "$answer" == "Y" ]] || { echo "Aborted."; exit 1; }
mcp-publisher publish
