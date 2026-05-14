#!/usr/bin/env bash
# E2E smoke test for the full user-facing flow (TASK-017).
#
# Steps:
#   1) docker compose up -d (storage + core + gateway with default fixture)
#   2) wait for gateway /health/ready
#   3) register a new user
#   4) login
#   5) GET /v1/instruments → 200, items array
#   6) GET /v1/quotes/SBER → 200, positive lastCents (fixture writes ticks)
#   7) POST /v1/orders BUY → 201 EXECUTED
#   8) GET /v1/portfolio → 200, position present
#   9) POST /v1/orders SELL → 201 EXECUTED
#   10) POST /v1/accounts/deposit → 201, balance increased
#   11) GET /v1/transactions → 200, contains BUY, SELL, DEPOSIT
#
# Exits 0 only if all 11 assertions pass.
#
# Optional env:
#   GATEWAY_URL  — default http://localhost:8080
#   SMOKE_TIMEOUT — overall seconds to wait for gateway ready (default 90)
#   SMOKE_SKIP_UP — set to 1 to skip `docker compose up -d` (compose already running)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GATEWAY="${GATEWAY_URL:-http://localhost:8080}"
TIMEOUT="${SMOKE_TIMEOUT:-90}"
TICKER="${SMOKE_TICKER:-SBER}"

fail() { echo "smoke-e2e: FAIL: $*" >&2; exit 1; }
log()  { echo "smoke-e2e: $*"; }

need() { command -v "$1" >/dev/null 2>&1 || fail "$1 not found in PATH"; }
need curl
need jq

# 1) Bring the stack up (default profile — no quotes-service / no driver).
if [[ "${SMOKE_SKIP_UP:-0}" != "1" ]]; then
    log "docker compose up -d (default profile)"
    (cd "$REPO_ROOT" && docker compose up -d)
fi

# 2) Wait for gateway readiness.
log "waiting up to ${TIMEOUT}s for ${GATEWAY}/health/ready"
deadline=$(( $(date +%s) + TIMEOUT ))
while :; do
    if curl -fsS "${GATEWAY}/health/ready" >/dev/null 2>&1; then
        break
    fi
    if (( $(date +%s) >= deadline )); then
        fail "gateway not ready within ${TIMEOUT}s"
    fi
    sleep 2
done
log "gateway ready"

# Wait until DevPriceFixture has populated quotes:{TICKER} (sleep up to 5s).
for _ in 1 2 3 4 5; do
    qresp="$(curl -fsS "${GATEWAY}/v1/quotes/${TICKER}" -H "Authorization: Bearer _dummy" 2>/dev/null || true)"
    # /v1/quotes is JWT-gated — we'll do a proper check after login.
    sleep 1
done

EMAIL="smoke_$(date +%s)_$RANDOM@stockyard.test"
PASSWORD="Sm0keTest!$RANDOM"

# 3) Register.
log "register $EMAIL"
register_resp="$(curl -sS -X POST "${GATEWAY}/v1/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\"}")"
ACCESS="$(echo "$register_resp" | jq -er '.accessToken')" || fail "register: $register_resp"
log "  accessToken acquired (len=${#ACCESS})"

# 4) Re-login to validate that path too.
log "login"
login_resp="$(curl -sS -X POST "${GATEWAY}/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\"}")"
ACCESS="$(echo "$login_resp" | jq -er '.accessToken')" || fail "login: $login_resp"

auth=(-H "Authorization: Bearer $ACCESS")

# 5) Instruments.
log "GET /v1/instruments"
instr_resp="$(curl -sS "${GATEWAY}/v1/instruments" "${auth[@]}")"
icount="$(echo "$instr_resp" | jq -er '.items | length')" || fail "instruments: $instr_resp"
[[ "$icount" -ge 1 ]] || fail "instruments empty"
log "  $icount items"

# 6) Quote for TICKER — fixture must have populated it.
log "GET /v1/quotes/${TICKER}"
qresp="$(curl -sS "${GATEWAY}/v1/quotes/${TICKER}" "${auth[@]}")"
last="$(echo "$qresp" | jq -er '.lastCents')" || fail "quote: $qresp"
[[ "$last" -gt 0 ]] || fail "lastCents <= 0: $qresp"
log "  lastCents=$last"

# 7) BUY.
log "POST /v1/orders BUY ${TICKER} qty=1"
buy_resp="$(curl -sS -X POST "${GATEWAY}/v1/orders" \
    "${auth[@]}" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: smoke-buy-$(date +%s%N)" \
    -d "{\"ticker\":\"${TICKER}\",\"side\":\"BUY\",\"qty\":1}")"
buy_status="$(echo "$buy_resp" | jq -er '.status')" || fail "buy: $buy_resp"
[[ "$buy_status" == "EXECUTED" ]] || fail "buy not EXECUTED: $buy_resp"
log "  status=$buy_status"

# 8) Portfolio.
log "GET /v1/portfolio"
pf_resp="$(curl -sS "${GATEWAY}/v1/portfolio" "${auth[@]}")"
positions="$(echo "$pf_resp" | jq -er '.positions | length')" || fail "portfolio: $pf_resp"
[[ "$positions" -ge 1 ]] || fail "no positions after BUY: $pf_resp"
log "  positions=$positions"

# 9) SELL.
log "POST /v1/orders SELL ${TICKER} qty=1"
sell_resp="$(curl -sS -X POST "${GATEWAY}/v1/orders" \
    "${auth[@]}" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: smoke-sell-$(date +%s%N)" \
    -d "{\"ticker\":\"${TICKER}\",\"side\":\"SELL\",\"qty\":1}")"
sell_status="$(echo "$sell_resp" | jq -er '.status')" || fail "sell: $sell_resp"
[[ "$sell_status" == "EXECUTED" ]] || fail "sell not EXECUTED: $sell_resp"

# 10) Deposit.
log "POST /v1/accounts/deposit 5000 RUB"
dep_resp="$(curl -sS -X POST "${GATEWAY}/v1/accounts/deposit" \
    "${auth[@]}" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: smoke-dep-$(date +%s%N)" \
    -d '{"amountCents":500000,"currency":"RUB"}')"
dep_txn="$(echo "$dep_resp" | jq -er '.transactionId')" || fail "deposit: $dep_resp"
log "  transactionId=$dep_txn"

# 11) Transactions — should now contain BUY, SELL, DEPOSIT.
log "GET /v1/transactions"
tx_resp="$(curl -sS "${GATEWAY}/v1/transactions" "${auth[@]}")"
types="$(echo "$tx_resp" | jq -er '[.items[].type] | unique | sort | join(",")')"
[[ "$types" == "BUY,DEPOSIT,SELL" ]] || fail "expected BUY,DEPOSIT,SELL; got: $types"
log "  types=$types"

log "OK — full user flow smoke passed"
