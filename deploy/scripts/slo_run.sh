#!/usr/bin/env bash
# SLO run (TASK-017). Bootstraps stack, runs load-simulator at the requested
# user count, prints final metrics. Light-weight assertions only — full SLO
# validation needs Prometheus queries and is left as Backlog.
#
# Usage:
#   deploy/scripts/slo_run.sh [USERS] [HOLD_SECONDS]
#
# Example:
#   deploy/scripts/slo_run.sh 1000 120
#
# Optional env:
#   SLO_RAMP_SECONDS  — default 30
#   SLO_GATEWAY_URL   — default http://gateway:8080 (inside compose network)
#                       Set to http://localhost:8080 to run sim from host.
#   RATELIMIT_PER_IP  — bump to avoid 429 storms in compose-internal scenarios.
#                       Suggested: 100000 for 10k-user run.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
USERS="${1:-1000}"
HOLD="${2:-120}"
RAMP="${SLO_RAMP_SECONDS:-30}"
GATEWAY="${SLO_GATEWAY_URL:-http://gateway:8080}"

log()  { echo "slo-run: $*"; }
fail() { echo "slo-run: FAIL: $*" >&2; exit 1; }

log "Bootstrapping stack (storage + core + gateway + otel)"
(cd "$REPO_ROOT" && docker compose up -d)

log "Waiting up to 90s for gateway healthcheck"
deadline=$(( $(date +%s) + 90 ))
while :; do
    status="$(docker inspect -f '{{.State.Health.Status}}' stockyard-gateway 2>/dev/null || echo missing)"
    if [[ "$status" == "healthy" ]]; then
        break
    fi
    (( $(date +%s) < deadline )) || fail "gateway not healthy in 90s (last: $status)"
    sleep 3
done

log "Running load-simulator: users=$USERS ramp=${RAMP}s hold=${HOLD}s gw=$GATEWAY"

# Run the simulator under the `sim` profile. Foreground so we can pipe logs.
SIM_USERS="$USERS" \
SIM_RAMP_SECONDS="$RAMP" \
SIM_HOLD_SECONDS="$HOLD" \
SIM_GATEWAY_URL="$GATEWAY" \
SIM_PRINT_SECONDS=10 \
docker compose --profile sim run --rm \
    -e SIM_USERS \
    -e SIM_RAMP_SECONDS \
    -e SIM_HOLD_SECONDS \
    -e SIM_GATEWAY_URL \
    -e SIM_PRINT_SECONDS \
    load-simulator | tee /tmp/slo_run.out

log "SLO run finished. Inspect /tmp/slo_run.out for full metrics."

# Soft assertion: registers must mostly succeed.
ok="$(grep -oE 'count.register.ok=[0-9]+' /tmp/slo_run.out | tail -1 | sed 's/.*=//' || echo 0)"
fail_count="$(grep -oE 'count.register.fail=[0-9]+' /tmp/slo_run.out | tail -1 | sed 's/.*=//' || echo 0)"
log "register: ok=$ok fail=$fail_count"

if [[ "${ok:-0}" -lt $(( USERS / 2 )) ]]; then
    fail "less than half of users registered successfully (ok=$ok / users=$USERS)"
fi

log "OK"
