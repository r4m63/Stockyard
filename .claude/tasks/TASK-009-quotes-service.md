# TASK-009: Go Quotes Service

## Meta
- ID: TASK-009
- Created: 2026-05-11T18:00:00Z
- Last updated: 2026-05-11T18:00:00Z
- Stage: architect-done
- Touched roles: architect

## Original Request
TASK-008 — quotes pipeline (Driver + Quotes Service + WS), декомпозиция на 4 подзадачи. Эта подзадача — Go Quotes Service.

## Pipeline Context
Вторая из 4 подзадач quotes pipeline. Зависит от **TASK-008** (нужен реальный или mock-драйвер). Producer для **TASK-010** (Gateway WS).

```
   TASK-008 ──┐
   (C driver) ├─▶ TASK-009 ──┐  ← THIS TASK
              │   (Quotes Go) │
              │               ├─▶ TASK-011
              │               │
   TASK-010 ──┘───────────────┘
```

Контракты, зафиксированные в TASK-008/010 и здесь:
- **C1** (consumer): `struct stockyard_tick` **44 байта** packed, little-endian — см. TASK-008. (Архитектор изначально написал 40, но `8+8+8+8+8+4 = 44`; reviewer TASK-008 подписал поправку, volume оставлен в struct.)
- **C2** (producer): Redis JSON payload, HSET / PUBLISH / XADD — здесь.

## Architect Design

### Affected components
- **NEW** `quotes-service/` (по 03-components §3.3):
  - `cmd/quotes/main.go`
  - `internal/driver/{reader,parser}.go`
  - `internal/pipeline/{fanout,tick}.go`
  - `internal/sinks/{redis,clickhouse}.go`
  - `internal/health/server.go`
  - `internal/telemetry/otel.go`
  - `internal/config/config.go`
  - `go.mod`, `go.sum`, `Dockerfile`
- **NEW** интеграционный test-стенд (Testcontainers-Go против Redis + CH).

Не затрагиваются: Core, Gateway, БД-схемы (есть в init.sql из TASK-001).

### API contract changes

