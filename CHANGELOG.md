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

### Changed

### Deprecated

### Removed

### Fixed

### Security

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

[Unreleased]: https://github.com/r4m63/Stockyard/compare/v0.6.0...HEAD
[0.6.0]: https://github.com/r4m63/Stockyard/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/r4m63/Stockyard/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/r4m63/Stockyard/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/r4m63/Stockyard/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/r4m63/Stockyard/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/r4m63/Stockyard/releases/tag/v0.1.0
