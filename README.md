<div align="center">

# Stockyard

Trading & Investment Platform

Учебный MVP экосистемы для трейдинга на 10 000 одновременных клиентов.
Курс «Разработка мобильных приложений», ИТМО, весна 2026.

[![Version](https://img.shields.io/badge/version-0.4.0-blue)](CHANGELOG.md)
[![Status](https://img.shields.io/badge/status-MVP--in--progress-orange)](.claude/tasks/)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0.21-7f52ff?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Ktor](https://img.shields.io/badge/ktor-2.3.13-087cfa?logo=ktor&logoColor=white)](https://ktor.io/)
[![Go](https://img.shields.io/badge/go-1.22+-00add8?logo=go&logoColor=white)](https://go.dev/)
[![PostgreSQL](https://img.shields.io/badge/postgres-16-4169e1?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/redis-7-dc382d?logo=redis&logoColor=white)](https://redis.io/)
[![ClickHouse](https://img.shields.io/badge/clickhouse-24-ffcc01?logo=clickhouse&logoColor=black)](https://clickhouse.com/)

</div>

---

## Что это

Stockyard — учебная торговая платформа, которая по структуре повторяет реальные брокерские системы, но в масштабе одного семестра. Биржевой драйвер на C эмулирует поток котировок через character-device, Quotes Service на Go раздаёт их по pub/sub, Core Service на Kotlin исполняет ордера в одной PostgreSQL-транзакции, Gateway терминирует HTTP/WS от мобильных клиентов. Андроид-приложение и React Native-клиент ходят в одну и ту же публичную ручку.

Цель — пройти полный цикл от ТЗ до защиты с реальной нагрузкой 10 000 CCU на финальном прогоне Load Simulator. Стек жёстко зафиксирован [требованиями курса](REQUIREMENTS.md).

## Архитектура

```
                  ┌────────────────────────┐  ┌─────────────────────────┐
   CLIENTS        │ Android · Kotlin +     │  │ React Native · TS +     │
                  │ Jetpack Compose        │  │ Redux Toolkit           │
                  └───────────┬────────────┘  └────────────┬────────────┘
                              │  REST + WS                 │
                              ▼                            ▼
                  ┌─────────────────────────────────────────────────────┐
   EDGE           │  API Gateway  ·  Ktor  ·  :8080                     │
                  │  публичный HTTP + WebSocket, JWT issue/verify       │
                  └────┬────────────────────┬────────────────┬──────────┘
                       │ REST               │ pub/sub        │ ws-fanout
                       ▼                    │                │
        ┌──────────────────────────────┐    │                │
   BACK │ Core Service · Ktor · :8081  │    │     ┌──────────┴────────┐
        │ orders, portfolio, auth      │    │     │ Quotes Service ·  │
        └──┬──────────┬──────────┬─────┘    │     │ Go · pipeline     │
           │          │          │          │     └──────────┬────────┘
           │          │          │          │                │
           ▼          ▼          ▼          ▼                ▼
        ┌────┐    ┌────┐    ┌──────┐   ┌────────────────────────┐
   STORE│ PG │    │ RD │    │  CH  │   │  PG=PostgreSQL 16      │
        │ 16 │    │  7 │    │  24  │   │  RD=Redis 7            │
        └────┘    └────┘    └──────┘   │  CH=ClickHouse 24      │
                                       └────────────────────────┘

                       ▲
                       │ binary tick stream
                       │
                  ┌────┴───────────────────┐
   DRIVER         │  /dev/stockyard        │
                  │  C Linux kernel module │
                  └────────────────────────┘
```

Подробнее: [`docs/architecture/`](docs/architecture/README.md) — 12 разделов плюс [9 ADR](docs/architecture/adr/) с обоснованием решений.

## Стек

| Слой | Технологии |
|---|---|
| Android | Kotlin · Jetpack Compose · Hilt · OkHttp/Retrofit |
| Cross-platform | React Native · TypeScript · Redux Toolkit · axios |
| API Gateway, Core Service | Kotlin · Ktor · корутины · kotlinx.serialization |
| Quotes Service | Go · stdlib + `go-redis` + `clickhouse-go` |
| Driver | C · Linux kernel module (character device) |
| OLTP | PostgreSQL 16 · **raw SQL only, без ORM** |
| Cache / pub-sub | Redis 7 (либо KeyDB) |
| Time-series | ClickHouse 24 |
| Observability | OpenTelemetry → Prometheus + Jaeger + Grafana |
| Auth | Argon2id (m=19 MiB / t=2 / p=1) + HMAC-SHA256 pepper · JWT HS256 |

Отклонение от стека снижает балл — это требование ТЗ §3.

## Быстрый старт

Поднять storage + бэкенд одной командой:

```bash
cp .env.example .env
# заполнить пароли: PG_PASSWORD, REDIS_PASSWORD, CH_PASSWORD,
#                   JWT_SECRET (≥32 байта), ARGON2_PEPPER (≥32 байта),
#                   MONITORING_PASSWORD

docker compose up -d
docker compose ps     # дождаться, пока все контейнеры healthy
```

Поднимутся: `postgres` :5432, `redis` :6379, `clickhouse` :8123/9000, `prometheus` :9090, exporters, `core-service` :8081, `gateway` :8080.

Проверить, что всё живо:

```bash
curl http://localhost:8080/health/ready   # gateway
curl http://localhost:8081/health/ready   # core
```

Зарегистрировать пользователя и получить токены:

```bash
curl -X POST http://localhost:8080/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"trader@example.com","password":"strong-pass-1"}'
```

Ответ — userId, accessToken (TTL 15 мин), refreshToken (TTL 30 дней), на счету 1 000 000,00 ₽.

## Что работает сегодня (v0.4.0)

| Область | Состояние |
|---|---|
| Storage layer (PG + Redis + CH) | Готов: 7 миграций, 50 тикеров MOEX, MV-свечи 1m/1h, бэкап cron |
| API Gateway | Готов: health, metrics, WS-skeleton, JWT-issue/verify, error-mapper |
| Core Service | Готов: Flyway-bootstrap, pools, OTel, `/internal/*` для auth |
| Auth flow | Готов: register / login / refresh с rotation в Redis |
| Orders (BUY/SELL) | 501 stub — TASK-006 |
| Portfolio | 501 stub — TASK-007 |
| Quotes pipeline (driver → Quotes → Redis → WS) | 501 stub + scaffold — TASK-008 |
| Android client | TASK-009 |
| React Native client | TASK-010 |
| Load Simulator | TASK-011 |

История релизов — в [CHANGELOG.md](CHANGELOG.md).

## Структура репозитория

```
.
├── core-service/           Kotlin/Ktor — бизнес-логика, ордера, портфели
│   └── src/main/resources/db/migration/   Flyway V1..V7
├── gateway-service/        Kotlin/Ktor — публичный HTTP + WS
├── quotes-service/         Go — котировочный pipeline (scaffold)
├── deploy/                 postgresql.conf, redis.conf, clickhouse/, prometheus.yml
├── docker-compose.yml      одна команда — поднять всё
├── docs/architecture/      12 разделов + ADR-001..009
├── .claude/                role-based slash-commands + task ledger
│   ├── commands/             architect, backend, tester, reviewer, committer, …
│   └── tasks/                TASK-001 … TASK-NNN (журнал работ)
├── REQUIREMENTS.md         требования курса (ТЗ)
├── CLAUDE.md               контекст проекта для AI-assisted разработки
├── CHANGELOG.md            Keep a Changelog 1.1.0
└── VERSION                 SemVer single-source-of-truth
```

## Документация

Точка входа — [`docs/architecture/README.md`](docs/architecture/README.md). Что искать где:

- [`01-context.md`](docs/architecture/01-context.md) — система как чёрный ящик и внешние акторы
- [`02-containers.md`](docs/architecture/02-containers.md) — какой сервис где живёт
- [`03-components.md`](docs/architecture/03-components.md) — внутреннее устройство каждого сервиса
- [`05-communication.md`](docs/architecture/05-communication.md) — REST + WS контракты, формат ошибок, error codes
- [`06-data.md`](docs/architecture/06-data.md) — DDL, индексы, ключи Redis, схема ClickHouse
- [`07-consistency.md`](docs/architecture/07-consistency.md) — BUY/SELL TX, `FOR UPDATE`, idempotency
- [`09-observability.md`](docs/architecture/09-observability.md) — OTel, метрики, трейсы, SLO
- [`10-scenarios.md`](docs/architecture/10-scenarios.md) — sequence-диаграммы всех ключевых flow
- [`11-testing.md`](docs/architecture/11-testing.md) — стратегия трёх уровней (unit / IT / system)
- [`12-storage-operations.md`](docs/architecture/12-storage-operations.md) — конфиги Hikari/Lettuce, бэкапы
- [`adr/`](docs/architecture/adr/) — почему raw SQL, почему Argon2, почему single-writer и т.д.

## Workflow разработки

Проект ведётся через систему ролевых команд с общим **task ledger**. Каждая фича начинается с архитектора и проходит по пайплайну, оставляя след в `.claude/tasks/TASK-NNN-<slug>.md`.

```
/architect "feature X"   →  TASK-NNN создан, дизайн готов, ADR при необходимости
/backend  TASK-NNN       →  Kotlin / Go / C — код по плану архитектора
/mobile   TASK-NNN       →  Android UI
/frontend TASK-NNN       →  React Native UI
/tester   TASK-NNN       →  unit + integration (Testcontainers) + system
/reviewer TASK-NNN       →  финальный gate: correctness / security / conventions
/committer TASK-NNN      →  ветка feature/<N>-<slug>, atomic commits, push
```

Правила атомарности коммитов, имени веток и SemVer-bump — в [`.claude/commands/committer.md`](.claude/commands/committer.md). Структура задачи и handoff log — в [`.claude/tasks/README.md`](.claude/tasks/README.md).

Тесты:

```bash
# Kotlin (Gradle)
./gradlew :gateway-service:test
./gradlew :core-service:test

# Go
cd quotes-service && go test ./...
```

Интеграционные тесты используют Testcontainers — нужен запущенный Docker.

## Версионирование

SemVer 2.0 + Keep a Changelog 1.1.0. Файл [`VERSION`](VERSION) — единственный источник правды о текущей версии, изменяется только командой `/committer release ...`. Pre-1.0 (`0.x.y`) — API может ломаться между минорными релизами; стабильность гарантируется с `1.0.0` (планируется к финальной защите).

| Версия | Дата | Что добавлено |
|---|---|---|
| [0.4.0](https://github.com/r4m63/Stockyard/releases/tag/v0.4.0) | 2026-05-11 | Auth flow: register / login / refresh с rotation |
| [0.3.0](https://github.com/r4m63/Stockyard/releases/tag/v0.3.0) | 2026-05-11 | Core Service scaffold + Flyway-bootstrap + pools |
| [0.2.0](https://github.com/r4m63/Stockyard/releases/tag/v0.2.0) | 2026-05-11 | Storage layer + Gateway scaffold + 50 MOEX тикеров |
| [0.1.0](https://github.com/r4m63/Stockyard/releases/tag/v0.1.0) | 2026-05-09 | Архитектурный фундамент (12 разделов + 6 ADR) |

## Команда курса

Преподаватель — **Ключев А.О.** ([kluchev@yandex.ru](mailto:kluchev@yandex.ru)). Группа в Telegram — «РМП 2026 весна». Семестр — весна 2026, ИТМО, ФПИиКТ.

---

<sub>Stockyard разрабатывается как учебный проект. Лицензия — академическая, используется только в рамках курса.</sub>
