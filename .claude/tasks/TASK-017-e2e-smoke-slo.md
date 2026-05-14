# TASK-017: e2e smoke script + SLO run script

## Meta
- ID: TASK-017
- Created: 2026-05-14T15:05:00Z
- Last updated: 2026-05-14T15:10:00Z
- Stage: backend-done
- Touched roles: architect, backend

## Original Request
Завершающая задача MVP-плана: e2e smoke прогон + SLO run скрипт. Без них «работоспособный MVP» неверифицируем.

## Architect Design

### Affected components
- `deploy/scripts/smoke_e2e.sh` (новый) — curl-based; полный пользовательский флоу.
- `deploy/scripts/slo_run.sh` (новый) — bootstrap + load-sim + лёгкая assertion.

### API contract changes
- Нет.

### Data model changes
- Нет.

### Implementation steps
1. `smoke_e2e.sh`:
    - docker compose up -d
    - wait `/health/ready`
    - register / login → JWT
    - GET /v1/instruments, /v1/quotes/SBER
    - POST /v1/orders BUY, GET /v1/portfolio
    - POST /v1/orders SELL
    - POST /v1/accounts/deposit
    - GET /v1/transactions → проверка типа BUY,DEPOSIT,SELL.
    - exit 0 при всех пасах.
2. `slo_run.sh USERS HOLD_SECONDS`:
    - docker compose up -d
    - docker compose --profile sim run --rm load-simulator
    - tee output
    - soft-assert регистраций >= USERS/2.

### ADRs referenced
- Нет новых.

### Risks
- Quote-fixture не успевает прогреть Redis на cold-start — 5-сек grace в smoke (loop) митигейтит.
- Per-IP rate limit может зарубить smoke (несколько последовательных POST), но default 50 rps/IP × 1 sec window — smoke делает ~10 POST за 5 сек, fits.
- Аутентификация: Argon2 на register CPU-bound, может занять ~500ms — smoke не таймаутит до 10s default curl request timeout.

### Suggested complexity: SMALL (2 файла, ~280 строк bash)
### Suggested next: ручной прогон скриптов /committer

## Backend Implementation

### Files changed
- `deploy/scripts/smoke_e2e.sh` (new, 130 LOC)
- `deploy/scripts/slo_run.sh` (new, 85 LOC)

### Key decisions
- Smoke использует **public gateway endpoints** только (в отличие от smoke_quotes.sh, который ходит в /internal core'а) — целевой профиль смока — простор интеграционной валидации, а не диагностика.
- Smoke требует `jq`. Если не установлено — fail-fast в `need jq`. Альтернатива — grep'ом по полям, но менее надёжно.
- SLO run **не выполняет** SLO assertions (p99 < 200ms, error rate < 1%) — это требует Prometheus query API + `promql` instant queries. Перенесено в Backlog: задача архитектора в /next-итерации.
- В compose `load-simulator` есть `depends_on gateway: { condition: service_healthy }` — `slo_run.sh` ждёт его перед `compose run`.

### API endpoints implemented
- Нет.

### SQL migrations
- Нет.

### Open questions
- На macOS dev без `/dev/stockyard` — DevPriceFixture включён (TASK-013), значит smoke работает. На Linux + driver запустить `STOCKYARD_QUOTES_SOURCE=driver docker compose --profile quotes up` ДО smoke'а. Документировано в smoke header.

## Tests
*(не применимо — это сами тесты)*

## Review
*(заполнит /reviewer)*

## Handoff Log
- 2026-05-14T15:05:00Z: /architect — план из /plan.
- 2026-05-14T15:10:00Z: /backend — 2 скрипта готовы; bash syntax OK (`bash -n`). Suggested next: /committer + ручной прогон.
