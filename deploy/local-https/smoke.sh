#!/usr/bin/env bash
# Session cookie smoke check through the public HTTPS origin: CSRF -> login -> /api/me -> logout -> /api/me.
# Prints only status lines and Set-Cookie attributes (cookie values are masked). Never prints the password.
# Usage: SMOKE_EMAIL=... SMOKE_PASSWORD=... ./smoke.sh https://localhost:8443 [--insecure]
set -euo pipefail
base=${1:?usage: smoke.sh BASE_URL [--insecure]}
tls=()
[[ ${2:-} == --insecure ]] && tls=(-k)
: "${SMOKE_EMAIL:?set SMOKE_EMAIL}" "${SMOKE_PASSWORD:?set SMOKE_PASSWORD}"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
jar=$work/cookies

step() {
  local name=$1; shift
  curl -sS "${tls[@]}" -b "$jar" -c "$jar" -D "$work/headers" -o "$work/body" "$@"
  printf '== %s: %s\n' "$name" "$(head -1 "$work/headers" | tr -d '\r')"
  grep -i '^set-cookie:' "$work/headers" | tr -d '\r' | sed -E 's/(SESSION=)([^;]{6})[^;]*/\1\2.../' || true
}
csrf() { sed -E 's/.*"token":"([^"]+)".*/\1/' "$work/body"; }

step csrf "$base/api/auth/csrf"
token=$(csrf)
printf '{"email":"%s","password":"%s"}' "$SMOKE_EMAIL" "$SMOKE_PASSWORD" > "$work/login.json"
step login -X POST -H 'Content-Type: application/json' -H "X-CSRF-TOKEN: $token" --data-binary "@$work/login.json" "$base/api/auth/login"
step me "$base/api/me"
step csrf-after-login "$base/api/auth/csrf"
token=$(csrf)
step logout -X POST -H "X-CSRF-TOKEN: $token" "$base/api/auth/logout"
step me-after-logout "$base/api/me"
