# 12. Эксплуатация уровня хранения

Операционная часть PostgreSQL, Redis/KeyDB и ClickHouse под целевую нагрузку 10 000 CCU: connection pools, таймауты, конфиги, health-checks, миграции, сидинг, бэкапы, failure modes.

Дополняет [06. Архитектура данных](06-data.md) (что и где хранится), [07. Согласованность](07-consistency.md) (как пишем) и [09. Наблюдаемость](09-observability.md) (см. также §9.12 — storage-specific OTel).

---

## 12.1. PostgreSQL — operations

### 12.1.1. `postgresql.conf` (demo, 4 GB RAM)

```ini
# Memory (для контейнера 4 GB)
shared_buffers = 1GB                 # 25% от RAM
effective_cache_size = 3GB           # 75%
work_mem = 16MB                      # на сортировку/хэш
maintenance_work_mem = 256MB

# Connections
max_connections = 200                # пул 50 × 2 реплики Core Service + запас под exporter
listen_addresses = '*'

# WAL / checkpoint
wal_level = replica
max_wal_size = 1GB
min_wal_size = 80MB
checkpoint_completion_target = 0.9

# Statement-level tracking (нужно для метрик)
shared_preload_libraries = 'pg_stat_statements'
pg_stat_statements.track = all
pg_stat_statements.max = 10000

# Logging slow / lock
log_min_duration_statement = 500ms
log_lock_waits = on
log_temp_files = 0
log_line_prefix = '%m [%p] %u@%d trace=%a '
```

Файл монтируется в контейнер как `/etc/postgresql/postgresql.conf` и активируется `command: postgres -c config_file=...`.

### 12.1.2. HikariCP (Core Service)

```kotlin
hikari {
    jdbcUrl                  = "jdbc:postgresql://postgres:5432/stockyard"
    driverClassName          = "org.postgresql.Driver"
    username                 = ${PG_USER}
    password                 = ${PG_PASSWORD}

    maximumPoolSize          = 50          // ориентир из §8.5; по 25 на реплику × 2 реплики
    minimumIdle              = 10
    connectionTimeout        = 1000        // ms — fail-fast при перегрузке PG
    validationTimeout        = 500
    idleTimeout              = 600000      // 10 min
    maxLifetime              = 1800000     // 30 min — ротация раньше server-timeout
    keepaliveTime            = 60000
    leakDetectionThreshold   = 5000        // 5 s — словить «забытую» TX

    connectionTestQuery      = "SELECT 1"
    initializationFailTimeout = 10000      // 10 s до первого подключения

    metricRegistry           = micrometerRegistry   // Micrometer → OTLP
}

// На уровне connection после получения:
defaultStatementTimeout    = 3000   // ordering pipeline укладывается
defaultLockTimeout         = 2000
defaultIdleInTxTimeout     = 5000   // убивать «забытые» TX
applicationName            = "core-service"
```

Обоснование размера пула: см. [08. §8.5](08-scaling.md). На 170 TPS (10к CCU × 1 ордер/мин), при p95 latency TX ~70 мс, минимально нужно ~12 соединений; 50 — с запасом × 4 для бурстов + select-only запросов.

### 12.1.3. Health probes

| Probe | Команда | Timeout | Где используется |
|---|---|---|---|
| docker `HEALTHCHECK` | `pg_isready -U stockyard -d stockyard` | 5s × 5 retries | docker-compose / orchestrator |
| Service `/health/ready` | `SELECT 1` через HikariCP `connectionTestQuery` | 500ms | k8s-style readiness, traffic gating |
| Service `/health/live` | `true` без обращений к БД | — | liveness, не вызывает рестарт при PG-down |

### 12.1.4. Migrations & seeding (Flyway)

Расположение:

```
core-service/src/main/resources/db/migration/
├── V1__init_users_accounts.sql        # users, accounts (06-data §6.2.2)
├── V2__instruments.sql                # instruments DDL + DML 50 тикеров (см. seed/instruments-50.md)
├── V3__orders.sql                     # orders + UNIQUE(user_id, idempotency_key)
├── V4__positions.sql
├── V5__transactions.sql
├── V6__indexes_perf.sql               # idx_orders_user_ticker
└── V7__pg_stat_statements.sql         # CREATE EXTENSION + GRANT pg_monitor monitoring
```

