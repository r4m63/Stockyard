# TASK-011: Pipeline integration + DevPriceFixture retirement

## Meta
- ID: TASK-011
- Created: 2026-05-11T18:00:00Z
- Last updated: 2026-05-11T18:00:00Z
- Stage: architect-done
- Touched roles: architect

## Original Request
TASK-008 — quotes pipeline (Driver + Quotes Service + WS), декомпозиция на 4 подзадачи. Эта подзадача — финальная интеграция всех 3 компонентов в docker-compose, e2e smoke, выпиливание `DevPriceFixture`.

## Pipeline Context
Финальная подзадача из 4. Запускается **после** TASK-008/009/010 backend-done. Без неё `DevPriceFixture` остаётся в проде, а Quotes Service не подключён к остальной системе.

```
   TASK-008 ──┐
              ├─▶ TASK-009 ──┐
              │              │
              │              ├─▶ TASK-011 ← THIS TASK
              │              │
   TASK-010 ──┘──────────────┘
```

## Architect Design

### Affected components

- **EXTEND** `deploy/docker-compose.yml`:
  - Добавить `quotes-service` (build из `quotes-service/Dockerfile`, depends_on: redis, clickhouse, mount `/dev/stockyard`).
  - Healthcheck → `/healthz`.
- **MODIFY** `core-service/.../quotes/DevPriceFixture.kt` — **удалить полностью**.
- **MODIFY** `core-service/.../Application.kt` — убрать wire-up DevPriceFixture, CH-параметр. Оставить `QuotesPort.getQuote` (читает HGETALL, теперь данные пишет Quotes Service).
- **MODIFY** TASK-010 stub-publisher в Core — удаляется вместе с DevPriceFixture.
- **MODIFY** docs:
  - `docs/architecture/05-communication.md` §5.3.3 — endpoint `/v1/ws/quotes` (не `/v1/ws`), полный список frames, error codes.
  - `docs/architecture/05-communication.md` §5.5.2 — JSON payload в cents (ADR-011).
  - `docs/architecture/12-storage-operations.md` §12.2.0 — заменить «DevPriceFixture dual-writer» на «Quotes Service is the writer».
  - `docs/architecture/adr/README.md` — index ADR-010..014.
- **NEW** `deploy/scripts/load_driver.sh` — host-side: `make -C driver/`, `sudo insmod`, `sudo mknod`, `sudo chmod`. Idempotent.
- **MODIFY** `CHANGELOG.md` `[Unreleased]` + release `v0.7.0`.
- **MODIFY** `VERSION` → `0.7.0` через `/committer release`.

### API contract changes
Контракт не меняется — он же lift'ится в общую документацию.

Изменения в §5.5.2:
```
PUBLISH channel:quotes:SBER  '{"ticker":"SBER","ts":"...","tsNs":...,"bidCents":28550,"askCents":28570,"lastCents":28560,"volume":12345}'
HSET    quotes:SBER          ts "..."  ts_ns ...  bid 28550  ask 28570  last 28560  volume 12345
XADD    stream:quotes        MAXLEN ~ 100000  *  ticker SBER  ts_ns ...  bid 28550  ask 28570  last 28560  volume 12345
```

Изменения в §5.3.3:
- endpoint: `/v1/ws/quotes` (не `/v1/ws`).
- frame `quote` с `bidCents`/`askCents`/`lastCents`/`volume` (cents Long), не Decimal.
- error code list expanded (`SUBSCRIPTION_LIMIT`, `INVALID_FRAME`).

### Data model changes
Никаких. Все ключи/таблицы существуют, перестаёт писать только `DevPriceFixture`.

### Implementation steps

**Backend (integrator: Kotlin + docker-compose + sh):**

| # | Шаг | Файлы |
|---|---|---|
| 1 | Добавить service `quotes` в `docker-compose.yml`. Depends_on: redis, clickhouse. Mount `/dev/stockyard`. Healthcheck. | `deploy/docker-compose.yml` |
| 2 | `deploy/scripts/load_driver.sh` — host-side: `make -C driver/`, `sudo insmod`, `sudo mknod`, `sudo chmod`. Idempotent. | `deploy/scripts/load_driver.sh` |
| 3 | Удалить `core-service/.../quotes/DevPriceFixture.kt`. | — |
| 4 | `core-service/.../Application.kt` — убрать создание DevPriceFixture + CH-параметр из конструктора. | `Application.kt` |
| 5 | Удалить stub-publisher из DevPriceFixture (если он не удалился вместе с шагом 3). | — |
| 6 | Update `05-communication.md` §5.3.3 endpoint name + frame schema cents. | `docs/architecture/05-communication.md` |
| 7 | Update §5.5.2 JSON payload в cents. | `docs/architecture/05-communication.md` |
| 8 | Update `12-storage-operations.md` §12.2.0 — Quotes Service replaces DevPriceFixture. | `docs/architecture/12-storage-operations.md` |
| 9 | Update `adr/README.md` — index ADR-010..014. | `docs/architecture/adr/README.md` |
| 10 | E2E smoke `deploy/scripts/smoke_quotes.sh`: load driver → docker-compose up → wait healthchecks → `websocat`/Python WS subscribe SBER → expect tick within 5s. | `deploy/scripts/smoke_quotes.sh` |
| 11 | Update `HOWTO.md` (если описывает setup) — driver-loading section. | `HOWTO.md` |
| 12 | CHANGELOG `[Unreleased]`: added `/v1/ws/quotes`, added Quotes Service, retired DevPriceFixture. | `CHANGELOG.md` |

