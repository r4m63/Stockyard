#!/usr/bin/env bash
# E2E smoke test for the quotes pipeline: driver → quotes-service → Redis →
# core-service.  Run on the same host that owns /dev/stockyard.
#
# Steps:
#   1) verify /dev/stockyard exists (operator ran load_driver.sh)
#   2) docker compose up -d quotes-service core-service redis clickhouse
#   3) poll docker compose ps until quotes-service is healthy
#   4) GET http://localhost:8082/healthz on quotes-service → 200
#   5) GET http://localhost:8081/internal/quotes/SBER on core-service → 200
#      with non-zero lastCents
#
# Note: we hit core-service's INTERNAL endpoint deliberately — the public
# gateway route /v1/quotes/{ticker} is JWT-protected (auth-jwt) and a
# smoke test should not have to mint a token just to prove the pipeline
# is alive.  Internal endpoints live on the docker-compose network and
# are exposed locally on port 8081 for diagnostics; in production they
# would not be reachable from the host.
#
# Exits 0 on full pipeline OK, non-zero with a diagnostic on any failure.
#
# Usage:
#   deploy/scripts/smoke_quotes.sh
#
# Optional env:
#   SMOKE_TICKER  — ticker to probe (default SBER)
#   SMOKE_TIMEOUT — overall seconds to wait for first quote (default 60)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TICKER="${SMOKE_TICKER:-SBER}"
TIMEOUT="${SMOKE_TIMEOUT:-60}"
CORE_URL="${SMOKE_CORE_URL:-http://localhost:8081}"
QUOTES_URL="${SMOKE_QUOTES_URL:-http://localhost:8082}"

fail() { echo "smoke: $*" >&2; exit 1; }

# 1) Driver / char device must exist on host.
if [[ ! -c /dev/stockyard ]]; then
    fail "/dev/stockyard missing — run: sudo deploy/scripts/load_driver.sh"
fi

# 2) Bring the pipeline up.
echo "[smoke] docker compose up -d"
(cd "$REPO_ROOT" && docker compose up -d redis clickhouse quotes-service core-service)

# 3) Wait for quotes-service to flip to healthy.  docker compose's own
#    healthcheck on /healthcheck does the actual work; we just poll the
#    derived state via `docker inspect`.
echo "[smoke] waiting up to ${TIMEOUT}s for quotes-service healthy"
deadline=$(( $(date +%s) + TIMEOUT ))
while :; do
    status=$(docker inspect -f '{{.State.Health.Status}}' stockyard-quotes-service 2>/dev/null || echo "missing")
    if [[ "$status" == "healthy" ]]; then
        break
    fi
    if (( $(date +%s) >= deadline )); then
        fail "quotes-service did not become healthy in ${TIMEOUT}s (last: $status)"
    fi
    sleep 2
done

# 4) Direct /healthz on quotes-service.
if ! curl -fsS "${QUOTES_URL}/healthz" >/dev/null; then
    fail "quotes-service /healthz failed at ${QUOTES_URL}"
fi
echo "[smoke] quotes-service /healthz OK"

# 5) Wait for core-service health (its readiness pings PG + Redis).
echo "[smoke] waiting up to ${TIMEOUT}s for core-service healthy"
deadline=$(( $(date +%s) + TIMEOUT ))
while :; do
    status=$(docker inspect -f '{{.State.Health.Status}}' stockyard-core-service 2>/dev/null || echo "missing")
    if [[ "$status" == "healthy" ]]; then
        break
    fi
    if (( $(date +%s) >= deadline )); then
        fail "core-service did not become healthy in ${TIMEOUT}s (last: $status)"
    fi
    sleep 2
done

# 6) Quote check via core-service /internal/quotes/{ticker} — no auth.
quote_url="${CORE_URL}/internal/quotes/${TICKER}"
echo "[smoke] GET $quote_url"

# Capture body + status code separately so a non-2xx response still
# leaves a useful diagnostic in the smoke log (curl -f would discard it).
http_out="$(curl -sS -o /tmp/smoke_resp.json -w '%{http_code}' "$quote_url")" \
    || fail "curl failed for $quote_url"
resp="$(cat /tmp/smoke_resp.json)"
rm -f /tmp/smoke_resp.json
if [[ "$http_out" != "200" ]]; then
    fail "GET $quote_url returned HTTP $http_out: $resp"
fi

# Naïve JSON parse — grep "lastCents":<digits>.  jq isn't a hard
# dependency for the smoke test.
if ! echo "$resp" | grep -Eq '"lastCents"[[:space:]]*:[[:space:]]*[1-9][0-9]*'; then
    fail "no positive lastCents in response: $resp"
fi

echo "[smoke] OK — core-service returned: $resp"
