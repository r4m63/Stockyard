#!/usr/bin/env bash
# Source this file before running core-service / gateway-service via ./gradlew run.
#
# Usage:
#   source deploy/scripts/local-env.sh
#   cd core-service && ./gradlew run
#   # (in another terminal)
#   source deploy/scripts/local-env.sh
#   cd gateway-service && ./gradlew run
#
# Assumes infrastructure containers are up via `docker compose up -d` (default
# profile, no `app` / `sim` / `quotes`).

set -a
# Read secrets from the project's .env (PG_PASSWORD, REDIS_PASSWORD, CH_PASSWORD,
# ARGON2_PEPPER, JWT_SECRET, MONITORING_PASSWORD).
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
REPO_ROOT="$( cd "$SCRIPT_DIR/../.." && pwd )"
if [[ -f "$REPO_ROOT/.env" ]]; then
    # shellcheck disable=SC1091
    source "$REPO_ROOT/.env"
fi

# Hostname overrides — containers are reachable on localhost via published ports.
export PG_HOST=localhost
export PG_PORT=5432
export REDIS_URL="redis://localhost:6379"
export CH_HOST=localhost
export CH_PORT=8123
export OTEL_EXPORTER_OTLP_ENDPOINT="http://localhost:4317"

# Port layout for local gradle run:
#   gateway → 8080 (matches smoke_e2e.sh GATEWAY_URL default)
#   core    → 8081 (avoid clash with gateway)
export CORE_PORT=8081
export GATEWAY_PORT=8080
export CORE_SERVICE_URL="http://localhost:8081"

# Dev fixture in core (no Quotes Service in this layout).
export STOCKYARD_QUOTES_SOURCE=fixture

# Tracing off by default (fast cold-start). Set to false to ship traces to
# the running otel-collector container.
export OTEL_SDK_DISABLED="${OTEL_SDK_DISABLED:-true}"
set +a

echo "local-env: loaded. PG=$PG_HOST:$PG_PORT REDIS=$REDIS_URL CORE=$CORE_SERVICE_URL"
echo "local-env: run 'cd core-service && ./gradlew run' or 'cd gateway-service && ./gradlew run'"
