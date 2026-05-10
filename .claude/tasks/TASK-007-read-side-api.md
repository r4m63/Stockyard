# TASK-007: Read-side API — portfolio, quotes, instruments

## Meta
- ID: TASK-007
- Created: 2026-05-11T14:00:00Z
- Last updated: 2026-05-11T17:00:00Z
- Stage: committed
- Touched roles: architect, backend, reviewer, committer

## Original Request
для TASK-007 — следующая задача (portfolio + current quote + quote history + instruments каталог).

## Architect Design

### 1. Контекст

Backend закрыл auth (TASK-005, v0.4.0) и orders (TASK-006, v0.5.0). У пользователя есть JWT, есть способ разместить BUY/SELL, в БД формируются `accounts`, `positions`, `transactions`. **Сейчас он не может увидеть, что у него на счёте.** Это блокирует UI-задачи TASK-009 (Android) и TASK-010 (RN) — экраны портфеля и графика без read-side не сделать.

Что уже готово:
- **`accounts` + `positions` заполняются** через `OrderService.place` (TASK-006).
- **`instruments` сидится 50 MOEX-тикерами** (V2 миграция, TASK-001).
- **Redis `quotes:{ticker}` HASH** пишется `DevPriceFixture` (TASK-006). После TASK-008 это заместит Quotes Service.
- **ClickHouse `quotes_ticks` + MV `quotes_candles_1m`/`_1h`** есть в схеме (`init.sql`, TASK-001), но пока пустые — туда не пишет ни один компонент.
- **5 stub-эндпоинтов** в gateway (`/v1/portfolio`, `/v1/quotes/{ticker}`, `/v1/quotes/{ticker}/history`, `/v1/instruments`) и 4 stub в core (`/internal/users/{id}/portfolio`, `/internal/instruments`, `/internal/quotes/{ticker}/history`) возвращают 501.

Задача — превратить эти stub'ы в real read-side, плюс расширить `DevPriceFixture` так, чтобы он попутно писал и в `quotes_ticks` (через ClickHouse JDBC), и history-эндпоинт начал отдавать ненулевые свечи без ожидания TASK-008.

### 2. Affected components

| Компонент | Что меняется |
|---|---|
| **Core Service** | `domain/portfolio/PortfolioService` (новый), `domain/portfolio/PositionRepository` extension (`listByUser`), `domain/account/AccountRepository` extension (`findBalance` без lock), `domain/instrument/InstrumentRepository` extension (`listAll`), `quotes/QuotesPort` extension (`getQuote`: bid/ask/last/ts одним HGETALL), `quotes/CandlesRepository` (новый, ClickHouse JDBC), `quotes/DevPriceFixture` (расширен — пишет также в `quotes_ticks`). 4 API-файла из 501 → real: `PortfolioApi`, `QuotesApi`, `InstrumentApi` + extension `UserApi` для `/internal/users/{id}/portfolio`. |
| **API Gateway** | 3 routing-файла из 501 → real: `PortfolioRoutes`, `QuotesRoutes`, `InstrumentsRoutes` (все 4 эндпоинта). Все public-эндпоинты внутри `authenticate("auth-jwt")` блока. `CoreServiceClient` дополнительно: `getPortfolio`, `getQuote`, `getQuoteHistory`, `listInstruments`. |
| **Mobile / RN** | не затрагиваются (UI — TASK-009/010). |
| **PostgreSQL** | использует существующие `accounts`, `positions`, `instruments`. **Никаких новых миграций.** |
| **Redis** | дополнительный read-only `HGETALL quotes:{ticker}` (вместо двух HGET для current quote). |
| **ClickHouse** | новый INSERT-канал в `quotes_ticks` (через расширенный `DevPriceFixture`); SELECT по `quotes_candles_1m`/`_1h` с `argMinMerge` / `argMaxMerge` / `maxMerge` / `minMerge` / `sumMerge` для history. |

### 3. API contract changes

