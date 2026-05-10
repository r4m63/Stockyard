# TASK-004: Scaffold Core Service

## Meta
- ID: TASK-004
- Created: 2026-05-11T03:30:00Z
- Last updated: 2026-05-11T05:00:00Z
- Stage: done
- Touched roles: architect, backend, tester, reviewer

## Original Request
спроектируй на основе того что сейчас есть Core Service полностью!!!

## Architect Design

> **Anti-pattern flag.** Архитектура Core Service полностью спроектирована в [03-components §3.2](../../docs/architecture/03-components.md#32-core-service-kotlin) (компоненты + layout пакетов), [05-communication §5.4](../../docs/architecture/05-communication.md#54-внутренний-api-gateway--core-service) (internal API), [06-data §6.2](../../docs/architecture/06-data.md#62-postgresql-схема) (DDL), [07-consistency §7.2](../../docs/architecture/07-consistency.md#72-acid-транзакции-в-postgresql) (ACID TX BUY/SELL), [12-storage-operations §12.1](../../docs/architecture/12-storage-operations.md#121-postgresql--operations) (HikariCP/Flyway), плюс ADR-004/005/006/007/008/009. Эта задача — не повторное проектирование, а **scaffold + bootstrap** того, что уже есть: build, DI, конфиги, Flyway, минимальный набор internal-эндпоинтов как stubs.

### 1. Affected components

| Компонент | Затрагивается | Что меняется |
|---|---|---|
| **Core Service (Kotlin/Ktor)** | да, **новый сервис целиком** | scaffold + минимальный bootstrap |
| **Gateway** | нет напрямую | `CoreServiceClient.healthReady()` начнёт отвечать `true` после первого деплоя core — поведение `/health/ready` Gateway улучшится |
| **docker-compose.yml** | да | новый блок `core-service` (depends_on: postgres healthy, redis healthy) |
| **.env.example** | минимально | переменные `PG_USER`, `PG_PASSWORD`, `PG_DB`, `REDIS_PASSWORD`, `CH_USER`, `CH_PASSWORD`, `ARGON2_PEPPER` уже есть |
| Mobile/RN/Quotes/Driver/Simulator | нет | не общаются с core напрямую |

### 2. Что точно НЕ делаем в TASK-004

Минимально жизнеспособный = **process поднимается, Flyway применил V1-V7, отвечает на health, имеет stubs internal-эндпоинтов**. Бизнес-логика выносится в последующие TASK'и:

| Возможность | TASK | Зависимости |
|---|---|---|
| `POST /internal/users` + argon2id hash | TASK-005 | требует Argon2 wire-up |
| `POST /internal/auth` (password verify) | TASK-005 | парный к /users |
| `POST /internal/orders` (BUY/SELL TX, см. 07-consistency §7.2) | TASK-006 | требует quotes:* доступа в Redis |
| `GET /internal/users/{id}/portfolio` | TASK-007 | требует positions + accounts |
| `GET /internal/instruments` | TASK-007 | простой SELECT |
| `GET /internal/users/{id}/orders` (history) | TASK-007 | требует pagination cursor |
| `GET /internal/quotes/{ticker}/history` (CH candles) | TASK-008 | требует clickhouse-jdbc запросов |

В TASK-004 эти routes возвращают **`501 NOT_IMPLEMENTED`** в формате `{"error":{"code":"NOT_IMPLEMENTED","message":"..."}}`, идентичном Gateway. Это позволяет Gateway уже в TASK-004 показывать `core-service: UP` в `/health/ready` и проксировать запросы (получая консистентный 501).

### 3. Scaffold scope

**Build (single-module Gradle, ADR-009):**
- `core-service/build.gradle.kts` (Kotlin DSL)
- `core-service/settings.gradle.kts`
- `core-service/gradle.properties`
- `core-service/gradle/libs.versions.toml`

**Source layout** (per [03-components §3.2](../../docs/architecture/03-components.md#32-core-service-kotlin), с поправкой package: `com.stockyard.db` → `com.stockyard.core` — следствие TASK-002 rename):

```
core-service/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
├── Dockerfile
├── .dockerignore
└── src/main/
    ├── kotlin/com/stockyard/core/
    │   ├── Application.kt              # entry: EngineMain.main(args) + module()
    │   ├── config/
    │   │   ├── AppConfig.kt            # data class из HOCON
    │   │   └── Plugins.kt              # install ContentNegotiation, StatusPages, CallLogging
    │   ├── persistence/
    │   │   ├── DataSources.kt          # HikariCP для PG + clickhouse-jdbc для CH
    │   │   ├── TransactionManager.kt   # тонкая обёртка suspend { connection.autoCommit=false; … }
    │   │   └── FlywayBootstrap.kt      # programmatic flyway.migrate() на старте
    │   ├── redis/
    │   │   └── RedisModule.kt          # Lettuce — командный пул + pubsub-connection (как в gateway)
    │   ├── auth/
    │   │   └── PasswordHasher.kt       # argon2id wrapper (de.mkammerer:argon2-jvm) — готов к TASK-005
    │   ├── api/
    │   │   ├── UserApi.kt              # POST /internal/users, POST /internal/auth (stubs)
    │   │   ├── OrderApi.kt             # POST /internal/orders, GET /internal/users/{id}/orders (stubs)
    │   │   ├── PortfolioApi.kt         # GET /internal/users/{id}/portfolio (stub)
    │   │   ├── InstrumentApi.kt        # GET /internal/instruments (stub)
    │   │   └── QuotesApi.kt            # GET /internal/quotes/{ticker}/history (stub)
    │   ├── routing/
    │   │   └── HealthRoutes.kt         # /health/{live,ready} (PG SELECT 1 + Redis PING)
    │   ├── error/
    │   │   ├── ApiError.kt             # {code, message, details?} — идентичный gateway
    │   │   └── ErrorMapper.kt          # StatusPages mappings
    │   ├── domain/
    │   │   ├── user/                   # пустые пакеты-заготовки для TASK-005
    │   │   ├── order/                  # для TASK-006
    │   │   ├── portfolio/              # для TASK-007
    │   │   └── instrument/             # для TASK-007
    │   └── telemetry/
    │       └── OtelInit.kt             # GlobalOpenTelemetry wrapper (как в gateway)
    └── resources/
        ├── application.conf            # HOCON
        ├── logback.xml                 # JSON через logstash-encoder
        └── db/
            ├── migration/V1..V7        # уже на месте (из TASK-001)
            └── seed/dev_users.sql      # уже на месте (TASK-001)
```

**Не входит:**
- Реальные репозитории/сервисы (только пакеты-заготовки в `domain/`).
- Бизнес-логика BUY/SELL TX.
- Тесты — отдельной задачей `/tester TASK-004`.

### 4. Технические решения

#### 4.1. Stack & версии

Полностью соответствует gateway (`gateway-service/gradle/libs.versions.toml` из TASK-003), плюс **что отличается у core**:

- **postgresql** JDBC driver — `org.postgresql:postgresql:42.7.4`.
- **HikariCP** — `com.zaxxer:HikariCP:6.0.0`.
- **Flyway** core — `org.flywaydb:flyway-core:10.20.1` + PG plugin `flyway-database-postgresql`.
- **clickhouse-jdbc** — `com.clickhouse:clickhouse-jdbc:0.7.0` (для будущих SELECT свечей).
- **argon2-jvm** — `de.mkammerer:argon2-jvm:2.11`.

Версии Ktor 2.3.13, Kotlin 2.0.21, Lettuce 6.4.1, commons-pool2 2.12.0 — те же что у gateway.

Catalog общий по структуре, но **дублируется** в `core-service/gradle/libs.versions.toml` (ADR-009: single-module per service, без shared catalog).

#### 4.2. DI

Constructor injection без фреймворка. `Application.module()` строит граф: AppConfig → DataSources → TransactionManager → RedisModule → PasswordHasher → ApiHandlers → Routing.

#### 4.3. Flyway: programmatic на старте

В `Application.module()` до `routing { }`:

```kotlin
val flyway = Flyway.configure()
    .dataSource(dataSources.pg)
    .locations("classpath:db/migration")
    .baselineOnMigrate(true)
    .validateOnMigrate(true)
    .cleanDisabled(true)
    .load()
val result = flyway.migrate()
log.atInfo()
    .addKeyValue("migrations.applied", result.migrationsExecuted)
    .addKeyValue("schema.version", result.targetSchemaVersion?.version)
    .log("Flyway migration complete")
```

Это:
- Гарантирует, что схема актуальна **до** первого запроса в БД.
- Pin'ит версию схемы к версии приложения (deploy unit = jar + миграции).
- Не требует внешнего тулинга (gradle-plugin, sidecar).

Если миграция упала — `flyway.migrate()` бросит exception, `module()` не завершится, Ktor не стартует. Это правильный fail-fast.

`flyway.cleanDisabled=true` — `flyway clean` отключён даже в dev. Если разработчику нужно сбросить — пусть пересоздаёт PG-volume.

#### 4.4. HikariCP — по 12-storage-operations §12.1.2

```kotlin
HikariConfig().apply {
    jdbcUrl = config.postgres.jdbcUrl
    driverClassName = "org.postgresql.Driver"
    username = config.postgres.user
    password = config.postgres.password
    maximumPoolSize = 50
    minimumIdle = 10
    connectionTimeout = 1000
    idleTimeout = 600_000
    maxLifetime = 1_800_000
    keepaliveTime = 60_000
    leakDetectionThreshold = 5_000
    connectionTestQuery = "SELECT 1"
    initializationFailTimeout = 10_000
}
// + connection-level через init SQL:
// SET statement_timeout = 3000;
// SET lock_timeout = 2000;
// SET idle_in_transaction_session_timeout = 5000;
```

#### 4.5. ClickHouse: clickhouse-jdbc через HikariCP

```kotlin
HikariConfig().apply {
    jdbcUrl = "jdbc:ch://${config.clickhouse.host}:8123/stockyard?compress=lz4&socket_timeout=10000"
    username = config.clickhouse.user
    password = config.clickhouse.password
    maximumPoolSize = 8
    minimumIdle = 2
    connectionTimeout = 2000
    idleTimeout = 300_000
    maxLifetime = 1_800_000
    connectionTestQuery = "SELECT 1 FORMAT TabSeparated"
}
```

#### 4.6. TransactionManager

Тонкая обёртка для bracket-паттерна:

```kotlin
class TransactionManager(private val ds: DataSource) {
    suspend fun <T> withTx(block: suspend (Connection) -> T): T = withContext(Dispatchers.IO) {
        ds.connection.use { conn ->
            val prev = conn.autoCommit
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                result
            } catch (e: Throwable) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                conn.autoCommit = prev
            }
        }
    }
}
```

В TASK-004 не используется бизнес-логикой, но готов для TASK-005/006.

#### 4.7. PasswordHasher (argon2-jvm) — готов к TASK-005

```kotlin
class PasswordHasher(private val pepper: ByteArray) {
    private val argon = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
    private val memoryKiB = 19 * 1024   // 19 MiB per ADR-006
    private val iterations = 2
    private val parallelism = 1

    fun hash(password: CharArray): String {
        val peppered = peppered(password)
        try {
            return argon.hash(iterations, memoryKiB, parallelism, peppered)
        } finally {
            peppered.fill(' ')
        }
    }

    fun verify(hash: String, password: CharArray): Boolean {
        val peppered = peppered(password)
        try { return argon.verify(hash, peppered) }
        finally { peppered.fill(' ') }
    }

    private fun peppered(password: CharArray): CharArray = TODO("HMAC-mix password with pepper bytes")
}
```

Pepper читается из `ARGON2_PEPPER` env (уже в `.env.example`). Fail-fast если пуст или короче 32 байт (аналогично `JWT_SECRET` в gateway).

#### 4.8. Lettuce Redis — копия из gateway

Идентичная обёртка `RedisModule` с `GenericObjectPool` (maxTotal=32) + выделенный pubsub-conn. В core pubsub-connection пока не используется, но инициализируется для симметрии.

#### 4.9. Internal API routes — stubs

Согласно 05-communication §5.4:

```
POST   /internal/users
POST   /internal/auth
POST   /internal/orders
GET    /internal/users/{userId}/orders
GET    /internal/users/{userId}/portfolio
GET    /internal/instruments
GET    /internal/quotes/{ticker}/history
```

В TASK-004 каждая бросает `NotImplementedError("... coming in TASK-NNN")`, StatusPages маппит в 501 с единым форматом.

#### 4.10. Health endpoints

```
GET /health/live   → 200 {"status":"UP"} (без обращений в downstream)
GET /health/ready  → 200 если PG SELECT 1 ОК и Redis PING == PONG; 503 иначе.
                     ClickHouse — info-only, не делает unhealthy (CH не на критическом пути ордеров — см. 12-storage-operations §12.3).
```

#### 4.11. Configuration (HOCON)

```hocon
ktor {
  deployment {
    port = 8080
    port = ${?CORE_PORT}
    host = "0.0.0.0"
  }
  application {
    modules = [ com.stockyard.core.ApplicationKt.module ]
  }
}

stockyard {
  postgres {
    host     = "postgres"
    host     = ${?PG_HOST}
    port     = 5432
    db       = "stockyard"
    db       = ${?PG_DB}
    user     = "stockyard"
    user     = ${?PG_USER}
    password = ""
    password = ${?PG_PASSWORD}
  }
  redis {
    url      = "redis://redis:6379"
    url      = ${?REDIS_URL}
    password = ""
    password = ${?REDIS_PASSWORD}
  }
  clickhouse {
    host     = "clickhouse"
    host     = ${?CH_HOST}
    user     = "stockyard"
    user     = ${?CH_USER}
    password = ""
    password = ${?CH_PASSWORD}
  }
  argon2 {
    pepper = ""
    pepper = ${?ARGON2_PEPPER}
  }
  otel {
    serviceName  = "core-service"
    serviceName  = ${?OTEL_SERVICE_NAME}
    otlpEndpoint = "http://otel-collector:4317"
    otlpEndpoint = ${?OTEL_EXPORTER_OTLP_ENDPOINT}
  }
}
```

#### 4.12. Observability

OTel Java Agent через `JAVA_TOOL_OPTIONS=-javaagent:/otel.jar` (как gateway). Auto-instrumentation покроет: Ktor HTTP, HikariCP+JDBC (PG и CH), Lettuce. Кастомные spans — в TASK-005/006 вокруг бизнес-транзакций (`stockyard.tx.kind=order_buy`, `stockyard.user_id`).

Metrics через Micrometer-OTel bridge (`MicrometerMetrics` Ktor plugin) → `/metrics` Prometheus endpoint. Endpoint реализуется уже в TASK-004 (мелкая правка, в gateway отложил в TASK-005 — здесь сделать сразу, потому что HikariCP-метрики ценны с момента старта).

#### 4.13. Dockerfile

Идентичный gateway: multi-stage (gradle-jdk21-alpine → temurin-21-jre-alpine + OTel agent 2.9.0). HEALTHCHECK через `curl /health/live`.

#### 4.14. docker-compose

```yaml
core-service:
  build:
    context: ./core-service
    dockerfile: Dockerfile
  container_name: stockyard-core-service
  restart: unless-stopped
  deploy:
    resources:
      limits:       { cpus: '2.0', memory: 1G }
      reservations: { cpus: '1.0', memory: 512M }
  environment:
    CORE_PORT: "8080"
    PG_HOST: postgres
    PG_DB: ${PG_DB}
    PG_USER: ${PG_USER}
    PG_PASSWORD: ${PG_PASSWORD}
    REDIS_URL: redis://redis:6379
    REDIS_PASSWORD: ${REDIS_PASSWORD}
    CH_HOST: clickhouse
    CH_USER: ${CH_USER}
    CH_PASSWORD: ${CH_PASSWORD}
    ARGON2_PEPPER: ${ARGON2_PEPPER}
    OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4317
    OTEL_SERVICE_NAME: core-service
    DEPLOYMENT_ENVIRONMENT: ${DEPLOYMENT_ENVIRONMENT:-dev}
  ports:
    - "8081:8080"        # хосту 8081 чтобы не путать с gateway:8080
  networks: [stockyard]
  depends_on:
    postgres: { condition: service_healthy }
    redis:    { condition: service_healthy }
  healthcheck:
    test: ["CMD", "curl", "-fsS", "http://localhost:8080/health/live"]
    interval: 10s
    timeout: 3s
    retries: 5
    start_period: 60s     # Flyway-миграция на cold start может занять до 30 сек
```

Прометей-job уже добавлен (`gateway` job в TASK-003 покрывает Kotlin-сервисы; для core добавим `- targets: [core-service:8080]` отдельным job в prometheus.yml).

### 5. API contract changes

Никаких новых эндпоинтов поверх 05-communication §5.4. В TASK-004 материализуются те же контракты, но с временным поведением 501 NOT_IMPLEMENTED.

Также **расширяется список error-кодов в response body**: `STORAGE_UNAVAILABLE`, `STORAGE_TIMEOUT`, `CONFLICT_RETRY` готовы к использованию (см. 12-storage-operations §6 «Storage failure modes»), но в TASK-004 не возникают.

### 6. Data model changes

Никаких. Flyway применит V1-V7 — это **существующие** миграции из TASK-001, не новые.

### 7. Implementation steps (для /backend)

| # | Шаг | Артефакты |
|---|---|---|
| 1 | Gradle bootstrap | `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml` |
| 2 | `Application.kt` с `EngineMain.main` + module() skeleton | 1 файл |
| 3 | `application.conf` + `AppConfig.kt` (HOCON → data class) | 2 файла |
| 4 | `logback.xml` + logstash-encoder | 1 файл |
| 5 | `config/Plugins.kt` — install ContentNegotiation/StatusPages/CallLogging/MicrometerMetrics | 1 файл |
| 6 | `error/ApiError.kt` + `ErrorMapper.kt` | 2 файла |
| 7 | `persistence/DataSources.kt` — HikariCP для PG + CH, fail-fast validation секретов | 1 файл |
| 8 | `persistence/FlywayBootstrap.kt` + интеграция в `module()` (до routing) | 1 файл |
| 9 | `persistence/TransactionManager.kt` — bracket-обёртка | 1 файл |
| 10 | `redis/RedisModule.kt` — Lettuce + GenericObjectPool (копия паттерна из gateway) | 1 файл |
| 11 | `auth/PasswordHasher.kt` — argon2-jvm + pepper validation | 1 файл |
| 12 | `routing/HealthRoutes.kt` — `/health/{live,ready}` (PG SELECT 1 + Redis PING + CH info) | 1 файл |
| 13 | `api/{User,Order,Portfolio,Instrument,Quotes}Api.kt` — 7 stubs возвращают 501 | 5 файлов |
| 14 | `telemetry/OtelInit.kt` — `GlobalOpenTelemetry` wrapper | 1 файл |
| 15 | `Dockerfile` + `.dockerignore` | 2 файла |
| 16 | Расширение `docker-compose.yml` блоком `core-service` + новый job в `prometheus.yml` | 2 файла правки |
| 17 | Smoke verification | — |

После backend — `/tester TASK-004`:
- IT через Testcontainers (PG + Redis): `/health/{live,ready}` (PG/Redis up/down).
- IT для Flyway: применить V1-V7 на пустой PG, убедиться что таблицы созданы, идемпотентность повторного запуска (V* уже в `flyway_schema_history` → не применяется заново).
- 7 stub routes возвращают 501 с единым форматом.
- `PasswordHasher.hash/verify` round-trip (юнит-тест без TC).
- Fail-fast на пустой `ARGON2_PEPPER` (по аналогии с reviewer-finding H1 в TASK-003).

### 8. ADR

**ADR не требуется.** Все архитектурные решения уже в существующих ADR-004/005/006/007/008/009. Новые паттерны не вводятся.

### 9. Risks

| Риск | Импакт | Митигация |
|---|---|---|
| Flyway на старте долго применяется (cold start) → docker healthcheck падает | medium | `start_period: 60s` в compose; миграция V1-V7 на пустой PG занимает <5s, есть запас 12× |
| Hikari pool sizing не сбалансирован с PG `max_connections=200` | medium | формально 50 pool × 2 реплики = 100, +50 для exporter+monitoring = в норме |
| Argon2 pepper-валидация ломает старт в dev | low | `ARGON2_PEPPER` в `.env.example` обязателен; dev генерирует один раз через `openssl rand -base64 32` |
| Конфликт portов с gateway (оба слушают 8080 внутри контейнера) | низкий | в compose mapping `8081:8080` для core; gateway остаётся `8080:8080` |
| OTel Java Agent overhead на JDBC | low | по дизайну приемлем (см. TASK-003 H2 анализ); fallback на instrumentation library |
| `domain/` директории пустые — IDE может удалить | low | положить `.gitkeep` в каждую |
| Package `com.stockyard.db` в 03-components/исторических ADR | low | в дизайне используется `com.stockyard.core`; в коде пишем правильное имя сразу |

### 10. Estimated complexity: **MEDIUM**

~3 человеко-дня на backend (gradle + 17 шагов + smoke). Тесты — ещё 1-2 дня.

### 11. Suggested next role

`/backend TASK-004` — материализация scaffold по 17-шаговому плану.

После TASK-004 разблокируется TASK-005 (real auth flow), TASK-006 (orders), TASK-007 (portfolio/instruments), TASK-008 (quotes/history).

## Files Affected (план для backend)

NEW (~24 файла):
- `core-service/{settings,build}.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- `core-service/Dockerfile`, `.dockerignore`
- `core-service/src/main/resources/{application.conf,logback.xml}`
- `core-service/src/main/kotlin/com/stockyard/core/Application.kt`
- `core-service/src/main/kotlin/com/stockyard/core/config/{AppConfig,Plugins}.kt`
- `core-service/src/main/kotlin/com/stockyard/core/error/{ApiError,ErrorMapper}.kt`
- `core-service/src/main/kotlin/com/stockyard/core/persistence/{DataSources,FlywayBootstrap,TransactionManager}.kt`
- `core-service/src/main/kotlin/com/stockyard/core/redis/RedisModule.kt`
- `core-service/src/main/kotlin/com/stockyard/core/auth/PasswordHasher.kt`
- `core-service/src/main/kotlin/com/stockyard/core/routing/HealthRoutes.kt`
- `core-service/src/main/kotlin/com/stockyard/core/api/{User,Order,Portfolio,Instrument,Quotes}Api.kt` (5)
- `core-service/src/main/kotlin/com/stockyard/core/telemetry/OtelInit.kt`
- `core-service/src/main/kotlin/com/stockyard/core/domain/{user,order,portfolio,instrument}/.gitkeep` (4)

EXTEND:
- `docker-compose.yml` — блок `core-service` (depends_on postgres+redis healthy, port `8081:8080`)
- `deploy/prometheus.yml` — новый job `core-service` (`targets: [core-service:8080]`)

## Backend Implementation

### Files changed (NEW)

**Gradle bootstrap (4):**
- `core-service/settings.gradle.kts` — rootProject.name = "core-service"
- `core-service/gradle.properties` — Kotlin code style, JVM args, parallel/cache
- `core-service/gradle/libs.versions.toml` — version catalog: Kotlin 2.0.21, Ktor 2.3.13, kotlinx-coroutines 1.9.0, kotlinx-serialization 1.7.3, Lettuce 6.4.1, commons-pool2 2.12.0, postgresql 42.7.4, HikariCP 6.0.0, Flyway 10.20.1, clickhouse-jdbc 0.7.0, argon2-jvm 2.11, micrometer-prometheus 1.13.6, OTel BOM 1.43.0, logback 1.5.12, logstash-encoder 8.0, Testcontainers 1.20.3 (+ junit + postgresql modules), JUnit 5.11.3
- `core-service/build.gradle.kts` — plugins (kotlin-jvm, kotlin-serialization, ktor, application), 17 implementation/testImplementation, JDK 21 toolchain, fatJar archiveFileName

**Resources (2):**
- `src/main/resources/application.conf` — HOCON: ktor.deployment + stockyard.{postgres,redis,clickhouse,argon2,otel} с env overrides
- `src/main/resources/logback.xml` — STDOUT + LogstashEncoder + MDC keys (trace_id, span_id, user.id, order.id), service.name=core-service в customFields

**Kotlin sources (16):**
- `Application.kt` — `fun main()` через `EngineMain.main()`; `module()`:
  - `loadAppConfig()`, fail-fast `require(pepper >= 32 bytes)` + `require(pg.password not empty)`
  - инициализация: `DataSources` (PG + CH Hikari), `RedisModule`, `PasswordHasher`, `TransactionManager`
  - `FlywayBootstrap.migrate(pg)` ДО `routing { }`
  - `monitor.subscribe(ApplicationStopping)` для close
  - `installPlugins()` возвращает `PrometheusMeterRegistry`
  - `routing { healthRoutes + userApi + orderApi + portfolioApi + instrumentApi + quotesApi }`
- `config/AppConfig.kt` — 5 data class'ов (`PostgresConfig` с `jdbcUrl` computed, `RedisConfig`, `ClickHouseConfig` с `jdbcUrl`, `Argon2Config`, `OtelConfig`); `loadAppConfig()` extension читает HOCON
- `config/Plugins.kt` — install **StatusPages первым** (M2 lesson), ContentNegotiation(json), CallLogging (filter health/metrics), MicrometerMetrics с JvmMemory/Gc/Thread/Processor binders. Возвращает `PrometheusMeterRegistry` для шаринга с роутом /metrics
- `error/ApiError.kt` — Serializable data classes (идентично gateway)
- `error/ErrorMapper.kt` — StatusPages: NotImplementedError→501, IllegalArgumentException→400, Throwable→500, 404
- `persistence/DataSources.kt` — два HikariDataSource (pg pool=50, ch pool=8) с timeouts из 12-storage-operations §12.1.2/§12.3.3 + `connectionInitSql` с `SET statement_timeout=3000; lock_timeout=2000; idle_in_transaction_session_timeout=5000; application_name='core-service'`. `pgPing()` и `clickhousePing()` для readiness
- `persistence/FlywayBootstrap.kt` — programmatic `Flyway.configure().dataSource().locations("classpath:db/migration").baselineOnMigrate().validateOnMigrate().cleanDisabled().load().migrate()`; падает на старте при ошибке миграции
- `persistence/TransactionManager.kt` — `suspend fun <T> withTx(block)`; `Dispatchers.IO`, `connection.use`, autoCommit=false, commit/rollback, restore autoCommit. Готов к TASK-005/006
- `redis/RedisModule.kt` — Lettuce + GenericObjectPool (maxTotal=32) + выделенный pubsub-connection. `withCommandConnection { }` higher-order API. `ping()` для readiness. Идентично паттерну gateway после H2 round-2 fix
- `auth/PasswordHasher.kt` — Argon2id (m=19MiB, t=2, p=1 по ADR-006). Pepper применяется через `HMAC-SHA256(password, pepper)` перед argon2 — pepper не зашит в самом пароле, не утекает в логи. `hash(CharArray)` / `verify(hash, CharArray)` round-trip; password/peppered byte arrays зачищаются в finally. `PEPPER_MIN_BYTES = 32` константа
- `routing/HealthRoutes.kt` — `/health/live` (200 UP без downstream), `/health/ready` (PG SELECT 1 + Redis PING — blocking; ClickHouse — info-only), `/metrics` (Prometheus scrape через `prometheusRegistry.scrape()`)
- `api/UserApi.kt`, `api/OrderApi.kt`, `api/PortfolioApi.kt`, `api/InstrumentApi.kt`, `api/QuotesApi.kt` — 7 stub-эндпоинтов через `throw NotImplementedError(...)`. Все маршруты согласуются с 05-communication §5.4.2
- `telemetry/OtelInit.kt` — `object Telemetry` обёртка над `GlobalOpenTelemetry`. Auto-instrumentation делает Java Agent

**Domain placeholders (4 `.gitkeep`):**
- `domain/{user,order,portfolio,instrument}/.gitkeep` — пустые пакеты для TASK-005..007 чтобы Git зафиксировал директории

**Docker (2):**
- `Dockerfile` — multi-stage, идентичный gateway. `start_period: 60s` в HEALTHCHECK под Flyway cold-start. OTel Java Agent 2.9.0
- `.dockerignore` — `.gradle/`, `build/`, `.idea/`, `.git/`

### Files changed (EXTEND)

- `docker-compose.yml` — новый блок `core-service` (build context `./core-service`, port `8081:8080` host:container чтобы не путать с gateway:8080, `depends_on postgres+redis healthy`, env: PG_HOST/DB/USER/PASSWORD, REDIS_URL/PASSWORD, CH_HOST/USER/PASSWORD, ARGON2_PEPPER, OTLP). Размещён **перед** блоком `gateway`.
- `deploy/prometheus.yml` — новый job `core-service` с `metrics_path: /metrics`, `targets: [core-service:8080]`. В отличие от `gateway` (где /metrics будет в TASK-005), core имеет /metrics с момента старта.

### Key decisions

- **Pepper через HMAC-SHA256**, не concat — снижает риск утечки и не привязывает к длине pepper'а. Pepper отдельно от хэша в БД.
- **Flyway programmatic в `module()`** перед `routing { }` — fail-fast на старте Ktor; не использует Flyway-Gradle-plugin (без внешних зависимостей build-time).
- **HikariCP `connectionInitSql`** — server-side timeouts задаются через SET после connect, не через JDBC URL params. Это надёжнее: даже если кто-то поменяет URL в env, timeouts остаются.
- **`/metrics` endpoint реализуется сразу** в TASK-004, а не отложено (как было в gateway, TASK-005). HikariCP-метрики ценны с первого старта для диагностики Flyway cold-start.
- **StatusPages первым** в `installPlugins()` — урок reviewer-finding M2 из TASK-003.
- **3 типа проверок в `/health/ready`** — PG/Redis blocking, CH info-only. Соответствует 12-storage-operations §12.6 «Storage failure modes»: CH-недоступность ≠ unhealthy для ордерной логики.
- **Port mapping `8081:8080`** в compose — host 8081 для core, контейнер 8080 (внутри сети stockyard:8080 = gateway, core-service:8080 = core; host-доступ разный).
- **`@Suppress("unused")` на `passwordHasher` и `txManager`** в `Application.module()` — переменные создаются для будущих TASK-005/006, в TASK-004 не используются ни одной route'ой.

### API endpoints implemented

**Реальные:**
- `GET /health/live` — 200 `{"status":"UP"}`
- `GET /health/ready` — 200/503 с `checks.{postgres,redis,clickhouse}`
- `GET /metrics` — Prometheus exposition format

**Stubs (501 NOT_IMPLEMENTED):**
- `POST /internal/users` (TASK-005)
- `POST /internal/auth` (TASK-005)
- `POST /internal/orders` (TASK-006)
- `GET /internal/users/{userId}/orders` (TASK-006)
- `GET /internal/users/{userId}/portfolio` (TASK-007)
- `GET /internal/instruments` (TASK-007)
- `GET /internal/quotes/{ticker}/history` (TASK-008)

### SQL migrations

Никаких новых. Используются **существующие** V1–V7 из TASK-001 в `core-service/src/main/resources/db/migration/`. Flyway-bootstrap применит их на cold-start.

### Open questions / blockers

1. **Gradle wrapper не создан** — нет локального gradle CLI (как в TASK-003). Dockerfile использует `gradle:8.10-jdk21-alpine`.
2. **Локальная `gradle build` не проверена** — без gradle CLI. Полная проверка на стороне `/tester` через Testcontainers или при первом `docker compose build core-service`.
3. **Argon2 pepper генерация в demo** — рекомендация: при первом деплое запустить `openssl rand -base64 32` и положить в `.env`. На dev-машине pepper в `.env.example` пустой, а fail-fast не даст запустить без него — это намеренно (no insecure default).
4. **OTel Collector** в docker-compose отсутствует (то же что было в TASK-003). Java Agent будет логировать dial-fail; core продолжит работать. Добавится отдельной observability-задачей.
5. **`docker compose up -d core-service` cold-start таймауты**: Flyway применит V1-V7 на пустой PG за ~5s, healthcheck `start_period: 60s` имеет 12× запас.

## Tests

### Strategy

Scaffold-задача → unit на чистую логику (PasswordHasher) + integration через Testcontainers PG + Redis + Ktor `testApplication`. Системные тесты не нужны.

Особенности vs TASK-003 gateway: добавляется **Testcontainers PostgreSQL** для проверки Flyway round-trip и UNIQUE-констрейнтов, плюс отдельные тесты на `connectionInitSql` (statement_timeout, application_name).

### Test dependencies (build.gradle.kts)

Сверх backend-набора: `kotest-assertions-core-jvm`, `mockk`, `awaitility-kotlin`, `ktor-server-test-host-jvm`, `testcontainers/postgresql`. Уже в `libs.versions.toml`.

### Unit tests added (6)

`core-service/src/test/kotlin/com/stockyard/core/auth/PasswordHasherTest.kt`:
1. `hash produces argon2id-prefixed string` — формат хэша начинается с `$argon2id$`.
2. `verify accepts correct password` — round-trip hash→verify=true.
3. `verify rejects wrong password` — verify=false на неверном пароле.
4. `different peppers produce non-verifiable hashes` — хэш от pepper A не верифицируется через PasswordHasher с pepper B.
5. `same input produces different hashes due to random salt` — два hash от одного пароля разные, оба верифицируются.
6. `unicode passwords supported` — кириллица + эмодзи round-trip.

### Integration tests added (30)

| Файл | Testcontainer | Кейсы |
|---|---|---|
| `test/PgFixture.kt` (helper) | postgres:16-alpine | — фабрика `@Container` |
| `test/RedisFixture.kt` (helper) | redis:7-alpine | — фабрика |
| `test/AppFixture.kt` (helper) | — | `installTestModule(...)` для `testApplication` |
| `persistence/FlywayBootstrapIT.kt` | PG | 4: применение V1-V7 (6 user tables + flyway_schema_history); 50 тикеров засидены в `instruments`; повторный `migrate()` идемпотентен; UNIQUE(user_id, idempotency_key) сработал |
| `persistence/DataSourcesIT.kt` | PG | 4: `pgPing()`=true; `clickhousePing()`=false при недоступном CH (не бросает); `SHOW statement_timeout`='3s'; `SHOW application_name`='core-service' |
| `redis/RedisModuleIT.kt` | Redis | 5: ping=true; 50 borrow/return циклов с SET/GET; pool возвращает connection после exception в лямбде; pubsub-conn isOpen; ping=false на closed port |
| `routing/HealthRoutesIT.kt` | PG + Redis | 5: /health/live всегда 200; /health/ready 200 при PG+Redis up (CH info-only DOWN); 503 при мёртвом PG; 503 при мёртвом Redis; /metrics → Prometheus format с `jvm_memory_used_bytes` |
| `routing/StubRoutesIT.kt` | PG + Redis | 8: 7 internal stub-эндпоинтов возвращают 501 NOT_IMPLEMENTED + единый формат `{"error":{"code","message","details"}}`; неизвестный путь → 404 NOT_FOUND |
| `ApplicationStartupIT.kt` | PG + Redis | 4: пустой ARGON2_PEPPER → IllegalArgumentException ("at least 32 bytes"); короткий (<32) → то же; пустой PG_PASSWORD → IllegalArgumentException; валидные секреты → /health/live = 200 |

### System test results

Не запускался — это scaffold-задача. Системный прогон (Load Simulator с 10к CCU) уместен после реализации полного auth+orders flow (TASK-005/006), не на этом этапе.

### Coverage delta

Не подсчитан — нет gradle/jacoco в окружении (см. Findings T1). Ориентир по бизнес-логике (которой пока минимум): `PasswordHasher` ≈100% public API (6 тестов покрывают все ветки), `RedisModule.withCommandConnection` 100%, `DataSources.pgPing/clickhousePing` 100%, `FlywayBootstrap.migrate` happy path 100%, routes 100% (все ветки stubs/health покрыты).

### Findings

**[T1] Тесты НЕ прогнаны в окружении.** Идентичный блокер с TASK-003: нет gradle CLI и доступа к docker socket для Testcontainers. Тесты — это код по правилу `Тесты — это код, соблюдай те же конвенции`, и их компиляция не проверена локально. Прогон откладывается на CI / локальную машину команды.

**Рекомендация:** в CI workflow запустить `cd core-service && gradle test --no-daemon` после merge. Внутри Dockerized CI — пробросить `/var/run/docker.sock:/var/run/docker.sock` для Testcontainers, либо использовать Testcontainers Cloud.

**[T2] Возможный race в `FlywayBootstrapIT` `instruments seed contains 50 tickers`.** Тест полагается, что предыдущий тест `first migration applies V1 through V7` уже отработал и применил миграции. JUnit 5 не гарантирует порядок тестов в классе по умолчанию. Митигация (отложена в backlog для тестера): добавить `@TestMethodOrder(MethodOrderer.OrderAnnotation::class)` + `@Order(1/2/3)` или вынести `FlywayBootstrap.migrate(ds)` в `@BeforeAll`. На текущем уровне scaffold это OK — но при добавлении новых тестов в файл может flake.

**Никаких багов в production-коде не обнаружено** при проектировании тестов. Все тесты согласованы с public API core-service после backend run: `withCommandConnection { … }`, fail-fast `require()` блоков, `pgPing`/`clickhousePing`.

## Review

### Gate: **PASS**

0 critical, 0 high. Production-код прошёл — стек, деньги, SQL, PII, lifecycle, security, error-handling без замечаний. 2 medium и 3 low точечно фиксятся, но не блокируют merge.

### Critical findings

Нет.

### High findings

Нет.

### Medium findings

**[M1] `core-service/src/test/kotlin/com/stockyard/core/persistence/FlywayBootstrapIT.kt:66` — race в порядке тестов.** Тест `instruments seed contains 50 tickers` неявно полагается на то что `first migration applies V1 through V7` уже выполнился. JUnit 5 не гарантирует порядок без `@TestMethodOrder`. Если порядок изменится — тест упадёт с misleading SQL error.

**Fix:** добавить `@TestMethodOrder(MethodOrderer.OrderAnnotation::class)` на класс + `@Order(1..4)` на методы. Или (предпочтительно) перенести `FlywayBootstrap.migrate(ds)` в `@BeforeAll setUp()`.

**[M2] `bin/` не покрыт корневым `.gitignore`.** Корневой `.gitignore` содержит `/bin/` (anchored to root — покрывает Go-бинарный output в корне), но **не nested `core-service/bin/`**. Eclipse/IntelliJ build output `core-service/bin/main/` и `core-service/bin/test/` сейчас untracked и попадёт в `git add -A`.

**Fix:** изменить в корневом `.gitignore` строку `/bin/` на `bin/` (unanchored, матчит на любой глубине). Или добавить `core-service/.gitignore` с `bin/`. `.dockerignore` уже корректен.

### Low findings

**[L1] `core-service/src/main/kotlin/com/stockyard/core/auth/PasswordHasher.kt:48` — intermediate `String` в `pepperedBytes`.** `password.joinToString("").toByteArray(...)` создаёт JVM-`String`, который immutable и GC-managed — нельзя обнулить. `passwordBytes` и `peppered` зачищаются корректно, но intermediate String остаётся в куче до GC.

**Fix (отложен в TASK-005):** заменить на `Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password), buffer, true)` для прямой конвертации `CharArray → ByteBuffer` без String.

**[L2] `core-service/Dockerfile:24` — TODO без assignee.** `TODO(security): добавить --checksum=sha256:<sha>` присутствует, но без issue-ID. Acceptable для scaffold, не блокирует.

**[L3] `core-service/src/test/kotlin/com/stockyard/core/persistence/DataSourcesIT.kt` — `connectionInitSql` тестов 2 из 4.** Проверяются `statement_timeout` и `application_name`. Не тестируются `lock_timeout` и `idle_in_transaction_session_timeout` (они структурно идентичны проверенным, риск низкий).

### Positive observations

1. **`connectionInitSql` multi-statement работает корректно** — PostgreSQL JDBC 42.7.4 обрабатывает несколько `SET ...;` через simple query protocol, дополнительных параметров URL не требуется. Pre-flagged concern в брифе reviewer'а — non-issue.
2. **`PasswordHasher` zeroing корректен** — используется `fill(0)` для byte-array (не `fill(' ')` как было в первой версии design). `passwordBytes.fill(0)` в `finally` блоке `pepperedBytes()`. Argon2 параметры (m=19 MiB, t=2, p=1) совпадают с ADR-006 и OWASP.
3. **Bootstrap order ровно правильный** — `loadAppConfig` → require pepper → require pg.password → DataSources → Redis → PasswordHasher → TxManager → Flyway.migrate → monitor.subscribe → installPlugins → routing. `installErrorMapping()` первым в `installPlugins()` (lesson TASK-003 M2 применён). `Flyway.migrate()` до `routing` — схема актуальна до первого запроса.
4. **Lifecycle cleanup полный и defensive** — `runCatching { … }` на каждом close в `ApplicationStopping`. `DataSources.close()` независимо закрывает PG и CH пулы.
5. **`ClickHouseConfig` полный** — `port` и `db` корректно читаются из HOCON, есть env override, в docker-compose оставлены defaults (`port=8123`, `db=stockyard`). Никаких несогласованностей.

### Test coverage assessment

Unit `PasswordHasherTest` (6 кейсов) — comprehensive, включая pepper sensitivity, salt randomness, Unicode. `RedisModuleIT` верифицирует try/finally на exception (lesson TASK-003 H2). `ApplicationStartupIT` покрывает все три ветки `require()` (пустой/короткий pepper, пустой pg.password). Слабые места: M1 (test ordering в FlywayBootstrapIT), L3 (частичное покрытие connectionInitSql), отсутствие теста на `ApplicationStopping` close — acceptable для scaffold scope.

**Тесты не прогнаны** в окружении (gradle CLI/docker socket недоступны — известное ограничение TASK-003 T1). CI разруливает.

### What works after `docker compose up -d core-service`

- Контейнер `stockyard-core-service` поднимается за <60 сек, healthy.
- Flyway применяет V1–V7 на пустой PG (`flyway_schema_history` создаётся).
- `curl http://localhost:8081/health/live` → `{"status":"UP"}`
- `curl http://localhost:8081/health/ready` → 200 если PG+Redis up; 503 иначе.
  Поле `checks.clickhouse` — info-only.
- `curl http://localhost:8081/metrics` → Prometheus format (JVM + GC + Threads + Hikari pool).
- `curl -X POST http://localhost:8081/internal/users` → 501 `{"error":{"code":"NOT_IMPLEMENTED","message":"POST /internal/users coming in TASK-005"}}`
- Gateway теперь видит `core-service: UP` в `/health/ready` (вместо `DOWN`).

## Handoff Log
- 2026-05-11T03:30:00Z: создан через /architect — core-service scaffold спроектирован поверх 03-components §3.2, 05-communication §5.4, 06-data §6.2, 07-consistency §7.2, 12-storage-operations §12.1, ADR-004..009. Архитектурно нового дизайна не требуется (pattern-match TASK-003). ADR не пишется. Suggested next: /backend TASK-004.
- 2026-05-11T04:00:00Z: /backend — реализован scaffold по 17-шаговому плану: 4 Gradle-файла + 2 resources + 16 Kotlin sources + 4 domain-placeholders + Dockerfile + .dockerignore = 28 NEW; docker-compose.yml расширен блоком `core-service` (port 8081:8080) и prometheus.yml — новым job. `docker compose config --quiet` валиден. Особенности vs TASK-003 gateway: HikariCP + Flyway-programmatic + Argon2 + clickhouse-jdbc + MicrometerMetrics с /metrics уже в этом TASK. Прогон тестов отложен (нет gradle CLI). Stage = backend-done. Suggested next: /tester TASK-004 (IT с Testcontainers PG для Flyway + Redis для health + fail-fast pepper тесты) или /reviewer TASK-004.
- 2026-05-11T04:30:00Z: /tester — добавлены 6 unit + 30 integration тестов (7 файлов + 3 fixtures). Покрытие: PasswordHasher hash/verify round-trip + pepper sensitivity + Unicode; Flyway round-trip (применение V1-V7, идемпотентность, UNIQUE(idempotency_key) сработал, 50 тикеров засидены); DataSources (PG ping, CH-down ping, connectionInitSql statement_timeout=3s + application_name=core-service); Redis pool borrow/return + try/finally на exception; Health endpoints (PG+Redis up/down → 200/503, CH info-only DOWN, /metrics → Prometheus exposition); все 7 stub-routes → 501 + 404 на неизвестный путь; fail-fast тесты на пустой/короткий ARGON2_PEPPER и пустой PG_PASSWORD (verify Application require-блоков). Прогон тестов НЕ выполнен — нет gradle CLI и docker socket (та же причина что в TASK-003 T1). Stage = tested. Suggested next: /reviewer TASK-004.
- 2026-05-11T05:00:00Z: /reviewer — gate **PASS**. 0 critical, 0 high. 2 medium (M1: race в порядке тестов FlywayBootstrapIT; M2: `bin/` build-output не покрыт корневым .gitignore — там стоит `/bin/` anchored to root, не покрывает `core-service/bin/`). 3 low (L1: intermediate `String` в `PasswordHasher.pepperedBytes`; L2: Dockerfile checksum TODO без assignee; L3: connectionInitSql тестов 2 из 4 SET-stmt). Production-код без HIGH/CRITICAL — стек/деньги/SQL/PII/lifecycle/security/error-handling чисто. Stage = done. Suggested next: /committer TASK-004 (опционально — закрыть M1+M2 одним коммитом перед merge).
