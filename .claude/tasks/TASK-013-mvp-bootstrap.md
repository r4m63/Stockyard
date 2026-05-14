# TASK-013: MVP backend bootstrap (env + OTel + dev fixture)

## Meta
- ID: TASK-013
- Created: 2026-05-14T14:20:00Z
- Last updated: 2026-05-14T14:25:00Z
- Stage: backend-done
- Touched roles: architect, backend

## Original Request
Сделать работоспособный MVP бэкэнда по REQUIREMENTS.md. Текущий вариант можно полностью переделывать.

После /plan архитектор выявил, что backend ~80% готов; MVP-блок — last-mile (env + OTel collector + dev fixture для macOS + deposit/transactions endpoints + Load Simulator + e2e smoke).

TASK-013 — foundation: без него на macOS нельзя поднять стек, без квот в Redis не работает WS, без OTel collector не активируется wire-up SDK.

## Architect Design

### Affected components
- core-service: новый `DevPriceFixture`, чтение `quotesSource` из конфига, wire-up в `Application.module()`.
- core-service config: `application.conf` + `AppConfig.kt` — флаг `stockyard.quotesSource` и блок `devFixture`.
- deploy: новые сервисы `otel-collector` (OTLP gRPC/HTTP) и `jaeger` (UI :16686) в `docker-compose.yml`.
- deploy: `deploy/otel/collector-config.yaml` — receiver OTLP → exporter Jaeger.

### API contract changes
- Нет публичных контрактных изменений. Только переменные окружения:
  - `STOCKYARD_QUOTES_SOURCE` (default `fixture`; `driver` для prod / Linux + `/dev/stockyard`).
  - `STOCKYARD_FIXTURE_INTERVAL_SEC` (default 1).
  - `STOCKYARD_FIXTURE_JITTER_PCT` (default 0.4).

### Data model changes
- Нет.

### Implementation steps
1. **backend**: вернуть `InstrumentRepository.listTickers()` (был удалён в d8c05b2 retire коммите).
2. **backend**: восстановить `DevPriceFixture.kt` (по b325238 за актуальную версию), обновить docstring под флаг `STOCKYARD_QUOTES_SOURCE`.
3. **backend**: добавить `quotesSource` + `devFixture` в `AppConfig` и `application.conf`.
4. **backend**: в `Application.module()` стартовать фикстуру при `quotesSource == FIXTURE`, останавливать на `ApplicationStopping`.
5. **deploy**: добавить `otel-collector` (otel/opentelemetry-collector-contrib:0.103.1) и `jaeger` (jaegertracing/all-in-one:1.57) в `docker-compose.yml`, прописать `depends_on otel-collector` для core и gateway.
6. **deploy**: написать `deploy/otel/collector-config.yaml` — OTLP receiver → Jaeger exporter.
7. **tester**: smoke по docker compose up; проверка `quotes:{ticker}` HASH присутствует в Redis после ~5s; проверка `/v1/quotes/{ticker}` отдаёт 200.

### ADRs referenced
- ADR-010 (новый): source-of-quotes selection (`fixture` vs `driver`).

### Risks
- Drift между фикстурой и реальным Quotes Service — контракт `quotes:{ticker}` фиксирован C2 (TASK-009), фикстура пишет те же поля.
- Случайный запуск фикстуры в prod — фикстура пишется в Redis тех же ключей, что и Quotes Service; запуск обеих = двойная запись. Митигация: WARN-лог на старте фикстуры; default в compose `:-fixture` только в dev. Prod profile должен ставить `STOCKYARD_QUOTES_SOURCE=driver`.

### Suggested complexity: MEDIUM (6 файлов)
### Suggested next: /tester TASK-013 (smoke) → /committer

## Backend Implementation

### Files changed
- `core-service/src/main/kotlin/com/stockyard/core/Application.kt` — wire-up `DevPriceFixture` под флагом, остановка при shutdown.
- `core-service/src/main/kotlin/com/stockyard/core/config/AppConfig.kt` — `QuotesSource` enum, `DevFixtureConfig`, загрузка из HOCON.
- `core-service/src/main/kotlin/com/stockyard/core/domain/instrument/InstrumentRepository.kt` — вернул `listTickers(conn)`.
- `core-service/src/main/kotlin/com/stockyard/core/quotes/DevPriceFixture.kt` — восстановлен (новая docstring + WARN на старте).
- `core-service/src/main/resources/application.conf` — `stockyard.quotesSource` + `stockyard.devFixture` блоки.
- `docker-compose.yml` — `otel-collector`, `jaeger`, передача `STOCKYARD_QUOTES_SOURCE` в core-service, `depends_on` для core/gateway.
- `deploy/otel/collector-config.yaml` — OTLP receiver → Jaeger exporter.

### Key decisions
- Фикстура — synthetic writer того же Redis-контракта что и Quotes Service: `quotes:{ticker}` HSET + `channel:quotes:{ticker}` PUBLISH + batch INSERT в `quotes_ticks`.
- Старт фикстуры — fail-soft по ClickHouse (если CH недоступен, продолжаем писать в Redis); fail-hard по Redis (соединение проверено `RedisModule.health`).
- ClickHouse-вставка опциональна: фикстура работает и без CH в compose (но `quotes_candles_1m/1h` MV в этом случае не наполняются).
- Default `STOCKYARD_QUOTES_SOURCE=fixture` в compose — macOS-friendly. На Linux + driver: `STOCKYARD_QUOTES_SOURCE=driver docker compose --profile quotes up`.
- OTel collector конфиг — minimal: traces only (метрики уже идут в Prometheus через экспортеры).
- `OTEL_SDK_DISABLED` оставлен `:-true` по умолчанию — трассировка опт-ин: `OTEL_SDK_DISABLED=false docker compose up`. Это сохраняет fast cold-start для разработчика.

### API endpoints implemented
- Нет (нет публичных эндпойнтов в задаче).

### SQL migrations
- Нет.

### Open questions
- Нет blocker'ов.

## Tests
*(заполнит /tester)*

## Review
*(заполнит /reviewer)*

## Handoff Log
- 2026-05-14T14:20:00Z: /architect — план составлен (как часть /plan, см. assistant message); foundation для MVP. Suggested next: /backend.
- 2026-05-14T14:25:00Z: /backend — implementation готов, compileKotlin зелёный, docker compose config валиден. Suggested next: /tester (e2e smoke) + /committer.