Все контракты УЖЕ описаны в [05-communication.md §5.3.2](../../docs/architecture/05-communication.md#532-эндпоинты-rest). Переход 501 → real без shape-изменений.

#### 3.1. Public (Mobile ↔ Gateway)

```http
GET /v1/portfolio
Authorization: Bearer <JWT>

→ 200 OK
{
  "balance":    { "amountCents": 99714300, "currency": "RUB" },
  "positions": [
    {
      "ticker":           "SBER",
      "qty":              10,
      "avgPriceCents":    28570,
      "currentPriceCents": 28612,           # из Redis quotes:{ticker}.last; null если нет цены
      "unrealizedPnlCents": 420             # qty * (current - avg); null если нет current
    }
  ]
}

→ 401 UNAUTHORIZED   { "error": { "code": "UNAUTHORIZED" } }
→ 503 STORAGE_UNAVAILABLE   { "error": { "code": "STORAGE_UNAVAILABLE" } }
```

```http
GET /v1/quotes/{ticker}
Authorization: Bearer <JWT>

→ 200 OK
{
  "ticker":    "SBER",
  "bidCents":  28550,
  "askCents":  28570,
  "lastCents": 28560,
  "ts":        "2026-05-11T14:00:00.000Z"
}

→ 404 NOT_FOUND   { "error": { "code": "INSTRUMENT_NOT_FOUND" } }     # тикер не в каталоге
→ 422 NO_QUOTE_AVAILABLE   { "error": { "code": "NO_QUOTE_AVAILABLE" } }   # каталог OK, но цены нет в Redis
```

```http
GET /v1/quotes/{ticker}/history?from=2026-05-09T00:00:00Z&to=2026-05-09T23:59:59Z&interval=1m
Authorization: Bearer <JWT>

→ 200 OK
{
  "ticker":   "SBER",
  "interval": "1m",
  "candles": [
    {
      "ts":         "2026-05-09T00:00:00Z",
      "openCents":  28500,
      "highCents":  28620,
      "lowCents":   28470,
      "closeCents": 28590,
      "volume":     12345
    }
  ]
}

→ 404 INSTRUMENT_NOT_FOUND
→ 422 INVALID_INTERVAL    # interval не в {1m, 1h}
→ 422 INVALID_TIME_RANGE  # from >= to, либо span > 30 дней для 1m
```

Параметры `from`, `to` — ISO-8601, обязательные. `interval` — `1m` или `1h` (MVP). Максимальный span: 7 дней для `1m`, 90 дней для `1h` (защита от случайного `from=2000-01-01`).

```http
GET /v1/instruments
Authorization: Bearer <JWT>

→ 200 OK
{
  "items": [
    {"ticker":"SBER","name":"Сбербанк","type":"STOCK","lotSize":10},
    {"ticker":"GAZP","name":"Газпром",  "type":"STOCK","lotSize":10}
  ]
}
```

#### 3.2. Internal (Gateway → Core)

Симметричные internal-эндпоинты:

```http
GET /internal/users/{userId}/portfolio
→ 200 { "balance": {...}, "positions": [...] }

GET /internal/quotes/{ticker}
→ 200 { ticker, bidCents, askCents, lastCents, ts }
→ 404 INSTRUMENT_NOT_FOUND
→ 422 NO_QUOTE_AVAILABLE

GET /internal/quotes/{ticker}/history?from=&to=&interval=
→ 200 { ticker, interval, candles: [...] }
→ 404 INSTRUMENT_NOT_FOUND
→ 422 INVALID_INTERVAL | INVALID_TIME_RANGE

GET /internal/instruments
→ 200 { items: [...] }
```

### 4. Data model changes

**Никаких новых таблиц/миграций PG.** Расчёт `unrealizedPnlCents` — на gateway side (qty × (currentLastCents − avgPriceCents)) либо на core side; решено в core, чтобы клиенту не приходилось дублировать формулу.

**Redis** — добавляется один read-операция `HGETALL quotes:{ticker}` (раньше было два HGET bid/ask).

**ClickHouse**:
- `quotes_ticks` schema не меняется.
- `DevPriceFixture` начинает выполнять `INSERT INTO quotes_ticks (ticker, ts, bid, ask, last, volume) VALUES (?, ?, ?, ?, ?, ?)` для каждого тика. Batch-insert каждые 5 секунд (один statement на 50 тикеров). Materialized View `quotes_candles_1m_mv` автоматически агрегирует тики в `quotes_candles_1m`.
- **Важно:** `Decimal(18, 4)` в CH означает «цена в рублях с 4 знаками после запятой». DevPriceFixture хранит цены в копейках (`Long`). Конверсия `cents / 100.0` при INSERT'е приемлема **для dev-пайплайна** — это temporary feature. После TASK-008 Quotes Service переключится на свой формат, и DevPriceFixture исчезнет. Поэтому Decimal vs cents расхождение между Redis и CH — изолировано в DevPriceFixture, в API наружу всегда возвращаются `cents` (Long).

### 5. Сценарии — happy path

#### 5.1. GET /v1/portfolio (S7 из 10-scenarios.md, теперь реальный)

```
1. Mobile → Gateway:    GET /v1/portfolio, Bearer JWT
2. Gateway: validate JWT, extract userId; check session:{jti} in Redis
3. Gateway → Core:       GET /internal/users/{userId}/portfolio
4. Core (PortfolioService.read):
    параллельно:
    a. SELECT balance_cents, currency FROM accounts WHERE user_id = ? AND currency = 'RUB'
    b. SELECT ticker, qty, avg_price_cents FROM positions WHERE user_id = ?
    
    для каждого тикера из b: HGETALL quotes:{ticker} → currentLastCents (= last)
    
    compute unrealizedPnlCents = qty * (currentLastCents - avg_price_cents)  [null если currentLastCents=null]
5. Core ← Gateway: { balance, positions[] }
6. Gateway → Mobile: 200 + same shape
```

Параллельное чтение PG + Redis по тикерам — через `coroutineScope { … async { … }.awaitAll() }`. При 50 тикерах в портфеле — 50 параллельных Redis HGETALL. Lettuce-pool с maxTotal=32 → большая часть пойдёт sequentially; OK для MVP (50 ms latency × 2 RT). Если будет узким — batch через `mget` или Lua-script (📦 backlog).

#### 5.2. GET /v1/quotes/{ticker}

```
1. Gateway: validate JWT.
2. Gateway → Core: GET /internal/quotes/SBER
3. Core:
    a. SELECT 1 FROM instruments WHERE ticker = ? → если нет → 404 INSTRUMENT_NOT_FOUND
    b. HGETALL quotes:SBER → если empty → 422 NO_QUOTE_AVAILABLE
    c. Парсит bid/ask/last/ts из HASH → 200
```

#### 5.3. GET /v1/quotes/{ticker}/history

```
1. Validate ticker exists in instruments → 404.
2. Validate interval ∈ {1m, 1h} → 422.
3. Validate from < to и (to - from) <= max_span_for_interval → 422.
4. ClickHouse SELECT:
     SELECT ts_minute AS ts,
            argMinMerge(open)  AS open,
            argMaxMerge(close) AS close,
            maxMerge(high)     AS high,
            minMerge(low)      AS low,
            sumMerge(volume)   AS volume
     FROM quotes_candles_1m
     WHERE ticker = ? AND ts_minute BETWEEN ? AND ?
     GROUP BY ts_minute
     ORDER BY ts_minute
   (для 1h — `quotes_candles_1h` если есть, иначе агрегировать 1m через group-by к 1h на лету)
5. Конверсия Decimal cents: openDecimal * 100 (округление через banker's rounding не нужно — DevPriceFixture пишет ровные cents/100.0, обратное преобразование точное).
6. Return { ticker, interval, candles[] }
```

#### 5.4. GET /v1/instruments

Прямой `SELECT ticker, name, type, lot_size FROM instruments ORDER BY ticker` (~50 строк, без пагинации). Лёгкий cache-friendly запрос, можно вместо PG читать через in-memory кэш в Core с TTL 5 мин (📦 — premature, не делаем).

### 6. Implementation steps

#### 6.1. Core Service

| # | Шаг | Файлы |
|---|---|---|
| 1 | `domain/account/AccountRepository.kt` — добавить `findBalance(conn, userId, currency): Long?` (БЕЗ FOR UPDATE; для read-only). | `domain/account/AccountRepository.kt` |
| 2 | `domain/position/PositionRepository.kt` — добавить `listByUser(conn, userId): List<Position>`. | `domain/position/PositionRepository.kt` |
| 3 | `domain/instrument/InstrumentRepository.kt` — добавить `listAll(conn): List<Instrument>` и data class `Instrument(ticker, name, type, lotSize)`. | `domain/instrument/{Instrument,InstrumentRepository}.kt` |
| 4 | `quotes/QuotesPort.kt` — добавить `getQuote(ticker): Quote?` (single HGETALL), data class `Quote(bidCents, askCents, lastCents, tsEpochMs)`. | `quotes/QuotesPort.kt` |
| 5 | `quotes/CandlesRepository.kt` (новый) — ClickHouse JDBC через `DataSources.clickhouse`. Метод `loadCandles(ticker, fromEpochMs, toEpochMs, interval): List<Candle>`. Под капотом — SELECT с `argMinMerge`/`argMaxMerge`/`maxMerge`/`minMerge`/`sumMerge` из `quotes_candles_1m` или `_1h`. Конверсия Decimal → cents Long через `× 100` (предполагая чёткое умножение). | `quotes/CandlesRepository.kt` |
| 6 | `quotes/DevPriceFixture.kt` extension — на каждом цикле writeAll() **также** делает batch INSERT в `quotes_ticks` через `dataSources.clickhouse`. PreparedStatement c `addBatch`/`executeBatch`. **TODO(TASK-008): убрать вместе с самим DevPriceFixture.** | `quotes/DevPriceFixture.kt` |
| 7 | `domain/portfolio/PortfolioService.kt` (новый) — orchestrator: `getPortfolio(userId): Portfolio`. Внутри `tx.withTx` (read-committed; одна connection не вызывает FOR UPDATE) — параллельно через `coroutineScope { async { } }` SELECT balance + SELECT positions, потом HGETALL по тикерам через `QuotesPort.getQuote(ticker)`. Compute `unrealizedPnlCents`. | `domain/portfolio/PortfolioService.kt` |
| 8 | `domain/quotes/QuotesService.kt` (новый) — orchestrator: `getQuote(ticker)`, `getHistory(ticker, from, to, interval)`. Валидирует interval/range; делегирует в `InstrumentRepository.existsTicker` + `QuotesPort.getQuote` + `CandlesRepository.loadCandles`. | `domain/quotes/QuotesService.kt` |
| 9 | `domain/quotes/exceptions.kt` — `InstrumentNotFoundException(ticker)`, `InvalidIntervalException(raw)`, `InvalidTimeRangeException(reason)`. | `domain/quotes/exceptions.kt` |
| 10 | `api/PortfolioApi.kt` real impl: `GET /internal/users/{userId}/portfolio` + DTOs `InternalPortfolioResponse`, `InternalBalanceDto`, `InternalPositionDto`. | `api/PortfolioApi.kt`, `api/PortfolioDtos.kt` |
| 11 | `api/QuotesApi.kt` real impl: `GET /internal/quotes/{ticker}`, `GET /internal/quotes/{ticker}/history`. DTOs `InternalQuoteResponse`, `InternalCandleDto`, `InternalCandlesResponse`. | `api/QuotesApi.kt`, `api/QuotesDtos.kt` |
| 12 | `api/InstrumentApi.kt` real impl: `GET /internal/instruments`. DTOs `InternalInstrumentDto`, `InternalInstrumentsResponse`. | `api/InstrumentApi.kt`, `api/InstrumentsDtos.kt` |
| 13 | `error/ErrorMapper.kt` — добавить mappers для `InstrumentNotFoundException → 404 INSTRUMENT_NOT_FOUND`, `InvalidIntervalException → 422`, `InvalidTimeRangeException → 422`. (NoQuoteAvailable уже мапится из TASK-006.) | `error/ErrorMapper.kt` |
| 14 | `Application.kt` wire-up — создать `PortfolioService`, `QuotesService`, `CandlesRepository`; передать в API роуты. | `Application.kt` |

#### 6.2. Gateway Service

| # | Шаг | Файлы |
|---|---|---|
| 15 | `routing/PortfolioDtos.kt` — `PortfolioResponse`, `BalanceDto`, `PositionDto`. | `routing/PortfolioDtos.kt` |
| 16 | `routing/QuotesDtos.kt` — `QuoteResponse`, `CandleDto`, `CandlesResponse`. | `routing/QuotesDtos.kt` |
| 17 | `routing/InstrumentsDtos.kt` — `InstrumentDto`, `InstrumentsResponse`. | `routing/InstrumentsDtos.kt` |
| 18 | `client/CoreServiceClient.kt` — методы `getPortfolio(userId)`, `getQuote(ticker)`, `getQuoteHistory(ticker, from, to, interval)`, `listInstruments()`. Возвращают sealed-результаты для 404 / 422 / 5xx. | `client/CoreServiceClient.kt` |
| 19 | `auth/AuthExceptions.kt` — добавить `InstrumentNotFoundException`, `InvalidIntervalException`, `InvalidTimeRangeException` (mirror core). | `auth/AuthExceptions.kt` |
| 20 | `routing/PortfolioRoutes.kt` real: `authenticate("auth-jwt") { route("/v1/portfolio") { get { ... } } }`. | `routing/PortfolioRoutes.kt` |
| 21 | `routing/QuotesRoutes.kt` real: `authenticate("auth-jwt") { ... GET /v1/quotes/{ticker} + GET /v1/quotes/{ticker}/history }`. Парсит query-string from/to/interval; для bad input — `IllegalArgumentException` → 400. | `routing/QuotesRoutes.kt` |
| 22 | `routing/InstrumentsRoutes.kt` real: `authenticate("auth-jwt") { ... GET /v1/instruments }`. | `routing/InstrumentsRoutes.kt` |
| 23 | `error/ErrorMapper.kt` — mappers для `InstrumentNotFound → 404`, `InvalidInterval → 422`, `InvalidTimeRange → 422`. | `error/ErrorMapper.kt` |
| 24 | `Application.kt` — передать `coreClient` в `portfolioRoutes`, `quotesRoutes`, `instrumentsRoutes`. | `Application.kt` |

#### 6.3. Documentation

| # | Шаг | Файлы |
|---|---|---|
| 25 | `docs/architecture/05-communication.md` §5.3.2 — обновить shape примеров: `balance.amountCents` Long (вместо `balance: 100000.00`), `positions[].avgPriceCents`/`currentPriceCents`/`unrealizedPnlCents` Long. История: `openCents`/`highCents`/`lowCents`/`closeCents` Long. §5.7 — добавить `INSTRUMENT_NOT_FOUND (404)`, `INVALID_INTERVAL (422)`, `INVALID_TIME_RANGE (422)`. | `docs/architecture/05-communication.md` |
| 26 | `docs/architecture/12-storage-operations.md` §12.2.0 — отметить, что `DevPriceFixture` теперь пишет также в `quotes_ticks` (CH), чтобы history был ненулевым. После TASK-008 это переедет в Quotes Service. | `docs/architecture/12-storage-operations.md` |

### 7. Тестирование (для /tester)

#### Core Service

**Unit:**
- `PortfolioService.unrealizedPnl` — формула на разных входах (null current, exact match, deep negative).
- `QuotesService.validateInterval` — `1m` / `1h` valid, остальное → InvalidInterval.
- `QuotesService.validateRange` — from < to, span limits per interval.

**Integration (Testcontainers PG + Redis + ClickHouse):**
- IT-1: `GET /internal/users/{id}/portfolio` happy path после register + BUY → balance уменьшен, positions содержит 1 позицию, currentPrice взят из Redis (seeded), unrealizedPnl рассчитан правильно.
- IT-2: пользователь без позиций → `positions: []`, balance = initial.
- IT-3: позиция есть, но `quotes:{ticker}` отсутствует в Redis → `currentPriceCents: null`, `unrealizedPnlCents: null`.
- IT-4: `GET /internal/quotes/{ticker}` — happy + `NO_QUOTE_AVAILABLE` + `INSTRUMENT_NOT_FOUND`.
- IT-5: `GET /internal/quotes/{ticker}/history` — happy с записанными свечами (через DevPriceFixture за ~10 сек awaitility); пустой массив для далёкого from/to; INVALID_INTERVAL для `?interval=2h`; INVALID_TIME_RANGE для from >= to.
- IT-6: `GET /internal/instruments` — 50 тикеров MOEX, сортировка по ticker.
- IT-7: `DevPriceFixture` пишет также в `quotes_ticks` — после старта Application + awaitility(20s) проверка `SELECT count() FROM quotes_ticks WHERE ticker='SBER'` > 0.

#### Gateway

**Unit:**
- DTO query-parsing: `?from=garbage` → IllegalArgumentException → 400.
- `CoreServiceClient.getPortfolio` mapping (200/404/503).

**Integration (Testcontainers Redis + mock-core):**
- IT-8: `GET /v1/portfolio` без Authorization → 401.
- IT-9: `GET /v1/portfolio` happy через mock-core → 200 + shape OK.
- IT-10: `GET /v1/quotes/UNKNOWN` → mock-core 404 → gateway 404 INSTRUMENT_NOT_FOUND.
- IT-11: `GET /v1/quotes/SBER/history?interval=2h` → 422 INVALID_INTERVAL без обращения к core.
- IT-12: `GET /v1/instruments` → 200 со списком.

### 8. ADR

**Новых ADR не пишем.** Все паттерны уже зафиксированы:
- ADR-002 (ClickHouse для time-series) — используем materialized views для свечей.
- ADR-004 (single TX writer) — здесь только чтение, не требуется TX-writer гарантия.
- ADR-005 (idempotency) — read-only, не применимо.

Расширение `DevPriceFixture` writing'ом в ClickHouse — это temporary tweak в рамках уже зафиксированного решения «временный writer до TASK-008» (TASK-006 ledger §5), не новое решение.

### 9. Risks

| Риск | Импакт | Митигация |
|---|---|---|
| `DevPriceFixture` пишет в CH с конверсией `cents/100.0` → возможна потеря точности на нетривиальных ценах | low | dev-only; цены в seed детерминированы и кратны 1 копейке. Decimal(18,4) хватит на 14 знаков целых × 4 знака дробных. Точность копейка — округлений нет. |
| 50 параллельных HGETALL для портфеля при maxTotal=32 в Lettuce-пуле | low | pool блокирует на 18 запросах. Реальный потолок MVP — портфель из 5-10 позиций. При нагрузке >> 32 — batch через Lua-скрипт, помечено 📦. |
| ClickHouse MV-агрегаты на пустых данных | low | `argMinMerge` на пустой group вернёт `null`/0 — это нормально. Тест IT-5 включает пустой range. |
| `quotes_ticks` за 6 месяцев накапливается | low | TTL `INTERVAL 6 MONTH` уже в DDL, ClickHouse сам подчистит. |
| `current_price` берётся из Redis, может быть устаревшим (до 5 сек до TASK-008) | low | acceptable для MVP; пользователь видит current_price как «приблизительно». При желании можно показать `ts` в UI. |
| Decimal в CH ↔ Long cents в API — конверсия может потерять копейку при `Decimal(18,4).multiply(100)` если хранится не целое количество копеек | low | DevPriceFixture пишет ровно `cents.toDouble() / 100.0`, обратное `× 100` точное. В тестах IT-5 проверим round-trip. |

### 10. Estimated complexity: **MEDIUM**

~4-5 человеко-дней для одного backend разработчика:
- Core (steps 1-14): 2-3 дня.
- Gateway (steps 15-24): 1-1.5 дня.
- Документация (steps 25-26): 0.25 дня.

Тесты — ~2 дня для `/tester` (12 кейсов; CH-IT через Testcontainers ClickHouse — slightly slower).

### 11. Suggested next role

`/backend TASK-007` — 26-шаговая реализация.

После backend → `/tester TASK-007`.

После tester+reviewer → `/committer TASK-007`.

Параллельно — `/architect TASK-008` (Quotes pipeline: driver → Quotes Service → Redis → WS-fanout в Gateway) можно стартовать сразу после backend, чтобы не блокировать клиентов. Но это уже отдельный flow.

## Files Affected (план для backend)

NEW:
- `core-service/src/main/kotlin/com/stockyard/core/domain/portfolio/PortfolioService.kt`
- `core-service/src/main/kotlin/com/stockyard/core/domain/quotes/{QuotesService,exceptions}.kt`
- `core-service/src/main/kotlin/com/stockyard/core/quotes/CandlesRepository.kt`
- `core-service/src/main/kotlin/com/stockyard/core/domain/instrument/Instrument.kt`
- `core-service/src/main/kotlin/com/stockyard/core/api/{PortfolioDtos, QuotesDtos, InstrumentsDtos}.kt`
- `gateway-service/src/main/kotlin/com/stockyard/gateway/routing/{PortfolioDtos, QuotesDtos, InstrumentsDtos}.kt`

EXTEND:
- `core-service/src/main/kotlin/com/stockyard/core/domain/account/AccountRepository.kt` (findBalance no-lock)
- `core-service/src/main/kotlin/com/stockyard/core/domain/position/PositionRepository.kt` (listByUser)
- `core-service/src/main/kotlin/com/stockyard/core/domain/instrument/InstrumentRepository.kt` (listAll)
- `core-service/src/main/kotlin/com/stockyard/core/quotes/QuotesPort.kt` (getQuote с HGETALL)
- `core-service/src/main/kotlin/com/stockyard/core/quotes/DevPriceFixture.kt` (also write to quotes_ticks via CH)
- `core-service/src/main/kotlin/com/stockyard/core/api/{PortfolioApi, QuotesApi, InstrumentApi}.kt` (real impls)
- `core-service/src/main/kotlin/com/stockyard/core/Application.kt` (wire 3 new services)
- `core-service/src/main/kotlin/com/stockyard/core/error/ErrorMapper.kt` (3 new exceptions)
- `gateway-service/src/main/kotlin/com/stockyard/gateway/client/CoreServiceClient.kt` (4 new methods)
- `gateway-service/src/main/kotlin/com/stockyard/gateway/auth/AuthExceptions.kt` (3 new exceptions)
- `gateway-service/src/main/kotlin/com/stockyard/gateway/routing/{PortfolioRoutes, QuotesRoutes, InstrumentsRoutes}.kt` (real)
- `gateway-service/src/main/kotlin/com/stockyard/gateway/error/ErrorMapper.kt`
- `gateway-service/src/main/kotlin/com/stockyard/gateway/Application.kt`
- `docs/architecture/05-communication.md` (§5.3.2 shapes, §5.7 codes)
- `docs/architecture/12-storage-operations.md` (§12.2.0 DevPriceFixture + CH writes)

## Backend Implementation

### Files changed

#### Core Service (NEW)
- `domain/instrument/Instrument.kt` — data class (ticker, name, type, lotSize).
- `domain/quotes/{QuotesService, exceptions}.kt` — orchestrator + 3 exceptions (`InstrumentNotFound`, `InvalidInterval`, `InvalidTimeRange`).
- `domain/portfolio/PortfolioService.kt` — balance + positions + enrich current price из Redis + расчёт `unrealizedPnlCents`. Параллельный SELECT баланса и позиций через `coroutineScope { async {} }`.
- `quotes/CandlesRepository.kt` — ClickHouse JDBC; `loadCandles(ticker, from, to, interval)` через `argMinMerge`/`argMaxMerge`/`maxMerge`/`minMerge`/`sumMerge` на MV `quotes_candles_1m` / `_1h`. Конверсия `Decimal(18,4)` → `Long` cents через `×100`.
- `api/{PortfolioDtos, QuotesDtos, InstrumentsDtos}.kt` — DTO + `toDto()` mappers.

#### Core Service (EXTEND)
- `domain/account/AccountRepository.kt` — `findBalance(conn, userId, currency): Long?` без FOR UPDATE.
- `domain/position/PositionRepository.kt` — `listByUser(conn, userId): List<Position>` (только qty>0).
- `domain/instrument/InstrumentRepository.kt` — `listAll(conn): List<Instrument>`.
- `quotes/QuotesPort.kt` — `getQuote(ticker): Quote?` через одно `HGETALL` + новый data class `Quote(bidCents, askCents, lastCents, tsEpochMs)`.
- `quotes/DevPriceFixture.kt` — расширен: каждый цикл дополнительно делает batch INSERT в `quotes_ticks` через ClickHouse JDBC. `chDs` теперь параметр конструктора. Failure CH-write логируется, Redis-loop продолжает работать. Помечено `TODO(TASK-008)`.
- `api/{PortfolioApi, QuotesApi, InstrumentApi}.kt` — real handlers (заменены 501-stub'ы). Принимают service'ы как параметры.
- `error/ErrorMapper.kt` — мапперы `InstrumentNotFound → 404`, `InvalidInterval → 422`, `InvalidTimeRange → 422`.
- `Application.kt` — wire-up `PortfolioService`, `QuotesService`, `CandlesRepository`; `DevPriceFixture` получает `chDs = dataSources.clickhouse`.

#### Gateway Service (NEW)
- `routing/{PortfolioDtos, QuotesDtos, InstrumentsDtos}.kt` — public DTOs.

#### Gateway Service (EXTEND)
- `client/CoreServiceClient.kt` — методы `getPortfolio`, `getQuote` (sealed `QuoteResult`: Found/NotFound/Unavailable), `getQuoteHistory` (sealed `HistoryResult`: Ok/NotFound/InvalidInterval/InvalidRange), `listInstruments`.
- `auth/AuthExceptions.kt` — добавлены `InstrumentNotFoundException`, `InvalidIntervalException`, `InvalidTimeRangeException`.
- `routing/{PortfolioRoutes, QuotesRoutes, InstrumentsRoutes}.kt` — real handlers внутри `authenticate("auth-jwt")` блоков. `userId` всегда из JWT.
- `error/ErrorMapper.kt` — 3 новых mapper для read-side exceptions.
- `Application.kt` — передаёт `coreClient` в `portfolioRoutes(coreClient)`, `instrumentsRoutes(coreClient)`, `quotesRoutes(coreClient)`.

#### Documentation
- `docs/architecture/05-communication.md` §5.3.2 — пример портфеля переписан: `amountCents`, `avgPriceCents`, `currentPriceCents`, `unrealizedPnlCents` (Long), вместо Decimal `amount`/`avgPrice`. §5.7 — 3 новых error-кода (`INSTRUMENT_NOT_FOUND`, `INVALID_INTERVAL`, `INVALID_TIME_RANGE`).
- `docs/architecture/12-storage-operations.md` §12.2.0 — `DevPriceFixture` теперь dual-writer (Redis HSET + ClickHouse batch INSERT); MV сама агрегирует свечи.

### Key decisions

1. **`DevPriceFixture` расширен до dual-writer (Redis + ClickHouse).** Альтернатива — пустой history эндпоинт до TASK-008. Выбран dual-writer для нормального UX dev-стенда (графики работают сразу). Failure CH не валит Redis-loop — graceful degradation.
2. **Конверсия `Decimal(18,4)` ↔ `Long` cents** изолирована: `centsToDecimal` в `DevPriceFixture` (÷100), `decimalToCents` в `CandlesRepository` (×100). API наружу всегда Long cents.
3. **`getQuote` через одно `HGETALL`** вместо двух HGET — экономит round-trip.
4. **`PortfolioService.enrich` обогащает позиции последовательно.** Параллелизм на портфеле 5-10 тикеров — overkill; backlog (Lua-batch или pipelining).
5. **PostgreSQL чтение без TX-блокировок** в read-side — read-committed snapshot достаточен.
6. **`unrealizedPnlCents = (current - avg) × qty`** считается в Core — один источник правды.
7. **Sealed `QuoteResult` / `HistoryResult` в gateway-client** — типобезопасный мост 404/422 в business exceptions.
8. **`positions.qty > 0` filter** в `listByUser` — нулевые позиции после SELL не показываем.
9. **`CandlesRepository.loadCandles` на `Dispatchers.IO`** — CH JDBC blocking, не блокирует Ktor-coroutines.
10. **`InvalidTimeRangeException` для bad `from`/`to`** парсинга в `parseInstantParam` — корректно мапится в 422.

### API endpoints implemented

#### Gateway (public)
- `GET /v1/portfolio` (Bearer) → 200 `{balance, positions[]}` | 401 | 503
- `GET /v1/quotes/{ticker}` (Bearer) → 200 | 401 | 404 | 422 NO_QUOTE_AVAILABLE
- `GET /v1/quotes/{ticker}/history?from=&to=&interval=` (Bearer) → 200 | 400 (missing query) | 401 | 404 | 422 INVALID_INTERVAL/INVALID_TIME_RANGE
- `GET /v1/instruments` (Bearer) → 200 `{items[]}` | 401

#### Core (internal)
- `GET /internal/users/{userId}/portfolio`, `GET /internal/quotes/{ticker}`, `GET /internal/quotes/{ticker}/history`, `GET /internal/instruments`.

### SQL migrations

**Никаких новых миграций.** V1 (`accounts`), V2 (`instruments`), V4 (`positions`) уже применены. ClickHouse-схема (`quotes_ticks`, `quotes_candles_1m_mv`) — в `deploy/clickhouse/init.sql` из TASK-001.

### Open questions / blockers

- **P1 (backend) — RESOLVED как false alarm.** `quotes_candles_1h` table + `quotes_candles_1h_mv` ЕСТЬ в `deploy/clickhouse/init.sql:57,71`. Backend ошибся при чтении файла (посмотрел только секцию `## 2. Минутные свечи` до строки 53). Код менять не нужно — `interval=1h` будет работать.
- **Локальная компиляция не запускалась** — gradle CLI недоступен (known T1). CI + /tester.
- **`PortfolioService.enrich` sequential** по тикерам — для больших портфелей backlog Lua-batch.

## Review

### Gate: PASS

0 critical · 0 high · 2 medium · 7 low. Backend P1 был **false alarm** — `quotes_candles_1h` существует в init.sql. Готово к merge.

### Critical findings

Нет.

### High findings

Нет.

### Medium findings

- **M1 — Cross-domain exception coupling.** `core-service/.../domain/quotes/QuotesService.kt:30` — `getQuote(ticker)` бросает `com.stockyard.core.domain.order.NoQuoteAvailableException`. Quotes-сервис семантически зависит от order-domain exception. Архитектурно чище — перенести `NoQuoteAvailableException` в `domain/quotes/exceptions.kt` (рядом с `InstrumentNotFound`/`InvalidInterval`/`InvalidTimeRange`). `OrderService` (TASK-006) тоже использует этот класс — потребует обновление импорта. **Не блокирует** — функционально работает, маппинг в 422 один общий.
- **M2 — `RoundingMode.UNNECESSARY` в `DevPriceFixture.centsToDecimal`.** `core-service/.../quotes/DevPriceFixture.kt:121` — `BigDecimal(cents).divide(BigDecimal(100), 4, RoundingMode.UNNECESSARY)`. Сейчас безопасно (cents — Long, scale=4 точное). Но при будущем рефакторе (sub-cent precision) — `ArithmeticException`. Defensive: `RoundingMode.HALF_UP`. Минор.

### Low findings

- **L1 — Backend ledger зарегистрировал ложный P1.** `quotes_candles_1h` есть в `deploy/clickhouse/init.sql:57,71`. Открытый вопрос в Open questions переведён в "resolved". Урок: смотри весь init.sql, не первые 50 строк.
- **L2 — `volume = 0L`** во всех CH-INSERT'ах из `DevPriceFixture`. UI/тесты увидят `"volume": 0` во всех свечах. Acceptable для dev-fixture (`TODO(TASK-008)`); если живее — `Random.nextLong(1000, 10000)`.
- **L3 — `parseInstantParam` бросает `InvalidTimeRangeException` для malformed ISO** (`QuotesApi.kt:36`). На `?from=garbage` клиент получит 422 `INVALID_TIME_RANGE`, хотя это format issue. Generic-маппинг приемлем; для строгой семантики — `InvalidInstantFormatException`.
- **L4 — `PortfolioService.enrich` 50 sequential HGETALL.** Architect это в Risks; для портфеля 5-10 тикеров — OK; для load-simulator — backlog (Lua-batch или Lettuce pipelining).
- **L5 — `DevPriceFixture(chDs: HikariDataSource?)` принимает nullable.** В Application всегда non-null. Лишний null-check.
- **L6 — `CandlesRepository.decimalToCents` HALF_UP** на случай нецелого числа копеек после CH merge-агрегатов — безопасный выбор. ✓
- **L7 — `InstrumentApi.kt` создаёт `InternalInstrumentsResponse` напрямую**, без `toDto()` extension. Минорная inconsistency с PortfolioApi/QuotesApi.

### Positive observations

- **Все деньги — `Long` cents** в API: `amountCents`, `avgPriceCents`, `currentPriceCents`, `unrealizedPnlCents`, candles OHLC. Никаких Decimal/Float в API.
- **Конверсия `Decimal(18,4)` ↔ `Long` cents изолирована** в `DevPriceFixture.centsToDecimal` (CH-write) и `CandlesRepository.decimalToCents` (CH-read).
- **`HGETALL` вместо 2× HGET** для current quote — экономит round-trip; malformed-поле → null → `NoQuoteAvailable`.
- **DevPriceFixture dual-writer с graceful CH-failure.** Redis-loop продолжает работать при сбое CH; залогировано.
- **`userId` всегда из JWT через `call.userId()`** во всех 4 public routes. Чужие данные прочитать нельзя.
- **Sealed `QuoteResult` / `HistoryResult`** в gateway-client — типобезопасный мост 404/422.
- **CH JDBC на `Dispatchers.IO`** — Ktor-coroutines не блокируются.
- **`positions.qty > 0` filter** в `listByUser` — нулевые позиции скрыты.
- **Read-committed без FOR UPDATE** в read-side — корректная семантика, не блокирует concurrent writes.
- **Параллельный SELECT баланса+позиций** через `coroutineScope { async {} }`.
- **`unrealizedPnlCents` считается в Core** — один источник правды.
- **Раздельные max-span limits (7d для 1m, 90d для 1h)** — защита от случайного `from=2000-01-01`.
- **`getQuote/getHistory` валидируют ticker через `instruments` ДО HGETALL/CH-SELECT** — корректная 404 vs 422 семантика.
- **String-interpolation `$tsColumn`** в `CandlesRepository` использует значения из `when (interval)` (не user-input) — SQL injection невозможен. ✓
- **Документация синхронизирована**: §5.3.2 портфеля переписан на cents Long; §5.7 пополнен 3 кодами; §12.2.0 описывает dual-writer.

## Handoff Log
- 2026-05-11T14:00:00Z: создан через /architect — read-side API (portfolio, quotes current+history, instruments) спроектирован поверх TASK-005/006 и V1-V7 миграций. 4 public + 4 internal эндпоинта, никаких новых миграций. `DevPriceFixture` расширяется до ClickHouse-writer'а, чтобы history-эндпоинт начал отдавать ненулевые свечи без ожидания TASK-008. Никаких новых ADR. Suggested next: /backend TASK-007.
- 2026-05-11T15:00:00Z: /backend — реализованы 4 эндпоинта (4 gateway + 4 internal core) по 26-шаговому плану. Новые файлы: 8 в core + 3 в gateway. DevPriceFixture расширен до dual-writer. **Open question P1:** MV `quotes_candles_1h` отсутствует — false alarm, разрешено в /reviewer. Suggested next: /reviewer TASK-007.
- 2026-05-11T16:00:00Z: /reviewer — **gate: PASS**, 0 critical, 0 high, 2 medium (M1 cross-domain NoQuoteAvailable из order-domain, M2 RoundingMode.UNNECESSARY в DevPriceFixture), 7 low. **P1 backend — false alarm:** `quotes_candles_1h` table + MV ЕСТЬ в `deploy/clickhouse/init.sql:57,71`. Деньги только Long cents, ORM не появился, userId из JWT всегда, SQL без концатенации user-input. Готово к merge. Suggested next: /committer TASK-007.
- 2026-05-11T17:00:00Z: /committer — branch `feature/7-read-side-api`, 7 commits: `a6c65da` feat(core) read-side services, `9e51593` feat(core) DevPriceFixture CH dual-writer, `8c20811` feat(gateway) read-side routes with JWT, `144a38a` docs(arch) read-side contracts + CH dual-writer, `b20b7c4` docs(arch) README tone cleanup (carry-over, no TASK ref), `31bece7` docs(task) TASK-007 ledger, `502dfa1` docs(changelog) update. CHANGELOG `[Unreleased]` пополнен 5 user-visible записями. Suggested next: `/committer push`.
