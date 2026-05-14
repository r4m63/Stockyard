# Changelog

All notable changes to **Stockyard** are documented here.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and the project adheres to [Semantic Versioning 2.0](https://semver.org/spec/v2.0.0.html).

> **Pre-1.0 notice.** Stockyard находится в стадии разработки (`0.x.y`). API, схемы данных и архитектурные решения могут меняться между **минорными** релизами. Стабильность гарантируется только начиная с `1.0.0` (планируется к финальной защите курса).

## How this file is maintained

- Записи накапливаются в секции **`[Unreleased]`** при каждом `/committer` после code-коммитов.
- При вызове `/committer release patch|minor|major|auto` секция `[Unreleased]` фиксируется как `[X.Y.Z] - YYYY-MM-DD`, создаётся git-tag `vX.Y.Z`.
- Категории — стандартные Keep a Changelog: **Added**, **Changed**, **Deprecated**, **Removed**, **Fixed**, **Security**.
- Только **user-visible** изменения. Внутренние рефакторинги, тесты, форматирование — НЕ попадают в Changelog.

---

## [Unreleased]

### Added
- `deploy/scripts/smoke_e2e.sh` — комплексный e2e smoke: docker compose up → register → login → instruments → quote → BUY → portfolio → SELL → deposit → transactions. Все 11 проверок должны пройти, иначе exit 1. Использует только публичные эндпойнты gateway (в отличие от `smoke_quotes.sh`, который смотрит на `/internal/` core'а). (TASK-017)
- `deploy/scripts/slo_run.sh [USERS] [HOLD]` — bootstrap-and-load-sim runner. Поднимает стек, запускает load-simulator через `docker compose --profile sim run --rm`, tee'ит метрики в `/tmp/slo_run.out`, делает soft-assert: зарегистрировано минимум `USERS/2`. Полные SLO-assertions (p99 < 200ms через Prometheus query API) — Backlog. (TASK-017)
- **Load Simulator (Kotlin) — четвёртый микросервис, закрывающий ТЗ §2 п.5.** Standalone gradle project `load-simulator/`. Сценарий одного виртуального юзера: register → WS subscribe → loop `place BUY|SELL → deposit (раз в 10) → portfolio (раз в 5)` с рандомным jitter. Линейный ramp до N юзеров, hold, graceful drain. Метрики (counters + p50/p95/p99) печатаются в stdout каждые SIM_PRINT_SECONDS. Конфиг env-based: `SIM_USERS`, `SIM_GATEWAY_URL`, `SIM_RAMP_SECONDS`, `SIM_HOLD_SECONDS`. В docker-compose под `profiles: ["sim"]` — запускается явно через `docker compose --profile sim up load-simulator`. (TASK-016)
- **`GET /health/startup`** на gateway и core — отдельный probe для k8s startupProbe. Gateway: 200 iff Redis + `QuotesSubscriber.psubscribe(channel:quotes:*)` готов; core: 200 iff PG + Redis. До этого момента — 503 (даёт init-контейнерам грейс-период). (TASK-015)
- **Per-IP rate limit на gateway** (Ktor `RateLimitPlugin`, ADR-012): sliding-counter через Redis `INCR + EXPIRE` на ключах `ratelimit:ip:{ip}:{epochSec}`, default 50 rps/IP (override `RATELIMIT_PER_IP`). Skip `/health`, `/metrics`, `/v1/ws`. Заголовки `RateLimit-Limit / Remaining / Reset` (IETF draft). При превышении 429 `{error:{code:"RATE_LIMITED"}}` + `Retry-After`. Fail-open при недоступности Redis (с WARN в логи). (TASK-015)
- **`POST /v1/accounts/deposit`** — JWT-gated пополнение счёта пользователя. Тело `{amountCents, currency}`, обязателен заголовок `Idempotency-Key` (ADR-005-pattern). Возвращает 201 `{transactionId, balanceCents, currency}`. 422 `INVALID_AMOUNT` при `amountCents <= 0`. Атомарная транзакция в core: replay-short-circuit ДО `FOR UPDATE` на счёте, SQLState 23505 fallback при race. (TASK-014)
- **`GET /v1/transactions?limit=&cursor=`** — JWT-gated история всех денежных движений пользователя (DEPOSIT/BUY/SELL audit). Keyset-курсор `base64(epochSec.nano:id)` по `(created_at DESC, id DESC)`. (TASK-014)
- Migration V8: `idempotency_key TEXT` на `transactions` + partial UNIQUE `(user_id, type, idempotency_key) WHERE idempotency_key IS NOT NULL`. Mirror ADR-005 для deposit; type-scoped key namespace во избежание коллизий с order keys. (TASK-014)
- **OpenTelemetry pipeline в docker-compose** — сервисы `otel-collector` (OTLP gRPC :4317 / HTTP :4318) и `jaeger` (UI :16686). Конфиг коллектора в `deploy/otel/collector-config.yaml`: OTLP receiver → batch processor → Jaeger exporter (traces only; metrics идут в Prometheus через экспортеры). Core и Gateway получили `depends_on otel-collector`. Трейсинг по-прежнему опт-ин (`OTEL_SDK_DISABLED=false`) — fast cold-start сохранён. (TASK-013)
- **`DevPriceFixture` re-introduced под флагом `STOCKYARD_QUOTES_SOURCE=fixture`** (по умолчанию в compose). Synthetic writer того же контракта что и Quotes Service: `quotes:{ticker}` HSET + `channel:quotes:{ticker}` PUBLISH + batch INSERT в ClickHouse `quotes_ticks`. Решает проблему macOS-разработки без `/dev/stockyard` (ADR-010). Для Linux + driver: `STOCKYARD_QUOTES_SOURCE=driver docker compose --profile quotes up`. Параметры: `STOCKYARD_FIXTURE_INTERVAL_SEC=1`, `STOCKYARD_FIXTURE_JITTER_PCT=0.4`. На старте фикстуры в логи пишется WARN — случайный запуск в prod виден сразу. (TASK-013)
- WebSocket эндпоинт `wss://<gateway>/v1/ws/quotes?token=<JWT>` — потоковая раздача котировок мобильным клиентам. Inbound JSON-фреймы `subscribe / unsubscribe / ping`; outbound `quote / subscribed / unsubscribed / pong / error`. Payload `quote` использует integer cents (`bidCents/askCents/lastCents`, `tsNs`, `volume`) по ADR-011. Контракт надёжности: heartbeat 30s, idle close 60s (1008), max 100 тикеров на соединение (`SUBSCRIPTION_LIMIT`), max 5 соединений на пользователя (close 4002). При подписке клиент получает текущий snapshot из Redis `HGETALL quotes:{ticker}` **до** `subscribed`-ack. (TASK-010)
- Redis Pub/Sub bridge `QuotesSubscriber` — единая `psubscribe channel:quotes:*` (ADR-013) с in-process реверс-индексом `Map<Ticker, Set<Conn>>` и DROP_OLDEST backpressure на per-conn `Channel<Frame>(256)` (ADR-001 at-most-once). Defensive re-`psubscribe` после Lettuce reconnect через `RedisConnectionStateAdapter` — pattern subscriptions не восстанавливаются автоматически в Lettuce 6.x. (TASK-010)
- 7 OTel метрик WS-подсистемы: gauge `ws_active_connections` + counters `ws_subscriptions_total`, `ws_frames_sent_total{type}`, `ws_frames_dropped_backpressure_total{type}`, `redis_pubsub_messages_received_total`, `redis_pubsub_parse_errors_total`, `ws_shutdown_leaked_total{reason=timeout|send_failed}`. (TASK-010)
- **Quotes Service интегрирован в `docker-compose.yml`** — сервис `quotes-service` (build из `./quotes-service`), bind-mount `/dev/stockyard`, `depends_on` redis/clickhouse `service_healthy`, проброс на хост 8082→8080. Healthcheck через вспомогательный distroless-friendly бинарь `/healthcheck` (HTTP probe `/healthz`). (TASK-011)
- `deploy/scripts/load_driver.sh` — host-side загрузчик kernel-модуля `stockyard_driver`: build → insmod → создание `/dev/stockyard` → `chmod 0666` для доступа из контейнера. Идемпотентен. Запускается **до** `docker compose up` (см. ADR-015 — driver loading вне контейнера). (TASK-011)
- `deploy/scripts/smoke_quotes.sh` — e2e smoke: проверяет `/dev/stockyard`, `docker compose up -d`, ждёт quotes-service/gateway healthy, валидирует `GET /v1/quotes/SBER` → 200 с положительным `lastCents`. (TASK-011)
- `quotes-service/cmd/healthcheck` — миниатюрный Go-бинарь для Docker HEALTHCHECK в distroless-образе (нет shell/curl/wget). 4 unit-теста против `httptest.Server` покрывают 200/503/connection-refused/timeout ветки. (TASK-011)
- ADR-015 «Stockyard driver грузится на хосте, а не из docker-init-контейнера» — зафиксировано почему `deploy/scripts/load_driver.sh` (host root) предпочтительнее privileged init-container'а и udev auto-load для учебного MVP. (TASK-011)
- Gradle wrapper 8.10.2 в `gateway-service/` и `core-service/` (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.{jar,properties}`). Версия Gradle теперь пинится в репо — `./gradlew compileKotlin` запускается на dev box независимо от системной установки. Dockerfile'ы обоих сервисов переключены с `gradle:8.10-jdk21-alpine` на `eclipse-temurin:21-jdk-alpine` + `./gradlew buildFatJar` для reproducible сборки внутри контейнера. (TASK-012)

### Changed
- CallLogging форматтер маскирует `?token=<jwt>` в логах как `?token=REDACTED` — закрывает leak surface для WS handshake на `/v1/ws/quotes` (ADR-014, JWT в query-параметре с short TTL). (TASK-010)
- Документация архитектуры синхронизирована с реализацией: §5.3.3 endpoint `/v1/ws/quotes` (не `/v1/ws`), frame schema на integer cents + полный список error-кодов; §5.5.2 PUBLISH/HSET/XADD payload в cents; §12.2.0 — единственный writer `quotes:*` теперь Quotes Service. (TASK-011)

### Deprecated

### Removed
- `DevPriceFixture` (core-service) и его конфиг-блок `stockyard.devFixture.*` + env-vars `STOCKYARD_DEV_FIXTURE`, `STOCKYARD_DEV_FIXTURE_INTERVAL_SEC`, `STOCKYARD_DEV_FIXTURE_JITTER_PERCENT`. Единственный writer `quotes:{ticker}` HASH + `channel:quotes:*` PUBLISH + `stream:quotes` + ClickHouse `quotes_ticks` теперь Quotes Service. Core Service читает котировки только через `QuotesPort.getQuote`. Операторам, державшим `STOCKYARD_DEV_FIXTURE=true` в локальном `.env`, нужно убрать переменные — Core стартует без них. (TASK-011)

### Fixed
- Gateway-сервис собирается локально с Ktor 2.3.13 / Kotlin 2.0 / commons-pool2 2.12: исправлены 4 pre-existing scaffold-бага TASK-003 (неверный пакет `io.ktor.server.plugins.calllogging` → `callloging`, устаревшие `WebSockets.pingPeriod/timeout` → `pingPeriodMillis/timeoutMillis`, `GenericObjectPoolConfig.maxWait = ...` → `setMaxWait(...)`, `Application.monitor` → `environment.monitor`) и 2 unclosed KDoc-комментария с литералом `*/`, а также добавлены пропущенные `import io.ktor.server.application.call` в 9 routing-файлах. (TASK-010)

### Security
- Access-логи Gateway больше не содержат полный JWT при подключении к `/v1/ws/quotes`. Регулярное выражение `([?&])token=[^&]*` подменяет значение токена на `REDACTED`. Митigация для leak surface через прокси-логи и kube-audit. (TASK-010)

---

## [0.7.0] - 2026-05-12

### Added
- C Linux kernel module `/dev/stockyard` — имитатор биржи, выдаёт packed 44-байтовые `struct stockyard_tick` через character device по конфигурируемому таймеру (1..1000 Hz). Конфигурация runtime через 4 ioctl: `SET_TICKERS` (до 64 тикеров с initial-cents + volatility-bps), `SET_RATE_HZ`, `GET_STATS`, `RESET`. Драйвер уважает `O_NONBLOCK`/`poll`/`select`, реализует drop-oldest на overflow kfifo (8192 тиков), пишет статистику. Userspace harness (`test_read`/`test_ioctl`/`test_layout`/`test_errors`) + seed на 50 MOEX-тикеров + Apple Silicon Lima VM конфиг + загрузочные скрипты. Foundation для TASK-009 Quotes Service. (TASK-008)
- **Quotes Service** (Go) — новый микросервис из ТЗ §2 п.6: читает packed-binary tick stream из `/dev/stockyard`, фанит в Redis (`HSET quotes:{ticker}` + `PUBLISH channel:quotes:{ticker}` атомарно через TxPipeline + `XADD stream:quotes` MAXLEN ~100k) и батчит в ClickHouse `quotes_ticks` (1000 ticks OR 1s, 3× retry с backoff). JSON payload в Redis Pub/Sub — integer cents (`bidCents`/`askCents`/`lastCents`/`volume`) + ISO-8601 `ts` + `tsNs` uint64 по ADR-011. Asymmetric backpressure (Redis drop-on-full, CH blocking) per ADR-001. Health endpoints `/healthz`/`/readyz`/`/metrics` на порту 8080. Восемь Prometheus counters: `stockyard_quotes_ticks_total`, `_ticks_dropped_redis_total`, `_ticks_dropped_ch_total`, `_redis_publish_errors_total`, `_ch_batch_errors_total`, `_ch_rows_inserted_total`, `_ch_rows_dropped_total`, `_driver_reopens_total`. Docker image: multi-stage distroless nonroot. (TASK-009)

---

## [0.6.0] - 2026-05-11

### Added
- `GET /v1/portfolio` — баланс и список открытых позиций по JWT-пользователю. Каждая позиция обогащается `currentPriceCents` из Redis (`HGETALL quotes:{ticker}`) и `unrealizedPnlCents = (current - avg) * qty`; если котировки нет, поля `currentPriceCents` и `unrealizedPnlCents` приходят `null`. Все денежные поля — `Long` cents. (TASK-007)
- `GET /v1/quotes/{ticker}` — текущая котировка по тикеру (`bidCents`, `askCents`, `lastCents`, `ts`). `404 INSTRUMENT_NOT_FOUND` если тикера нет в каталоге; `422 NO_QUOTE_AVAILABLE` если тикер есть, но котировки пока не записаны. (TASK-007)
- `GET /v1/quotes/{ticker}/history?from=&to=&interval=` — OHLC-свечи из ClickHouse за окно. Поддерживаются `interval=1m` (до 7 дней) и `interval=1h` (до 90 дней); `422 INVALID_INTERVAL` или `422 INVALID_TIME_RANGE` при выходе за границы. (TASK-007)
- `GET /v1/instruments` — каталог из 50 MOEX-тикеров с `name`, `type`, `lotSize`. (TASK-007)
- `DevPriceFixture` теперь дополнительно пишет каждый тик в ClickHouse `quotes_ticks` через JDBC. Materialized views `quotes_candles_1m` / `quotes_candles_1h` начинают наполняться без ожидания TASK-008, поэтому `/v1/quotes/{ticker}/history` отдаёт ненулевые свечи в dev. Удалится одним коммитом, когда Quotes Service возьмёт оба write-канала на себя. (TASK-007)

---

## [0.5.0] - 2026-05-11

### Added
- `POST /v1/orders` — размещение BUY/SELL ордеров по рыночной цене из Redis. Требует `Authorization: Bearer` и `Idempotency-Key`. Атомарное исполнение в одной PostgreSQL-транзакции с `SELECT … FOR UPDATE` на `accounts` (BUY) или `positions` (SELL); audit-запись только на EXECUTED. REJECTED-ордера (недостаток средств/позиции) сохраняются в БД для пользовательской истории, в ответе — `422 INSUFFICIENT_FUNDS|INSUFFICIENT_POSITION` с details `{requiredCents, availableCents}` или `{requiredQty, availableQty}`. Idempotent повтор по тому же ключу возвращает тот же ордер; повтор с другим телом — `409 IDEMPOTENCY_CONFLICT`. (TASK-006)
- `GET /v1/orders?status=&limit=&cursor=` — listing с keyset-пагинацией по `(created_at DESC, id DESC)` через индекс `idx_orders_user_created`. `cursor` — opaque base64. (TASK-006)
- Временный «писатель котировок» `DevPriceFixture` в Core Service: на старте сидит `quotes:{ticker}` HASH для всех 50 тикеров из каталога, каждые 5 сек делает random walk ±0.5%. Отключается через `STOCKYARD_DEV_FIXTURE=false` в prod-like окружении; заместится Quotes Service в TASK-008. (TASK-006)

### Changed

### Deprecated

### Removed

### Fixed

### Security

---

## [0.4.0] - 2026-05-11

### Added
- `POST /v1/auth/register` — регистрация пользователя по email + паролю: Argon2id-хэш с pepper, выдача access (TTL 15 мин) + refresh (TTL 30 дней) JWT-токенов, автоматическое создание RUB-счёта с начальным депозитом 1 000 000,00 ₽. (TASK-005)
- `POST /v1/auth/login` — вход по email + паролю; generic `401 INVALID_CREDENTIALS` (не различает «email не найден» от «неверный пароль») для защиты от user-enumeration. (TASK-005)
- `POST /v1/auth/refresh` — ротация refresh-токена: подпись и срок проверяются, старый `jti` удаляется из Redis, выдаётся новая пара access + refresh. Украденный refresh — одноразовый. (TASK-005)
- Внутренние эндпоинты Core Service: `POST /internal/users` (создание user + accounts в одной PostgreSQL-транзакции) и `POST /internal/auth` (проверка пароля через Argon2id `PasswordHasher`). (TASK-005)

### Changed

### Deprecated

### Removed

### Fixed

### Security

---

## [0.3.0] - 2026-05-11

### Added
- Core Service (Kotlin/Ktor) — internal HTTP сервис на `:8080` (host `:8081`):
  - `GET /health/live` всегда `200 {"status":"UP"}`.
  - `GET /health/ready` проверяет PostgreSQL (`SELECT 1`) и Redis (`PING`) как блокирующие; ClickHouse info-only (DOWN не делает unhealthy).
  - `GET /metrics` — Prometheus exposition: JVM memory/GC/threads, processor, HikariCP pool, Ktor HTTP RED-метрики.
  - 7 internal stub-эндпоинтов (`POST /internal/{users,auth,orders}`, `GET /internal/users/{id}/{orders,portfolio}`, `GET /internal/instruments`, `GET /internal/quotes/{ticker}/history`) возвращают `501 NOT_IMPLEMENTED` в едином формате `{"error":{"code","message","details"}}`. Полная реализация — TASK-005..008. (TASK-004)
- Programmatic Flyway-bootstrap: миграции V1–V7 применяются в `Application.module()` до открытия HTTP-сокета; падение миграции → fail-fast старт. (TASK-004)
- Connection pools для PostgreSQL (HikariCP, 50 соединений, `statement_timeout=3s`, `application_name=core-service` через `connectionInitSql`), Redis (Lettuce + `GenericObjectPool` maxTotal=32, выделенный pub/sub connection), ClickHouse (HikariCP, 8 соединений, готов для TASK-008 SELECT свечей). (TASK-004)

### Changed

### Deprecated

### Removed

### Fixed

### Security

---

## [0.2.0] - 2026-05-11

### Added
- Уровень хранения данных «из коробки»: PostgreSQL 16 + Redis 7 + ClickHouse 24 + Prometheus с postgres/redis exporters поднимаются одной командой `docker compose up -d`. Конфиги, healthchecks, ресурсные лимиты и cron-скрипт бэкапа PG. (TASK-001)
- PostgreSQL-схема: 7 Flyway-миграций в `core-service/src/main/resources/db/migration/` — `users`, `accounts`, `instruments` с DML 50 тикеров MOEX, `orders` с `UNIQUE(user_id, idempotency_key)`, `positions`, `transactions` (audit), индекс `idx_orders_user_ticker`, `pg_stat_statements` + read-only роль `monitoring` для exporter'а. (TASK-001)
- ClickHouse-схема: `quotes_ticks` (MergeTree partition by month + TTL 6 мес.) + Materialized Views `quotes_candles_1m`/`_1h` для свечей. Загружается через `/docker-entrypoint-initdb.d/`. (TASK-001)
- API Gateway (Ktor) — публичный HTTP-сервер на `:8080`:
  - `GET /health/live` всегда `200 {"status":"UP"}`.
  - `GET /health/ready` проверяет Redis (blocking) и Core Service (info-only).
  - WebSocket `/v1/ws` skeleton: `subscribe` / `unsubscribe` / `ping` → JSON-frames `subscribed` / `unsubscribed` / `pong`.
  - 9 stub-эндпоинтов (`/v1/auth/*`, `/v1/orders`, `/v1/portfolio`, `/v1/instruments`, `/v1/quotes/*`) возвращают `501 NOT_IMPLEMENTED` в едином формате `{"error":{"code","message","details"}}`. Полная реализация — TASK-005..008. (TASK-003)

### Changed
- Внутреннее имя сервиса бизнес-логики переименовано: «DB Service» → «Core Service» во всей документации, конфигах и контейнерах (`db-service/` → `core-service/`). Соответствует ТЗ §2.4 «Микросервис для работы с БД» как функциональному описанию роли. Mobile/RN клиенты и публичный REST-контракт **не затронуты**. (TASK-002)

### Deprecated

### Removed

### Fixed

### Security

---

## [0.1.0] - 2026-05-09

### Added
- Архитектурный фундамент: 12 документов в `docs/architecture/` (контекст, структура, компоненты, развёртывание, коммуникация, данные, согласованность, масштабирование, наблюдаемость, сценарии, тестирование).
- 6 архитектурных решений (`docs/architecture/adr/ADR-001` … `ADR-006`).
- `REQUIREMENTS.md` с требованиями курса РМП.
- `HOWTO.md` с описанием ролевого workflow разработки.
- `CLAUDE.md` с контекстом проекта для AI-assisted разработки.
- Конфигурация `.claude/` с 8 слэш-командами (`architect`, `backend`, `frontend`, `mobile`, `tester`, `reviewer`, `committer`, `task`) и системой task ledger.
- Инфраструктура версионирования: `VERSION` + `CHANGELOG.md` (Keep a Changelog 1.1.0).

---

<!--
Compare links — обновляются автоматически /committer release.
Замени <org>/<repo> на реальный путь после публикации репозитория.
-->

[Unreleased]: https://github.com/r4m63/Stockyard/compare/v0.7.0...HEAD
[0.7.0]: https://github.com/r4m63/Stockyard/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/r4m63/Stockyard/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/r4m63/Stockyard/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/r4m63/Stockyard/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/r4m63/Stockyard/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/r4m63/Stockyard/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/r4m63/Stockyard/releases/tag/v0.1.0