`flyway.conf`:

```
flyway.url=jdbc:postgresql://postgres:5432/stockyard
flyway.user=${PG_USER}
flyway.password=${PG_PASSWORD}
flyway.locations=classpath:db/migration
flyway.baselineOnMigrate=true
flyway.outOfOrder=false
flyway.validateOnMigrate=true
flyway.cleanDisabled=true              # запрещаем clean в demo/prod
```

CI-flow:

1. **Unit/IT**: Testcontainers поднимает свежий PG → Flyway применяет все V*. Изоляция тестов гарантирована.
2. **Demo deploy**: entrypoint контейнера `core-service` запускает Flyway-shadow перед стартом приложения.
3. **Откат**: в dev — `flyway clean && flyway migrate` (только при `FLYWAY_CLEAN_ENABLED=true`). В demo/prod — никогда; правим вперёд (V8, V9, …).

**Seed-юзеры для dev** — НЕ Flyway-миграция, а отдельный профильный SQL:

```
core-service/src/main/resources/db/seed/
└── dev_users.sql           # 5 юзеров, депозит 1 000 000 cents, готовые позиции для smoke
```

Применяется руками или `make seed-dev`. Содержит захардкоженные `argon2id` хэши пароля `test123` (зависят от `ARGON2_PEPPER` — генерируется при первом старте demo, **не коммитим**). В CI и production не выполняется.

### 12.1.5. Backup & restore

Скрипт `deploy/postgres/backup.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
ts=$(date +%Y%m%d_%H%M)
pg_dump -U stockyard -d stockyard \
  --format=custom --compress=9 \
  --file=/backups/stockyard_${ts}.dump
find /backups -name 'stockyard_*.dump' -mtime +7 -delete
```

Cron на хосте (не в контейнере):

```
0 3 * * *  docker exec stockyard-postgres /backup.sh >> /var/log/stockyard-backup.log 2>&1
```

Retention 7 дней. Smoke-restore раз в неделю (manual чек-лист): `pg_restore --list` + полный `pg_restore` на изолированный контейнер + сравнение `count(*)` ключевых таблиц с prod-объёмами. Точка эволюции 📦 — sidecar `supercronic` контейнер вместо хостового cron.

### 12.1.6. Resource limits в docker-compose

```yaml
postgres:
  image: postgres:16-alpine
  deploy:
    resources:
      limits:       { cpus: '2.0', memory: 4G }
      reservations: { cpus: '1.0', memory: 2G }
  volumes:
    - pg-data:/var/lib/postgresql/data                                 # ~20 GB на хосте
    - ./postgres/postgresql.conf:/etc/postgresql/postgresql.conf:ro
    - ./postgres/backup.sh:/backup.sh:ro
    - ./volumes/backups/postgres:/backups
  command: postgres -c config_file=/etc/postgresql/postgresql.conf
  healthcheck:
    test:        ["CMD-SHELL", "pg_isready -U stockyard -d stockyard"]
    interval:    10s
    timeout:     5s
    retries:     5
    start_period: 30s
  environment:
    POSTGRES_USER:     stockyard
    POSTGRES_PASSWORD: ${PG_PASSWORD}
    POSTGRES_DB:       stockyard
```

Volume 20 GB — десятикратный запас от объёма годовой БД (~2 GB по `06-data §6.6`).

---

## 12.2. Redis / KeyDB — operations

### 12.2.0. DevPriceFixture (временный writer `quotes:*` до TASK-008)

До реализации Quotes Service (TASK-008) единственный writer `quotes:{ticker}` HASH — это `DevPriceFixture` в Core Service. Он стартует в `Application.module()` если `STOCKYARD_DEV_FIXTURE=true` (default `true` в dev), читает 50 тикеров из `instruments` и каждые 5 секунд делает random walk ±0.5% по bid/ask/last с записью через `HSET`. В prod-like окружении выключается через `STOCKYARD_DEV_FIXTURE=false`. Удаляется одним коммитом после TASK-008.

### 12.2.1. Один или два инстанса

В `06-data §6.3.4` упомянуто «для production-like — отдельный инстанс под Pub/Sub без eviction». **Решение для MVP: один инстанс**:

- Pub/Sub не использует eviction (push-семантика, ничего не хранит).
- `stream:quotes` ограничен `MAXLEN ~ 100k` явно при `XADD`.
- Сессии и refresh-токены — TTL, evict-able.

