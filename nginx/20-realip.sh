#!/bin/sh
# Renders /etc/nginx/realip.conf from TRUSTED_PROXIES / REAL_IP_HEADER at container start.
# Empty TRUSTED_PROXIES (the default) writes an empty file: nginx keeps the socket peer address.
set -eu

OUT=/etc/nginx/realip.conf
: > "$OUT"

proxies=$(printf '%s' "${TRUSTED_PROXIES:-}" | tr -d ' ')
[ -z "$proxies" ] && exit 0

header=${REAL_IP_HEADER:-X-Forwarded-For}
case "$header" in
  *[!A-Za-z0-9_-]*) echo "20-realip: invalid REAL_IP_HEADER '$header'" >&2; exit 1 ;;
esac

{
  old_ifs=$IFS; IFS=,
  for entry in $proxies; do
    case "$entry" in
      ''|*[!0-9A-Fa-f:./]*) echo "20-realip: invalid TRUSTED_PROXIES entry '$entry'" >&2; exit 1 ;;
    esac
    echo "set_real_ip_from $entry;"
  done
  IFS=$old_ifs
  echo "real_ip_header $header;"
  echo "real_ip_recursive on;"
} > "$OUT"

echo "20-realip: trusting proxies [$proxies], client IP from $header"