#### Inbound (C1 — consumer)
Парсит `struct stockyard_tick` **44 байта**, layout из TASK-008. Offsets:
`ticker[8]@0 · ts_ns@8 · bid_cents@16 · ask_cents@24 · last_cents@32 · volume@40`.
Golden fixture для unit-теста (от TASK-008 tester'а):
```
ticker="SBER", ts_ns=0, bid=28550, ask=28570, last=28560, vol=12345
53 42 45 52 00 00 00 00  00 00 00 00 00 00 00 00
86 6f 00 00 00 00 00 00  9a 6f 00 00 00 00 00 00
90 6f 00 00 00 00 00 00  39 30 00 00
```
```go
type Tick struct {
    Ticker    string
    TsNs      uint64
    BidCents  int64
    AskCents  int64
    LastCents int64
    Volume    uint32
}
// parser.go: binary.LittleEndian + ручные offset-ы
```

#### Outbound to Redis — C2 contract (frozen)
```
HSET   quotes:{ticker} ts <iso8601_utc> ts_ns <uint64> bid <cents_int> ask <cents_int> last <cents_int> volume <int>
PUBLISH channel:quotes:{ticker} <JSON payload>
XADD   stream:quotes MAXLEN ~ 100000 * ticker <ticker> ts_ns <uint64> bid ... ask ... last ... volume ...
```

**JSON payload в PUBLISH (frozen, integer cents):**
```json
{
  "ticker": "SBER",
  "ts": "2026-05-09T12:34:56.789Z",
  "tsNs": 1746789296789012345,
  "bidCents":  28550,
  "askCents":  28570,
  "lastCents": 28560,
  "volume":    12345
}
```

Отличие от текущего §5.5.2 (где Decimal) — TASK-011 обновит документацию.

#### Outbound to ClickHouse
```sql
INSERT INTO quotes_ticks (ticker, ts, bid, ask, last, volume) VALUES (?, ...), (?, ...), ...
-- ts: DateTime64(3,'UTC') из tsNs
-- bid/ask/last: Decimal(18,4), конверсия cents/100.0 в Go
-- volume: UInt64
```
**Batch:** 1000 ticks OR 1s window, whichever first, `Prepare` + multi-row VALUES.

#### Health/metrics (internal, port 8080)
- `GET /healthz` → 200 if driver fd alive AND last tick < 5s
- `GET /readyz`  → 200 if Redis ping OK AND CH ping OK
- `GET /metrics` → Prometheus (ticks_total, ticks_dropped, redis_publish_errors, ch_batch_errors, ch_batch_size_p95)

### Data model changes
Использует существующие ключи/таблицы:
- Redis: `quotes:{ticker}` HASH, `channel:quotes:{ticker}` Pub/Sub, `stream:quotes` Stream (§6.3.2).
- ClickHouse: `quotes_ticks` MergeTree (§6.4.1, init.sql из TASK-001).

Новых ключей/таблиц нет.

### Implementation steps

**Backend (Go, single role):**

| # | Шаг | Файлы |
|---|---|---|
| 1 | Skeleton: `cmd/quotes/main.go` с graceful shutdown (`signal.NotifyContext`). Wire-up всех модулей. | `cmd/quotes/main.go` |
| 2 | `config.go` — envvars: `STOCKYARD_DRIVER_PATH=/dev/stockyard`, `STOCKYARD_REDIS_ADDR`, `STOCKYARD_CH_DSN`, `STOCKYARD_HEALTH_PORT`, `STOCKYARD_CH_BATCH_SIZE=1000`, `STOCKYARD_CH_BATCH_MS=1000`. | `internal/config/config.go` |
| 3 | `driver/reader.go` — `os.OpenFile(driverPath, O_RDONLY, 0)`, цикл `read` буфера на 64 тика → `[]Tick`. Reopen при error (exponential backoff). | `internal/driver/reader.go` |
| 4 | `driver/parser.go` — `binary.Read(LittleEndian)` или ручной offset-парсинг. Strip null-padding из `ticker[8]`. | `internal/driver/parser.go` |
| 5 | `pipeline/fanout.go` — input chan, два output chan (Redis, CH). Buffered. Drop-on-full для Redis (ADR-001 at-most-once); buffered для CH. | `internal/pipeline/fanout.go` |
| 6 | `sinks/redis.go` — `go-redis/v9`. На каждый тик: HSet + Publish + XAdd, pipelined (одна round-trip). Errors → log + counter, тик не retried. | `internal/sinks/redis.go` |
| 7 | `sinks/clickhouse.go` — `clickhouse-go/v2` native protocol. Batch accumulator. На batch_size/timer flush — `PrepareBatch + AppendStruct + Send`. Retry 3× с backoff, потом drop oldest + counter. | `internal/sinks/clickhouse.go` |
| 8 | `health/server.go` — net/http: /healthz, /readyz, /metrics. | `internal/health/server.go` |
| 9 | `telemetry/otel.go` — OTLP exporter, tracer (опционально для TASK-009; может уйти в TASK-011). | `internal/telemetry/otel.go` |
| 10 | Graceful shutdown chain: SIGTERM → cancel ctx → reader stops → fanout drains → Redis sink finishes → CH flushes → close fd → exit 0. | `cmd/quotes/main.go` |
| 11 | `Dockerfile` multi-stage (golang:1.22-alpine → distroless). Volume mount `/dev/stockyard`. | `Dockerfile` |
| 12 | `go.mod`: `go-redis/v9`, `clickhouse-go/v2`, `prometheus/client_golang`, `go.opentelemetry.io/otel`. | `go.mod` |

**Tester:**

| # | Шаг |
|---|---|
| T1 | Unit (parser): golden 40-byte hex из TASK-008 T3 → правильный `Tick`. |
| T2 | Unit (parser): неправильная длина буфера → error, не panic. |
| T3 | Unit (parser): non-ASCII в ticker[8] → корректный strip до null. |
| T4 | Unit (fanout): 100 ticks → 100 в Redis chan + 100 в CH chan. |
| T5 | Unit (CH batcher): 999 ticks за 500ms → не flush; 1000-й → flush. 100 ticks за 1100ms → flush по таймеру. |
| T6 | IT (Testcontainers Redis): publisher → subscriber на `channel:quotes:*` получает JSON с cents Long, ts ISO-8601. |
| T7 | IT (Redis): `HGETALL quotes:SBER` возвращает все поля. |
| T8 | IT (Redis): `XLEN stream:quotes > 0`, `XRANGE` корректные поля. |
| T9 | IT (CH): batch INSERT → `SELECT count() FROM quotes_ticks WHERE ticker='SBER'` == ожидаемый. |
| T10 | IT (mock driver через named pipe): сервис читает 50 тиков → все в Redis + CH. |
| T11 | IT (CH MV): после INSERT, `SELECT count() FROM quotes_candles_1m > 0`. |
| T12 | System (driver + service): на стенде с реальным `/dev/stockyard` метрика `ticks_total` растёт. |
| T13 | Chaos: Redis down → `ticks_dropped_redis` растёт, после восстановления — возобновление; сервис не падает. |
| T14 | Chaos: CH down → buffer растёт до лимита, потом drop oldest + log; сервис не падает. |
| T15 | Shutdown: SIGTERM → CH flush < 1s, exit 0. |

**Reviewer:**
- Cents-only в JSON payload PUBLISH (R2).
- Конверсия `cents/100.0` в CH-sink (как в TASK-007 review M2; HALF_UP fallback).
- Backpressure: Redis sink drop-on-full vs CH buffer-with-retry (R4).
- Goroutine leaks: все ctx-aware.
- Reopen `/dev/stockyard` после ошибки не зацикливается.

### ADR
**ADR-011 (NEW): Cents (int64) as wire format in Redis Pub/Sub JSON.**
- Context: §5.5.2 показывал Decimal, CLAUDE.md/TASK-007 — Long cents. Нужно зафиксировать.
- Decision: все цены в JSON — integer cents (int64). `ts` — ISO-8601 UTC string, `tsNs` — uint64.
- Consequences: Gateway десериализует в `Long`. ClickHouse-конверсия `cents/100.0 → Decimal(18,4)` локально в Go.
- Alternatives: Decimal string (теряет точность при Float), Float64 (плохо для денег).

**ADR-012 (NEW, опц.):** Drop-on-full для Redis sink vs durable buffer для CH sink.

### Risks с митигациями
| Риск | Likelihood | Impact | Митигация |
|---|---|---|---|
| Endianness mismatch с TASK-008 | Low (оба LE) | High | T1 golden-hex. |
| JSON schema mismatch с Gateway | Medium | High | ADR-011 + shared fixtures. |
| Redis backpressure | Low | High | Pipelining + drop-on-full. **needs clarification:** `client-output-buffer-limit pubsub` в нашем Redis конфиге. |
| CH batch теряется при крэше | Medium | Medium | At-most-once (ADR-001); 1s окно ограничивает потерю. |
| Reopen `/dev/stockyard` после ошибки → loop | Low | Medium | Backoff 100ms → 1s → 10s. |
| TASK-008 не готов | High by definition | Low | Mock-driver через named pipe для unit + IT. |

### Estimated complexity: **MEDIUM**
4–6 ч/дней. Knot — graceful shutdown chain + backpressure.

### Suggested next role
`/backend TASK-009` (один Go-разработчик).

## Handoff Log
- 2026-05-11T18:00:00Z: создан через /architect — design complete; suggested next: `/backend TASK-009` (после TASK-008 backend-done).
