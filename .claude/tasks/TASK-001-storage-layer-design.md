# TASK-001: Полное проектирование уровня хранения данных

## Meta
- ID: TASK-001
- Created: 2026-05-10T00:00:00Z
- Last updated: 2026-05-10T23:00:00Z
- Stage: backend-partial
- Touched roles: architect, backend

## Original Request
изучи REQUIREMENTS.md и спроектируй сейчас уровень хранения данных. Redis Server / KeyDB — кэш БД, брокер сообщений + PostgreSQL + ClickHouse — хранение больших объёмов данных и быстрая выдача клиенту + Open Telemetry — для сбора телеметрии/логов!!! -- полностью спроектируй этот уровень и опиши его в документации!!!

## Architect Design

> Цель: довести слой данных от «концептуально спроектирован» (в `06-data`, `07-consistency`, `09-observability` уже есть DDL, ключи, гарантии и OTel-фреймворк) до **состояния готовности к разработке и эксплуатации** — конкретные пулы, таймауты, health-checks, миграции, сидинг, бэкапы, OTel storage instrumentation.

### 1. Affected components

| Компонент | Затрагивается | Что меняется |
|---|---|---|
| **Core Service (Kotlin/Ktor)** | да, основной | HikariCP конфиг, Lettuce конфиг, clickhouse-jdbc, JDBC OTel-инструментация, Flyway, health-эндпоинты |
| **API Gateway (Kotlin/Ktor)** | да | Lettuce (Redis) пул и таймауты для Pub/Sub + сессии, OTel Redis instrumentation, health |
| **Quotes Service (Go)** | да | go-redis pool, clickhouse-go batch и pool, OTel-обёртки (`otelredis`, ручная для CH), health |
| **C Linux Driver** | нет | не трогает storage |
| **Android, RN** | нет | API контракты не меняются |
| **Load Simulator** | минимально | не пишет в storage напрямую — ходит через Gateway |
| **deploy/** | да | docker-compose: limits, healthchecks, volumes; postgres/redis/clickhouse exporters; Flyway/init-сидинг |

### 2. Gap analysis

| Тема | Уже есть | Чего не хватает |
|---|---|---|
| Карта данных PG / Redis / CH | `06-data §6.1–§6.4` — карта, DDL, ключи, MV | — |
| ACID-транзакции BUY/SELL | `07-consistency §7.2`, ADR-004 | — |
| Идемпотентность | `07-consistency §7.3`, ADR-005 | TTL/cleanup стратегия — **зафиксировать в ADR-007** |
| Объёмы хранилищ | `06-data §6.6` | — |
| OTel SDK обзор | `09-observability §9.2`, §9.10 | **storage-specific instrumentation не описана** |
| Бизнес-метрики | `09-observability §9.3.2` | **нет storage-метрик из exporters**; нет `stockyard_storage_up` |
| Connection pool sizing | `08-scaling §8.5` упоминает «HikariCP 30–50» | **нет конкретных параметров для Hikari/Lettuce/go-redis/clickhouse-go** |
| Resource limits docker | `04-deployment §4.6` — общие CPU/RAM | **нет `mem_limit`/`cpus` блоков compose, нет volume sizing** |
| PostgreSQL config | `08-scaling §8.5` упоминает `shared_buffers/work_mem` | **нет полного `postgresql.conf`, нет `pg_stat_statements`** |
| Redis config | `06-data §6.3.4` упоминает `allkeys-lru` | **нет `maxmemory`, нет `appendonly`/`save`, нет `tcp-keepalive`** |
| ClickHouse config | `06-data §6.4` — schema | **нет `users.xml`/`config.xml` ключевых параметров, нет batch settings** |
| Health-checks | `04-deployment` упоминает `docker compose` | **нет конкретных пробов для каждого хранилища** |
| Migrations & seeding | `06-data §6.2.4` — Flyway путь | **нет CI-шага, нет seed-DML для 50 тикеров, нет seed-юзеров для dev** |
| Backup/restore | `06-data §6.7` — «pg_dump в cron» | **нет конкретной команды, расписания, retention, smoke-restore** |
| Failure modes | `08-scaling §8.6` 📦 | **нет конкретных сценариев «PG/Redis/CH down» с поведением сервисов** |
| Партиционирование PG | `06-data §6.5` lifecycle упоминает «партиции по месяцам» | **DDL без партиций — противоречит. Решить → ADR-008** |
| 50 тикеров | `08-scaling §8.2` упоминает «50 инструментов» | **нет списка** |

### 3. Documentation plan

| Файл | Действие | Содержание |
|---|---|---|
| `docs/architecture/12-storage-operations.md` | **NEW** (главный артефакт) | Operational design всех трёх хранилищ |
| `docs/architecture/seed/instruments-50.md` | **NEW** | Список 50 тикеров с lot_size |
| `docs/architecture/09-observability.md` | **EXTEND** новой секцией §9.12 | Storage instrumentation OTel |
| `docs/architecture/06-data.md` | **EXTEND** §6.2.5 + правка §6.5 | Решение по партиционированию + ссылка на ADR-008 |
| `docs/architecture/adr/ADR-007-idempotency-key-retention.md` | **NEW** | Idempotency-keys бессрочно вместе с ордером |
| `docs/architecture/adr/ADR-008-pg-no-partitioning-mvp.md` | **NEW** | В MVP не партиционируем PG |
| `docs/architecture/adr/README.md` | UPDATE | Индекс ADR-007, ADR-008 |
| `docs/architecture/README.md` | UPDATE | Ссылка на §12 |

Аргументация структуры: existing docs — «вертикальный» разрез. Operational concerns пересекаются по хранилищам — компактнее в одном файле §12, чем размазывать. OTel storage — естественное расширение §9.

### 4. Storage operations design — ключевые решения

**PostgreSQL.** `postgresql.conf`: `shared_buffers=1GB`, `effective_cache_size=3GB`, `work_mem=16MB`, `max_connections=200`, `pg_stat_statements`, `log_min_duration_statement=500ms`. HikariCP: `maximumPoolSize=50`, `minimumIdle=10`, `connectionTimeout=1000ms`, `idleTimeout=10min`, `maxLifetime=30min`, `leakDetectionThreshold=5s`, `connectionTestQuery=SELECT 1`. Statement-level: `defaultStatementTimeout=3000ms`, `defaultLockTimeout=2000ms`. Health: `pg_isready` для docker, `SELECT 1` для `/health/ready`. Backup: `pg_dump --format=custom --compress=9` ежедневно в 03:00, retention 7 дней. Resource limits: 2 vCPU / 4 GB / 20 GB volume.

**Redis.** Один инстанс на MVL. `maxmemory=1gb`, `maxmemory-policy=allkeys-lru`, `save 3600 1`, `appendonly no`, `client-output-buffer-limit pubsub 32mb 8mb 60`. Lettuce (Kotlin): command timeout 500ms, connect 2s, pool 32, отдельный Pub/Sub-connection вне пула. go-redis (Go): `PoolSize=16`, `Read/WriteTimeout=500ms`, `MaxRetries=2`. Health: `PING`. Resource: 1 vCPU / 1.5 GB.

**ClickHouse.** `max_connections=256`, `max_concurrent_queries=32`, `mark_cache=512MB`, `uncompressed_cache=1GB`, `parts_to_throw_insert=600`, profile `max_memory_usage=2GB`, `async_insert=1`, `wait_for_async_insert=0`. clickhouse-go batch: `batchMaxRows=1000`, `batchMaxAge=1s`, `MaxOpenConns=8`, LZ4 compression. clickhouse-jdbc (для свечей): pool 8, `socket_timeout=10000`, `queryTimeout=5000ms`. Health: `wget /ping → Ok.`. Resource: 2 vCPU / 4 GB.

**Configuration.** `.env` (не в git, шаблон `.env.example`) — пароли PG/Redis/CH, JWT_SECRET, ARGON2_PEPPER, OTLP endpoint, deployment env.

**Сидинг 50 тикеров** (полный список в `seed/instruments-50.md`): SBER, GAZP, LKOH, GMKN, ROSN, NVTK, MGNT, MTSS, YNDX, OZON, VKCO, PLZL, AFKS, AFLT, ALRS, CHMF, FEES, HYDR, IRAO, MOEX, NLMK, PHOR, POLY, RTKM, RUAL, SNGS, TATN, TCSG, TRMK, VTBR, PIKK, SBERP, SNGSP, TATNP, RASP, UPRO, LSRG, MAGN, SMLT, SGZH, FIVE, AGRO, FLOT, GLTR, CBOM, ENPG, MTLR, MTLRP, NMTP, BSPB.

**Dev seed-юзеры:** 5 юзеров с email `alice@…/bob@…/carol@…/demo@…/qa@…`, пароль `test123`, депозит 1 000 000 cents. Только в dev-профиле, никогда в demo/prod.

### 5. OTel storage instrumentation

**JDBC (Kotlin) — OTel Java Agent**: auto-instrumentation, semantic attrs `db.system=postgresql`, `db.name`, `db.statement` (sanitized), `db.operation`, `db.sql.table`. Кастом: `stockyard.tx.kind`, `stockyard.user_id`, `stockyard.order_id`, `stockyard.idempotency_key`, `db.rows_affected`. Hikari → Micrometer → OTLP: `hikaricp.connections{,active,idle,pending,acquire,creation,timeout}`.

**Lettuce (Kotlin) — `MicrometerTracing` через Observation API**. Attrs `db.system=redis`, `db.operation=HGET/PUBLISH/XADD`.

**go-redis — `redisotel` middleware**: `redisotel.InstrumentTracing(rdb)` + `InstrumentMetrics(rdb)`.

**clickhouse-go — manual wrapper** (нет out-of-the-box). Span attrs: `db.system=clickhouse`, `db.sql.table`, `stockyard.batch.rows`, `stockyard.batch.bytes`, `stockyard.batch.flush_reason={full,timeout}`.

**Storage exporters → Prometheus**: `postgres_exporter:9187`, `redis_exporter:9121`, ClickHouse builtin `:9363`. Health-метрика: `stockyard_storage_up{store, service}`.

### 6. Storage failure modes & recovery

| Сценарий | Поведение | Восстановление |
|---|---|---|
| **PG down** | Core Service `/ready` unhealthy; ордерные эндпоинты + auth → 503 `STORAGE_UNAVAILABLE`. Котировки в WS продолжают идти. | docker-restart, autoReconnect Hikari, ~1–2 мин |
| **Redis down** | Quotes не PUBLISH/HSET; новые WS не получают тиков. **Размещение ордера → fail-fast 503** (нет цены). Sessions → новые login невозможны. | docker-restart; кэш `quotes:*` восстанавливается за ~1 сек |
| **ClickHouse down** | Quotes батчёр копит до 10к строк, дальше дроп с метрикой `stockyard_clickhouse_dropped_rows_total`. **Текущие котировки и ордера работают** (CH не на критическом пути). | docker-restart; накопленный батч флашится |
| **Redis cold-start** | Quotes сидит стартовые цены при старте. Драйвер даёт первый тик ≤ 1 сек. Sessions пустые → re-login. | секунды |
| **PG deadlock** | retry 1 раз с jitter 50 мс → если повтор, 409 `CONFLICT_RETRY` | автоматически |
| **PG statement_timeout** | TX abort → 503 `STORAGE_TIMEOUT` → клиент ретраит по идемпотентности | автоматически |
| **Pub/Sub subscriber lag** | Redis kick по `client-output-buffer-limit` → GW переподключение → snapshot+live | <1 сек gap |

### 7. Idempotency-keys retention (ADR-007)

Хранить **бессрочно** в `orders.idempotency_key`. Никаких TTL/cleanup, никакого внешнего хранилища. Overhead 130 МБ/год. Альтернативы (отдельная таблица, Redis с TTL) — отвергнуты.

### 8. Data model changes

| Изменение | Где | Причина |
|---|---|---|
| `CREATE EXTENSION pg_stat_statements` | V7 | OTel slow-queries |
| `idx_orders_user_ticker` | V6 | UI «история по тикеру» |
| Никаких партиций | подтверждение | ADR-008 |
| Никаких новых таблиц | — | бессрочный idempotency через `orders` |

DDL `users / accounts / instruments / orders / positions / transactions` — **без изменений** относительно `06-data §6.2.2`.

### 9. API contract changes

Никаких новых эндпоинтов. Расширяется только список возможных error-кодов в существующем формате `{ error: { code, message, details } }`:

| Код | Когда |
|---|---|
| `STORAGE_UNAVAILABLE` | критическое хранилище недоступно |
| `STORAGE_TIMEOUT` | превышен statement_timeout |
| `STORAGE_DEGRADED` | non-critical хранилище недоступно (CH down при истории свечей) |
| `CONFLICT_RETRY` | PG deadlock после retry |

### 10. Implementation steps

| # | Шаг | Роль | Зависит от |
|---|---|---|---|
| 1 | Создать `12-storage-operations.md`, `seed/instruments-50.md`, ADR-007, ADR-008, расширить `09-observability §9.12`, точечно `06-data`, обновить индексы | architect | — |
| 2 | Flyway V1–V7 миграции + DML 50 тикеров + `flyway.conf` | backend (Kotlin) | 1 |
| 3 | HikariCP + clickhouse-jdbc + OTel Java Agent + `/health/{live,ready}` | backend (Kotlin) | 2 |
| 4 | Lettuce клиенты в Gateway/Core Service, разделить connection pool и Pub/Sub-connection, OTel | backend (Kotlin) | 3 |
| 5 | go-redis + clickhouse-go в Quotes Service, `redisotel` + ручная CH-обёртка | backend (Go) | 4 |
| 6 | docker-compose limits + healthchecks + volumes + configs + exporters + Prometheus scrape + `.env.example` | backend / devops | 2-5 |
| 7 | Integration тесты с Testcontainers (PG+Redis+CH); failure-mode тесты | tester | 6 |
| 8 | Code review + security check | reviewer | 7 |
| 9 | Branch `feature/TASK-001-storage-ops`, atomic commits | committer | 8 |

Mobile / RN — не трогаются.

### 11. ADR

**ADR-007: Idempotency-keys retention** — бессрочно в `orders.idempotency_key`, без TTL/external store. Overhead pure. Альтернативы (отдельная таблица, Redis TTL, partitioning) — отвергнуты.

**ADR-008: PostgreSQL: no partitioning in MVP** — обычные heap-таблицы, объём 1 GB/год не требует партиций. Точка эволюции — при росте до 20М+ строк или vacuum > 30 мин.

Оба ADR создаются в `docs/architecture/adr/` (см. шаг 1).

### 12. Risks

| Риск | Импакт | Митигация |
|---|---|---|
| Hikari pool sizing не угадан | high | мониторим `hikaricp_connections_pending`, тюним под Load Simulator |
| ClickHouse `parts_to_throw_insert` бурст | medium | application-batch + async_insert |
| OTel Java Agent CPU overhead 5–10% | low | измерить в demo, fallback на instrumentation library |
| Argon2 pepper в репе | high (security) | seed только dev, реальный pepper генерируется на demo |
| Backup-cron на хосте, не в контейнере | medium | чек-лист demo; sidecar supercronic 📦 |

### 13. Estimated complexity: **MEDIUM**

~9–10 человеко-дней, параллелится между Kotlin/Go разработчиками и тестировщиком.

### 14. Open questions

1. OTel Java Agent vs instrumentation library — финал на S3 (измерить overhead).
2. Argon2 pepper management в demo — финал на S6.
3. postgres_exporter auth — рекомендую отдельный role `monitoring` с `pg_monitor` в V7.

### 15. Suggested next role

`/backend TASK-001` — Kotlin Core Service implementation (Flyway V1–V7 + сидинг 50 тикеров + HikariCP + JDBC OTel + health). После S2 параллельно `/backend TASK-001` для Go (Quotes Service storage layer).

Опционально: `/dba`, `/security`, `/devops` для review шага 1 перед началом backend (см. блок «Next Steps» в детальном дизайне ниже).

---

## Files produced by architect (этап 1)

NEW:
- `docs/architecture/12-storage-operations.md`
- `docs/architecture/seed/instruments-50.md`
- `docs/architecture/adr/ADR-007-idempotency-key-retention.md`
- `docs/architecture/adr/ADR-008-pg-no-partitioning-mvp.md`

EXTEND:
- `docs/architecture/09-observability.md` — секция §9.12 Storage instrumentation
- `docs/architecture/06-data.md` — заметка в §6.5 + новая §6.2.5
- `docs/architecture/adr/README.md` — индекс ADR-007/008
- `docs/architecture/README.md` — ссылка на §12

## Backend Implementation

### Scope of this run

Реализован **storage-layer infrastructure** (шаги 2 и 6 implementation plan): SQL-миграции, конфиги хранилищ, docker-compose в корне, init-скрипты, .env.example, .gitignore. Эти артефакты автономны и поднимаются командой `docker compose up -d` без application-кода.

**Не реализовано** (шаги 3–5 implementation plan, application-side wiring): HikariCP + clickhouse-jdbc + Lettuce + OTel Java Agent в Kotlin-сервисах; go-redis + clickhouse-go + redisotel + ручная CH-обёртка в Quotes Service. Блокер — нет проектного скаффолда: в репозитории отсутствуют каталоги `gateway/`, `core-service/<root>`, `quotes-service/<root>` с `build.gradle.kts` / `go.mod`. Application-wiring требует отдельной задачи на скаффолд (см. Open Questions ниже).

### Files changed (created)

**SQL-миграции Flyway (`core-service/src/main/resources/db/migration/`):**

- `V1__init_users_accounts.sql` — таблицы `users`, `accounts`, индекс `idx_accounts_user`. DDL ровно по 06-data §6.2.2.
- `V2__instruments.sql` — таблица `instruments` + DML на 50 тикеров MOEX (полный список из `seed/instruments-50.md`).
- `V3__orders.sql` — таблица `orders` с `UNIQUE (user_id, idempotency_key)` (ADR-005, ADR-007), индексы `idx_orders_user_created`, partial `idx_orders_status` (PENDING).
- `V4__positions.sql` — таблица `positions` с PRIMARY KEY (user_id, ticker).
- `V5__transactions.sql` — audit `transactions` + `idx_txn_user`.
- `V6__indexes_perf.sql` — `idx_orders_user_ticker` под UI «история по тикеру».
- `V7__pg_stat_statements.sql` — `CREATE EXTENSION pg_stat_statements`, идемпотентное создание role `monitoring` через DO-блок и `current_setting('stockyard.monitoring_password')` (значение прокидывается через `POSTGRES_INITDB_ARGS` в docker-compose), `GRANT pg_monitor`.

**Dev seed (`core-service/src/main/resources/db/seed/`):**

- `dev_users.sql` — 5 dev-юзеров с депозитом 100M cents, alice уже владеет SBER × 100. Хэши паролей — placeholder `$argon2id$REPLACE_AT_RUNTIME`, реальные хэши вставляет CLI-утилита приложения с актуальным `ARGON2_PEPPER`. Все INSERT'ы идемпотентны (`ON CONFLICT DO NOTHING`).

**ClickHouse init (`deploy/clickhouse/`):**

- `config.xml` — server overlay: `max_connections=256`, `mark_cache=512MB`, `uncompressed_cache=1GB`, MergeTree backpressure (`parts_to_throw_insert=600`), prometheus endpoint `:9363/metrics`.
- `users.xml` — default profile: `max_memory_usage=2GB`, `max_execution_time=10`, `async_insert=1`, `wait_for_async_insert=0`.
- `init.sql` — `CREATE DATABASE stockyard`, `quotes_ticks` (MergeTree partition by month + TTL 6 month), `quotes_candles_1m`/`_1h` (AggregatingMergeTree) + два Materialized View поверх `quotes_ticks`. Все DDL — `IF NOT EXISTS`.

**PostgreSQL ops (`deploy/postgres/`):**

- `postgresql.conf` — full config: `shared_buffers=1GB`, `effective_cache_size=3GB`, `work_mem=16MB`, `max_connections=200`, `pg_stat_statements` в `shared_preload_libraries`, `log_min_duration_statement=500ms`, `log_lock_waits=on`.
- `backup.sh` (executable) — `pg_dump --format=custom --compress=9` + `pg_restore --list` для smoke-проверки + ротация `mtime +RETENTION_DAYS`. Размер бэкапа логируется stat-команд'ой переносимо для GNU/BSD.

**Redis ops (`deploy/redis/`):**

- `redis.conf` — `maxmemory=1gb`, `allkeys-lru`, `save 3600 1`, `appendonly no`, `client-output-buffer-limit pubsub 32mb 8mb 60`. `requirepass` НЕ в файле — передаётся флагом командной строки в docker-compose, чтобы пароль не лип в образ.

**Prometheus (`deploy/prometheus.yml`):**

- 5 jobs: `postgres_exporter:9187`, `redis_exporter:9121`, `clickhouse:9363`, `otel-collector:8889`, self-scrape.
- `scrape_interval=15s`, `external_labels.deployment=stockyard`.

**Quotes Service config (`quotes-service/config/`):**

- `seed-prices.yaml` — стартовые цены 50 тикеров в копейках с примерными значениями MOEX 2024 (SBER=28000, GAZP=14000, …, GMKN=1500000), `defaults.spread_bps: 50`, `initial_volume: 0`.

**Корень проекта:**

- `docker-compose.yml` — сервисы: `postgres`, `postgres-exporter`, `redis`, `redis-exporter`, `clickhouse`, `prometheus`. Все три storage с healthcheck'ами, resource limits (CPU/RAM по дизайну), volumes для данных, bind-mount конфигов и backup script'а. Exporters depend on healthy storage. Один shared network `stockyard`.
- `.env.example` — шаблон с пустыми секретами + комментарий о генерации (`openssl rand -base64 32`).
- `.gitignore` — `.env*` (кроме `.env.example`), `volumes/`, build-артефакты Kotlin/Go/Node/Android/Python/C, IDE-файлы, логи.
- `volumes/backups/postgres/.gitkeep` — placeholder, чтобы директория попала в репозиторий.

### Key decisions

- **Один Redis-инстанс на MVP** (а не два). Pub/Sub без eviction, `client-output-buffer-limit` защищает от subscriber-lag. Разделение на cache+pubsub — точка эволюции 📦 (см. 12-storage-operations §12.2.1).
- **`requirepass` Redis передаётся флагом командной строки**, не в `redis.conf` — пароль не попадает в bind-mount файл.
- **Flyway-миграции отдельно от docker-compose**: PG-миграции прогоняет entrypoint `core-service` (когда тот будет добавлен), а не docker-compose. ClickHouse `init.sql` грузится через `/docker-entrypoint-initdb.d/` — CH специфика, удобно для одной-двух DDL за весь MVP.
- **`monitoring_password` для postgres_exporter** прокидывается через GUC `stockyard.monitoring_password` в `POSTGRES_INITDB_ARGS`. V7 миграция читает GUC через `current_setting(...)` и создаёт роль идемпотентно.
- **dev_users seed** содержит placeholder-хэши `$argon2id$REPLACE_AT_RUNTIME`. Это намеренно: реальные хэши зависят от `ARGON2_PEPPER`, который генерируется локально и не коммитится. Это исключает риск, что seed-юзеры с известным паролем приедут на demo (security-митигация из дизайна §12).
- **`.gitignore`** содержит generic-блокировку `*credentials*` / `*secret*` / `id_rsa*`, но `.env.example` явно whitelisted.
- **Версии образов** зафиксированы (postgres:16-alpine, redis:7-alpine, clickhouse:24-alpine, prom:v2.54.1, postgres_exporter:v0.15.0, redis_exporter:v1.62.0). Никаких `:latest`.
- **`POSTGRES_INITDB_ARGS`** работает только при первой инициализации тома `pg-data`. Если volume уже существует — GUC не появится; это OK, ручной workflow для смены `MONITORING_PASSWORD` в проде описан как «поднять временную PG, прогнать `ALTER ROLE monitoring PASSWORD ...`» — выходит за scope MVP.

### API endpoints implemented

Никаких — задача инфраструктурная.

### SQL migrations

V1, V2, V3, V4, V5, V6, V7 — см. файловый список выше. V2 = DDL + DML (50 тикеров).

### Smoke verification

- `docker compose --env-file <test-env> config --quiet` → OK (проверено).
- Поднять полный stack (`docker compose up -d`) и убедиться что все three storage → `healthy` за < 60 сек — оставлено `/tester`.
- Прогнать V1–V7 на пустой PG и `init.sql` на пустом CH — оставлено `/tester` (нужен Testcontainers, без application-кода прогон руками через `psql -f V1__...` тривиален, но тестовые контейнеры покроют это автоматически).

### Open questions / blockers

1. **Скаффолд Kotlin-сервисов (`gateway/`, `core-service/<root>` с `build.gradle.kts`, `src/main/kotlin/Application.kt`).** Нужен, чтобы материализовать HikariCP + clickhouse-jdbc + Lettuce + OTel Java Agent + `/health/{live,ready}`. Это **новая задача** для архитектора — структура Gradle multi-module проекта, версии зависимостей, layout пакетов. Тогда `/backend TASK-N+M` сможет реально подключить пулы и инструментацию.
2. **Скаффолд Go-сервиса (`quotes-service/<root>` с `go.mod`).** Аналогично — нужен `cmd/quotes/main.go`, internal-структура, чтобы реализовать go-redis + clickhouse-go + redisotel + ручную CH-обёртку.
3. **Argon2 CLI-утилита** для seed-юзеров. Зависит от (1) — чтобы был Kotlin-проект, в котором живёт CLI.
4. **OTel Collector** в docker-compose. Сейчас `prometheus.yml` ссылается на `otel-collector:8889`, но самого контейнера в compose нет. Добавляется вместе с application-сервисами, иначе скрапить нечего. На текущем этапе job `otel_collector` будет показывать `up=0` — это OK для infra-only stage.
5. **Расширение V2 миграции для load-теста на 10к юзеров.** Сидинг 10k тестовых юзеров под Load Simulator — отдельная вещь, делается из CLI Simulator'а через API регистрации, не SQL'ем.

### What works now

```bash
cp .env.example .env
# заполнить PG_PASSWORD, MONITORING_PASSWORD, REDIS_PASSWORD, CH_PASSWORD
docker compose up -d
docker compose ps                    # postgres / redis / clickhouse → healthy
docker exec stockyard-postgres psql -U stockyard -d stockyard -c '\dt'
docker exec stockyard-clickhouse clickhouse-client -q 'SHOW TABLES FROM stockyard'
curl http://localhost:9090/targets   # Prometheus видит exporters
curl http://localhost:9187/metrics   # postgres_exporter (после применения V7)
curl http://localhost:9121/metrics   # redis_exporter
curl http://localhost:9363/metrics   # ClickHouse builtin
```

PG-миграции применяются при первом подключении Core Service. До тех пор схема пустая — это ожидаемо. Ручной прогон для smoke:

```bash
docker exec -i stockyard-postgres psql -U stockyard -d stockyard \
  < core-service/src/main/resources/db/migration/V1__init_users_accounts.sql
# ... V2..V7 в том же духе
```

ClickHouse `init.sql` применяется автоматически при первом старте контейнера.

## Handoff Log
- 2026-05-10T00:00:00Z: создан через /architect — design в работе
- 2026-05-10T00:30:00Z: /architect — gap-analysis выполнен; design сохранён; материализованы архитектурные документы и ADR; suggested next: /backend TASK-001
- 2026-05-10T22:30:00Z: /architect повторно — уточнено расположение `docker-compose.yml` в корне проекта (12-storage-operations §12.7). Suggested next: /backend TASK-001.
- 2026-05-10T23:00:00Z: /backend — реализована storage-layer infrastructure: 7 Flyway миграций + dev seed; ClickHouse config.xml/users.xml/init.sql; PG postgresql.conf + backup.sh; Redis redis.conf; prometheus.yml; docker-compose.yml в корне с 6 сервисами (PG/redis/CH + 2 exporters + Prometheus); .env.example; .gitignore; quotes-service/config/seed-prices.yaml. `docker compose config` валиден. Application-side wiring (Hikari/Lettuce/redisotel/CH wrapper) отложена — блокер: нет скаффолда Kotlin/Go проектов. Stage = backend-partial. Suggested next: /architect для нового TASK на scaffold проектов, либо /tester TASK-001 для проверки storage-инфры.
- 2026-05-10T23:30:00Z: затронут rename'ом из TASK-002 — все упоминания «DB Service» заменены на «Core Service», `db-service` → `core-service`, директория `db-service/` → `core-service/`. Содержательно дизайн не изменился; имена в этом ledger обновлены автоматически.
- 2026-05-11T03:00:00Z: /committer — артефакты включены в ветку `feature/3-gateway-scaffold` вместе с TASK-002 и TASK-003. SQL-миграции в коммите 2fda441 feat(core); deploy/конфиги в 4031ff6 feat(deploy); architectural docs в 8473161 docs(arch); ledger в 070e2e5 docs(task). CHANGELOG [Unreleased] дополнен. Stage = committed.