📦 Backlog: разделить, если на нагрузке pub/sub-fanout начнёт лагать (см. §9.8 алертов).

### 12.2.2. `redis.conf`

```ini
# Memory
maxmemory 1gb
maxmemory-policy allkeys-lru

# Persistence: AOF off (refresh/cache восстановимы), RDB hourly если есть данные
save 3600 1
appendonly no

# Network
tcp-keepalive 60
timeout 0
bind 0.0.0.0
protected-mode no                  # внутренняя docker-сеть
requirepass ${REDIS_PASSWORD}

# Pub/Sub: kick subscriber'ов, отстающих > 60 c с буфером > 32 MB
client-output-buffer-limit pubsub 32mb 8mb 60

# Streams: MAXLEN устанавливается в XADD на стороне Quotes Service, см. 06-data §6.3.2
```

### 12.2.3. Lettuce (Kotlin: Gateway + Core Service)

```kotlin
val redisUri = RedisURI.Builder.redis("redis", 6379)
    .withPassword(System.getenv("REDIS_PASSWORD").toCharArray())
    .withTimeout(Duration.ofMillis(500))     // command timeout
    .withDatabase(0)
    .build()

val clientOptions = ClientOptions.builder()
    .autoReconnect(true)
    .disconnectedBehavior(REJECT_COMMANDS)   // fail-fast при разрыве, не копить очередь
    .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(500)))
    .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(2)).build())
    .build()

// Pool (commons-pool2)
val poolConfig = GenericObjectPoolConfig<StatefulRedisConnection<String, String>>().apply {
    maxTotal           = 32
    maxIdle            = 16
    minIdle            = 4
    testOnBorrow       = false           // полагаемся на autoReconnect
    blockWhenExhausted = true
    maxWait            = Duration.ofMillis(500)
}
```

Pub/Sub-подключение — **отдельный, выделенный** `StatefulRedisPubSubConnection`, **не из пула**. Один на процесс Gateway, переподключается с экспоненциальным backoff.

### 12.2.4. go-redis (Quotes Service)

```go
rdb := redis.NewClient(&redis.Options{
    Addr:            "redis:6379",
    Password:        os.Getenv("REDIS_PASSWORD"),
    DB:              0,

    PoolSize:        16,                     // 50 PUBLISH/sec + HSET — c запасом
    MinIdleConns:    4,
    MaxIdleConns:    8,
    PoolTimeout:     500 * time.Millisecond,
    ConnMaxIdleTime: 5 * time.Minute,
    ConnMaxLifetime: 30 * time.Minute,

    DialTimeout:  2 * time.Second,
    ReadTimeout:  500 * time.Millisecond,
    WriteTimeout: 500 * time.Millisecond,

    MaxRetries:       2,                     // PUBLISH/HSET идемпотентны на нашем уровне
    MinRetryBackoff:  8 * time.Millisecond,
    MaxRetryBackoff:  100 * time.Millisecond,
})
```

### 12.2.5. Health probes

| Probe | Команда | Timeout |
|---|---|---|
| docker `HEALTHCHECK` | `redis-cli -a $REDIS_PASSWORD PING` → `PONG` | 3s × 5 retries |
| Service `/health/ready` | `PING` через клиент | 200ms |

### 12.2.6. Resource limits

```yaml
redis:
  image: redis:7-alpine
  deploy:
    resources:
      limits:       { cpus: '1.0', memory: 1.5G }   # запас над maxmemory 1G
      reservations: { cpus: '0.5', memory: 1G }
  volumes:
    - redis-data:/data
    - ./redis/redis.conf:/usr/local/etc/redis/redis.conf:ro
  command: redis-server /usr/local/etc/redis/redis.conf
  healthcheck:
    test:     ["CMD", "redis-cli", "-a", "${REDIS_PASSWORD}", "ping"]
    interval: 10s
    timeout:  3s
    retries:  5
```

### 12.2.7. Backup

RDB-снапшот `save 3600 1` пишется в `redis-data` volume. Бэкап = ежесуточное копирование `dump.rdb` на хост (cron). Retention 3 дня. Refresh-tokens восстановимы (юзеры разлогинятся), кэш котировок восстановится за 1 секунду — full backup некритичен.