**Tester:**

| # | Шаг |
|---|---|
| T1 | E2E smoke: driver → quotes → redis → gateway → WS клиент получает quote в < 2s. |
| T2 | E2E: `rmmod` driver → Quotes Service reopen-loop, WS клиенты не получают новых quotes но соединение живо. `insmod` обратно — поток восстанавливается. |
| T3 | E2E: убитый Quotes Service → новых тиков нет, но HGETALL `quotes:SBER` отдаёт последние данные. REST `/v1/quotes/SBER` отвечает 200 со stale данными. Restart Quotes Service → новые тики. |
| T4 | E2E: убитый Redis → Quotes Service не падает (reconnect), WS клиенты получают 1011, переподключаются (S8). |
| T5 | E2E: load-simulator 10k клиентов с WS subscribe → p95 от tick-gen до client-receive < 500ms (S9). |
| T6 | Regression: `GET /v1/quotes/SBER` REST остаётся работать (HGETALL Redis → Quotes пишет). |
| T7 | Regression: `GET /v1/quotes/SBER/history` после 10 мин Quotes Service возвращает свечи (MV агрегировал). |
| T8 | Regression: `GET /v1/portfolio` показывает `currentPriceCents` из Redis HASH. |
| T9 | Cold start: clean stand → driver load → quotes up → gateway up → first WS subscribe — нет stale, нет 503. |
| T10 | Regression: POST `/v1/orders` BUY SBER → 201 с реальным `priceCents` из Redis (Quotes Service-supplied). |

**Reviewer:**
- `DevPriceFixture` файл полностью удалён, imports почищены, build OK.
- Документация sync с реализацией: §5.3.3 endpoint, §5.5.2 cents, ADR index.
- CHANGELOG пополнен.
- Driver loading script проверен (хотя бы документирован).
- VERSION → 0.7.0 через `/committer release`.

### ADR
**ADR-015 (NEW, опц.): Driver loading is operator responsibility, not docker init container.**
- Context: kernel module нельзя загрузить из container без `--privileged` + `/lib/modules` mount + kernel headers матчинг.
- Decision: docker-compose не грузит driver; `deploy/scripts/load_driver.sh` для host-side. Quotes Service mount'ит `/dev/stockyard` снаружи.
- Alternatives: privileged init container — security smell для учебного проекта.
- Consequences: demo-инструкция: «sudo ./load_driver.sh, потом docker-compose up».

### Risks с митигациями
| Риск | Likelihood | Impact | Митигация |
|---|---|---|---|
| Удаление DevPriceFixture ломает orders (нет данных в Redis) | Medium | High | T10 проверяет; Quotes Service должен быть запущен до BUY/SELL. Docker-compose `depends_on` + healthcheck. |
| Driver не грузится на Apple Silicon/NixOS | High | Medium | README с альтернативой: «driver simulator» (Python + named pipe). Документируем, не реализуем в MVP. **needs clarification:** делать ли driver simulator? |
| MV `quotes_candles_1m` имеет gap во время перехода | Medium | Low | OK для учебного проекта. Если важно — transition план: запустить Quotes Service параллельно с fixture'ом на 1 мин, потом убрать. Не делаем в MVP. |
| WS endpoint name change `/v1/ws → /v1/ws/quotes` ломает существующих клиентов | Low | Low | До v0.7.0 WS не было. |

### Estimated complexity: **SMALL-MEDIUM**
2–3 ч/дня (cleanup + docs + e2e smoke). Зависит от того, насколько чисто отделится driver-loading на чужих машинах.

### Suggested next role
`/backend TASK-011` (integrator-ориентированный, знает docker-compose).
После backend → `/tester TASK-011` (e2e smoke + regression).
После tester → `/reviewer TASK-011`.
После reviewer → `/committer TASK-011` + `/committer release auto` (→ v0.7.0).

## Handoff Log
- 2026-05-11T18:00:00Z: создан через /architect — design complete; suggested next: `/backend TASK-011` (после backend-done всех TASK-008/009/010).
