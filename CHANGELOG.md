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

[Unreleased]: https://github.com/r4m63/Stockyard/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/r4m63/Stockyard/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/r4m63/Stockyard/releases/tag/v0.1.0