---

## 12.3. ClickHouse — operations

### 12.3.1. Ключевые секции `config.xml` / `users.xml`

```xml
<!-- /etc/clickhouse-server/config.d/stockyard.xml -->
<clickhouse>
  <max_connections>256</max_connections>
  <keep_alive_timeout>10</keep_alive_timeout>
  <max_concurrent_queries>32</max_concurrent_queries>
  <mark_cache_size>536870912</mark_cache_size>             <!-- 512 MB -->
  <uncompressed_cache_size>1073741824</uncompressed_cache_size>  <!-- 1 GB -->

  <merge_tree>
    <parts_to_delay_insert>300</parts_to_delay_insert>
    <parts_to_throw_insert>600</parts_to_throw_insert>
    <max_parts_in_total>10000</max_parts_in_total>
  </merge_tree>

  <prometheus>
    <endpoint>/metrics</endpoint>
    <port>9363</port>
    <metrics>true</metrics>
    <events>true</events>
    <asynchronous_metrics>true</asynchronous_metrics>
  </prometheus>
</clickhouse>
```

```xml
<!-- /etc/clickhouse-server/users.d/stockyard.xml -->
<clickhouse>
  <profiles>
    <default>
      <max_memory_usage>2000000000</max_memory_usage>          <!-- 2 GB -->
      <max_execution_time>10</max_execution_time>
      <readonly>0</readonly>
      <async_insert>1</async_insert>
      <wait_for_async_insert>0</wait_for_async_insert>
      <async_insert_max_data_size>10485760</async_insert_max_data_size>
      <async_insert_busy_timeout_ms>1000</async_insert_busy_timeout_ms>
    </default>
  </profiles>
</clickhouse>
```

`async_insert` — нативная батчёвка CH. Сложение с application-batch (§12.3.2) даёт два уровня буферизации: наш батч уменьшает RPS до CH, async_insert сглаживает остатки.

### 12.3.2. clickhouse-go (Quotes Service)

```go
const (
    batchMaxRows     = 1000             // ~50 ticks/sec × 20 sec
    batchMaxAge      = 1 * time.Second  // §8.5
    batchFlushOnFull = true
)

opts := &clickhouse.Options{
    Addr: []string{"clickhouse:9000"},
    Auth: clickhouse.Auth{
        Database: "stockyard",
        Username: "stockyard",
        Password: os.Getenv("CH_PASSWORD"),
    },
    DialTimeout:     5 * time.Second,
    ReadTimeout:     10 * time.Second,
    MaxOpenConns:    8,
    MaxIdleConns:    4,
    ConnMaxLifetime: 30 * time.Minute,
    Compression:     &clickhouse.Compression{Method: clickhouse.CompressionLZ4},
    BlockBufferSize: 10,
    Settings: clickhouse.Settings{
        "async_insert":          1,
        "wait_for_async_insert": 0,
    },
}
```

Семантика батча: «либо `batchMaxRows` достигнуто, либо прошёл `batchMaxAge` — флашим». При недоступности CH буфер растёт до `batchMaxRows × 10 = 10 000`, дальше дроп с метрикой `stockyard_clickhouse_dropped_rows_total{reason="ch_unavailable"}`.

### 12.3.3. clickhouse-jdbc (Core Service для свечей)

```kotlin
// DataSource через clickhouse-jdbc (com.clickhouse:clickhouse-jdbc)
val url = "jdbc:ch://clickhouse:8123/stockyard?compress=lz4&socket_timeout=10000"
hikari {
    jdbcUrl              = url
    username             = ${CH_USER}
    password             = ${CH_PASSWORD}
    maximumPoolSize      = 8                  // запросы свечей не частые, p95 < 100 мс
    minimumIdle          = 2
    connectionTimeout    = 2000
    idleTimeout          = 300000             // 5 min
    maxLifetime          = 1800000
    connectionTestQuery  = "SELECT 1 FORMAT TabSeparated"
}
queryTimeout = 5000   // на уровне Statement
```

### 12.3.4. Health probes

| Probe | Команда | Timeout |
|---|---|---|
| docker `HEALTHCHECK` | `wget -q --spider http://localhost:8123/ping` (`Ok.`) | 5s × 5 retries |
| Service `/health/ready` | `SELECT 1 FORMAT TabSeparated` через jdbc | 1s |

### 12.3.5. Resource limits

```yaml
clickhouse:
  image: clickhouse/clickhouse-server:24-alpine
  deploy:
    resources:
      limits:       { cpus: '2.0', memory: 4G }
      reservations: { cpus: '1.0', memory: 2G }
  ulimits:
    nofile: { soft: 262144, hard: 262144 }
  volumes:
    - ch-data:/var/lib/clickhouse
    - ./clickhouse/config.xml:/etc/clickhouse-server/config.d/stockyard.xml:ro
    - ./clickhouse/users.xml:/etc/clickhouse-server/users.d/stockyard.xml:ro
    - ./clickhouse/init.sql:/docker-entrypoint-initdb.d/init.sql:ro
  healthcheck:
    test:         ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:8123/ping"]
    interval:     10s
    timeout:      5s
    retries:      5
    start_period: 30s
```

`init.sql` — DDL `quotes_ticks` и MV `quotes_candles_1m`/`_1h` из [06-data §6.4](06-data.md#64-clickhouse-схема), идемпотентно `CREATE … IF NOT EXISTS`.

### 12.3.6. Backup

`06-data §6.7` зафиксировал: «бэкап CH необязателен для MVP, тики восстановимы из `stream:quotes` за последний час, остальное — несущественно». Точка эволюции 📦 — `clickhouse-backup` утилита с заливкой в S3-совместимое хранилище.

### 12.3.7. Retention/lifecycle

DDL уже содержит `TTL toStartOfMonth(ts) + INTERVAL 6 MONTH`. CH прогоняет TTL во время merge — отдельный cron не нужен. Мониторим число партов:

```sql
SELECT count() FROM system.parts WHERE table = 'quotes_ticks' AND active;
```

Включается в Grafana «Storage» дашборд (см. §9.7).

---

## 12.4. Configuration management

`.env` рядом с `docker-compose.yml`, **не коммитится** (`.gitignore`). Шаблон `.env.example` коммитится с пустыми значениями секретов.

```bash
# Storage credentials
PG_USER=stockyard
PG_PASSWORD=                  # openssl rand -base64 32
PG_DB=stockyard
REDIS_PASSWORD=               # openssl rand -base64 32
CH_USER=stockyard
CH_PASSWORD=                  # openssl rand -base64 32

# Application secrets
JWT_SECRET=                   # openssl rand -base64 64
ARGON2_PEPPER=                # openssl rand -base64 32

# Observability
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
OTEL_SERVICE_NAME=core-service  # переопределяется на сервис в docker-compose
DEPLOYMENT_ENVIRONMENT=dev    # dev | demo
```

Hot-reload не поддерживается — перезапуск контейнера = новая конфигурация.

---

## 12.5. Storage exporters → Prometheus

Каждое хранилище — отдельный exporter / встроенный endpoint, попадает в Prometheus и далее в Grafana.

| Хранилище | Источник | Порт | Ключевые метрики |
|---|---|---|---|
| PostgreSQL | `prometheuscommunity/postgres_exporter` (sidecar контейнер) | 9187 | `pg_up`, `pg_database_size_bytes`, `pg_stat_activity_count{state=}`, `pg_stat_database_xact_commit/rollback`, `pg_locks_count`, `pg_stat_statements_*` |
| Redis | `oliver006/redis_exporter` (sidecar) | 9121 | `redis_up`, `redis_connected_clients`, `redis_memory_used_bytes`, `redis_keyspace_hits/misses_total`, `redis_evicted_keys_total`, `redis_pubsub_channels`, `redis_commands_total{cmd=}` |
| ClickHouse | builtin endpoint `/metrics` | 9363 | `ClickHouseAsyncMetrics_*`, `ClickHouseMetrics_PartsActive/Query/Merge`, `ClickHouseProfileEvents_InsertedRows/FailedInsertQuery` |

`prometheus.yml` (фрагмент):

```yaml
scrape_configs:
  - job_name: postgres_exporter
    static_configs: [{ targets: ['postgres-exporter:9187'] }]
  - job_name: redis_exporter
    static_configs: [{ targets: ['redis-exporter:9121'] }]
  - job_name: clickhouse
    static_configs: [{ targets: ['clickhouse:9363'] }]
```

`postgres_exporter` подключается под отдельным read-only role:

```sql
-- V7 миграция (фрагмент)
CREATE ROLE monitoring LOGIN PASSWORD '${MONITORING_PASSWORD}';
GRANT pg_monitor TO monitoring;
```

### Health-метрика на стороне сервисов

Каждый Stockyard-сервис экспортирует:

```
stockyard_storage_up{store="postgres"|"redis"|"clickhouse", service="core-service"|"quotes-service"|"api-gateway"}  =  0 | 1
```

Снимается в health-loop (раз в 5 сек), используется для Grafana-панели «Storage availability matrix».

OTel-spans на storage-операциях, конкретные attributes и метрики Hikari/Lettuce/redisotel — см. [09. §9.12 Storage instrumentation](09-observability.md#912-storage-instrumentation).

---

## 12.6. Storage failure modes & recovery

| Сценарий | Поведение системы | SLO impact | Восстановление |
|---|---|---|---|
| **PG primary down** | Core Service `/ready` → unhealthy. Все ордерные эндпоинты + auth → `503 STORAGE_UNAVAILABLE`. Котировки в WS продолжают идти (Gateway → Redis Pub/Sub) | торговая часть полностью недоступна | docker-restart PG; HikariCP `autoReconnect`; ~1–2 мин на restart + 10 с на pool-warmup |
| **Redis down** | Quotes Service не PUBLISH/HSET → `stockyard_quotes_published_total` flat, `stockyard_storage_up{store=redis}=0`. GW теряет Pub/Sub → новые WS-клиенты не получают тиков. **Размещение ордера → fail-fast 503** (нужен HGET для цены). Sessions/refresh — Redis → новые login невозможны, валидные JWT в памяти GW работают до 15 мин | торговля + котировки лежат | docker-restart; кэш `quotes:*` восстанавливается за ~1 сек после первого тика; `stream:quotes` пустой (acceptable) |
| **ClickHouse down** | Quotes Service батчёр копит в памяти до 10 000 строк (~3 минуты на 50 ticks/sec), дальше дропает с `stockyard_clickhouse_dropped_rows_total`. **Текущие котировки в Redis работают, ордера исполняются** (CH не на критическом пути). UI-графики свечей пустые/деградируют → Mobile показывает «история недоступна», live-цена в порядке | только история свечей | docker-restart; накопленный батч флашится на reconnect; история за окно простоя — потеряна (acceptable) |
| **Redis cold-start** | Quotes Service на старте сидит стартовые цены: `HSET quotes:<ticker> ...` для всех 50. Драйвер даёт первый тик ≤ 1 сек. Sessions пустые → все юзеры разлогиниваются | UX — массовый re-login | штатно, секунды |
| **PG: deadlock на ордере** | PG детектит cycle, откатывает одну TX → Core Service ловит `deadlock_detected` (40P01) → retry один раз с jitter 50 мс → если опять, `409 CONFLICT_RETRY` | редкое (FOR UPDATE по разным строкам упорядочивает) | автоматически |
| **PG: statement_timeout 3 с** | TX abort → `503 STORAGE_TIMEOUT` → клиент по идемпотентности может ретраить (тот же `Idempotency-Key`) | редкое | автоматически через идемпотентность |
| **Redis Pub/Sub: subscriber отстаёт** | `client-output-buffer-limit pubsub 32mb 8mb 60` → Redis kick-нёт GW → GW переподключение → §7.6.2: snapshot + live | <1 сек gap | автоматически |

Эти сценарии переходят в integration-тесты (см. [11. Стратегия тестирования](11-testing.md)) — `docker stop` посреди прогона, проверка корректных HTTP-кодов и метрик.

---

## 12.7. Где что лежит

`docker-compose.yml` располагается **в корне проекта** (стандартная практика — `docker compose up -d` без флагов). Конфигурационные ансамбли каждого хранилища и init-скрипты — в `deploy/`, чтобы не захламлять корень XML/conf-файлами. Volumes для бэкапов — `volumes/backups/` в корне (bind-mount, не git-tracked).

```
stockyard/
├── docker-compose.yml                  # в корне, ссылается на deploy/* configs
├── .env                                # секреты — НЕ в git
├── .env.example                        # шаблон с пустыми значениями — в git
├── .gitignore                          # игнорирует .env, volumes/, target/, build/, .gradle/, node_modules/
├── deploy/
│   ├── postgres/
│   │   ├── postgresql.conf
│   │   └── backup.sh
│   ├── redis/
│   │   └── redis.conf
│   ├── clickhouse/
│   │   ├── config.xml                  # /etc/clickhouse-server/config.d/stockyard.xml
│   │   ├── users.xml                   # /etc/clickhouse-server/users.d/stockyard.xml
│   │   └── init.sql                    # /docker-entrypoint-initdb.d/init.sql — DDL ticks + MV
│   └── prometheus.yml
├── core-service/
│   └── src/main/resources/db/
│       ├── migration/                  # Flyway, прогоняется на старте core-service
│       │   ├── V1__init_users_accounts.sql
│       │   ├── V2__instruments.sql     # DDL + DML 50 тикеров
│       │   ├── V3__orders.sql
│       │   ├── V4__positions.sql
│       │   ├── V5__transactions.sql
│       │   ├── V6__indexes_perf.sql
│       │   └── V7__pg_stat_statements.sql
│       └── seed/
│           └── dev_users.sql           # только для dev, применяется руками
├── quotes-service/
│   ├── config/
│   │   └── seed-prices.yaml            # стартовые цены 50 тикеров для Redis HSET
│   └── internal/
│       ├── storage/redis.go
│       ├── storage/clickhouse.go
│       └── telemetry/ch_span.go
└── volumes/
    └── backups/
        └── postgres/                   # bind-mount на хост, retention 7 дней
```

Запуск целиком одной командой из корня:

```bash
cp .env.example .env && vi .env        # заполнить пароли
docker compose up -d
docker compose ps                       # все три storage-контейнера → healthy за < 60 сек
```

PostgreSQL Flyway-миграции **не запускаются docker-compose'ом напрямую** — их прогоняет entrypoint контейнера `core-service` перед стартом приложения (или вручную через Gradle-task `core-service:flywayMigrate` в dev). Это нужно, чтобы миграции версионировались вместе с приложением, а не с инфраструктурой.

ClickHouse `init.sql` (DDL `quotes_ticks` + Materialized Views для свечей) **запускается docker-compose'ом** через `/docker-entrypoint-initdb.d/` — CH-специфика, удобно. Миграционная история в CH тривиальная (одна-две DDL за весь MVP), полноценный Flyway не нужен; новые DDL добавляются как `init-002.sql`, `init-003.sql` и т.д. с `CREATE … IF NOT EXISTS`.

Redis init не нужен — стартовые цены `quotes:<ticker>` посыпает Quotes Service при старте из своего `config/seed-prices.yaml`.

---

## 12.8. Чек-лист готовности

- [ ] Все три хранилища поднимаются через `docker-compose up -d` за < 60 сек.
- [ ] `docker-compose ps` показывает `healthy` для всех трёх.
- [ ] `core-service /health/ready` отвечает 200 после применения V1–V7.
- [ ] `seed/instruments-50.md` соответствует `instruments` в БД (50 строк).
- [ ] postgres_exporter, redis_exporter, ClickHouse `/metrics` собираются Prometheus.
- [ ] Метрики `hikaricp_*`, `redis_*`, `ClickHouse*`, `stockyard_storage_up` видны в Grafana.
- [ ] Smoke-тест: `docker stop redis` → ордера падают с `503 STORAGE_UNAVAILABLE`, `start redis` → восстановление < 5 сек.
- [ ] Backup-cron на хосте срабатывает; `.dump` появляется в `volumes/backups/postgres/`.
- [ ] `.env.example` без реальных секретов; `.env` в `.gitignore`.

---

## Связанные документы

- ⬅ [06. Архитектура данных](06-data.md) — DDL, ключи, schema CH.
- ⬅ [07. Согласованность и транзакции](07-consistency.md) — TX, идемпотентность.
- ⬅ [09. Наблюдаемость](09-observability.md) — общая OTel-инструментация; **§9.12** — storage-specific.
- ➡ [seed/instruments-50.md](seed/instruments-50.md) — список тикеров.
- ➡ [adr/ADR-007](adr/ADR-007-idempotency-key-retention.md) — retention idempotency-key.
- ➡ [adr/ADR-008](adr/ADR-008-pg-no-partitioning-mvp.md) — без партиционирования в MVP.
