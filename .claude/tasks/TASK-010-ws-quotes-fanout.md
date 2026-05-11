# TASK-010: Gateway WS fanout `/v1/ws/quotes`

## Meta
- ID: TASK-010
- Created: 2026-05-11T18:00:00Z
- Last updated: 2026-05-11T18:00:00Z
- Stage: architect-done
- Touched roles: architect

## Original Request
TASK-008 — quotes pipeline (Driver + Quotes Service + WS), декомпозиция на 4 подзадачи. Эта подзадача — Gateway WebSocket fanout котировок.

## Pipeline Context
Третья из 4 подзадач quotes pipeline. **Может развиваться параллельно с TASK-008/009** — для теста хватит stub-publisher'а на базе `DevPriceFixture` (добавляется здесь как ROLL-BACK-AFTER).

```
   TASK-008 ──┐
              ├─▶ TASK-009 ──┐
              │              │
              │              ├─▶ TASK-011
              │              │
   TASK-010 ──┘──────────────┘  ← THIS TASK
```

Контракт C3 (Gateway↔Client WS) фиксируется здесь.

## Architect Design

### Affected components

- **EXTEND** `gateway-service/src/main/kotlin/com/stockyard/gateway/ws/`
  - `WsHub.kt` — реестр соединений (NEW).
  - `QuotesSubscriber.kt` — Redis Pub/Sub subscriber (NEW).
  - `WsMessages.kt` — sealed classes для inbound/outbound frames (NEW).
- **EXTEND** `gateway-service/.../routing/QuotesRoutes.kt` — добавить `webSocket("/v1/ws/quotes")` route внутри auth блока.
- **EXTEND** `gateway-service/.../Application.kt` — install `WebSockets` plugin, wire-up `WsHub`, `QuotesSubscriber`.
- **EXTEND** `gateway-service/build.gradle.kts` — `io.ktor:ktor-server-websockets`.
- **EXTEND** Core Service — **stub-publisher** в `DevPriceFixture` (только для test/dev): после `HSET` сделать `PUBLISH channel:quotes:{ticker}` с JSON shape из ADR-011. **TODO(TASK-011): drop вместе с DevPriceFixture.**

Не затрагиваются: Core domain logic, Quotes Service (его пока нет), C driver, БД.

### API contract changes

#### Public WS endpoint
```
URL:        wss://stockyard.example/v1/ws/quotes
Auth:       Authorization через query ?token=<JWT>   (§5.3.3 — JWT в query)
Subprotocol: stockyard.v1
```

**Замечание:** в §5.3.3 endpoint назван `/v1/ws` (общий); здесь специализируем до `/v1/ws/quotes`. TASK-011 обновит §5.3.3.

#### Inbound frames (Client → Gateway)
```json
{ "action": "subscribe",   "tickers": ["SBER", "GAZP"] }
{ "action": "unsubscribe", "tickers": ["SBER"] }
{ "action": "ping" }
```

#### Outbound frames (Gateway → Client) — frozen C3 contract
```json
// tick
{ "type": "quote", "ticker": "SBER", "ts": "...", "tsNs": ...,
  "bidCents": 28550, "askCents": 28570, "lastCents": 28560, "volume": 12345 }

// acks
{ "type": "subscribed",   "tickers": ["SBER","GAZP"] }
{ "type": "unsubscribed", "tickers": ["SBER"] }

// heartbeat
{ "type": "pong" }

// errors
{ "type": "error", "code": "INVALID_TICKER",      "message": "..." }
{ "type": "error", "code": "SUBSCRIPTION_LIMIT",  "message": "max 100 tickers/connection" }
{ "type": "error", "code": "INVALID_FRAME",       "message": "..." }
{ "type": "error", "code": "UNAUTHORIZED",        "message": "..." }   // только во время handshake
```

#### Reliability contract
- Server `pong` каждые 30s (heartbeat).
- Client idle >60s → server closes 1008.
- Per-connection outbound buffer 256 messages, **DROP_OLDEST** (at-most-once по ADR-001).
- Max subscriptions per connection: **100 tickers**.
- Max connections per user: **5** (per userId из JWT).
- WS close codes: 1000 normal, 1008 policy, 1011 server error, 4001 auth failed at handshake, 4002 too many conns/user.

### Data model changes
Никаких новых таблиц/ключей. Использует:
- `channel:quotes:*` — pattern SUBSCRIBE (read).
- `quotes:{ticker}` HASH — на reconnect snapshot (S8).
- `session:{jti}` — validate at handshake.

### Implementation steps

**Backend (Kotlin, single role):**

| # | Шаг | Файлы |
|---|---|---|
| 1 | Install `io.ktor.server.websockets.WebSockets` в Application: `pingPeriod=15s, timeout=60s, maxFrameSize=64KB`. | `Application.kt` |
| 2 | `ws/WsMessages.kt` — sealed `InboundFrame`/`OutboundFrame` через kotlinx.serialization polymorphic (`action`/`type` discriminator). | `ws/WsMessages.kt` |
| 3 | `ws/WsHub.kt` — потокобезопасный реестр: `ConcurrentHashMap<ConnId, ConnState>` + reverse index `Map<Ticker, Set<ConnId>>`. Sharded RW-locks. Методы register/unregister/subscribe/unsubscribe/connectionsFor. | `ws/WsHub.kt` |
| 4 | `ws/QuotesSubscriber.kt` — Lettuce `RedisPubSubAdapter`, на старте — один `psubscribe("channel:quotes:*")`. На каждое сообщение: parse JSON (это `OutboundFrame.Quote`), вытащить ticker, `hub.connectionsFor(ticker)`, `trySend` в outbound chan (drop-on-full). | `ws/QuotesSubscriber.kt` |
| 5 | `routing/QuotesRoutes.kt` extend: внутри `authenticate("auth-jwt")` блока — `webSocket("/v1/ws/quotes") { ... }`. Per-user count check → 4002 + close. Spawn outbound writer coroutine. | `routing/QuotesRoutes.kt` |
| 6 | `ConnectionState(connId, userId, tickers: MutableSet<String>, outbound: Channel<OutboundFrame>(capacity=256, BufferOverflow.DROP_OLDEST))`. | `ws/WsHub.kt` |
| 7 | Validation при subscribe: cached set тикеров (load на старте, refresh 5 мин). Invalid → error frame, hard cap 100/conn → `SUBSCRIPTION_LIMIT`. | `routing/QuotesRoutes.kt`, `ws/WsHub.kt` |
| 8 | Reconnect snapshot (S8): первый subscribe на тикер для conn → `HGETALL quotes:{ticker}` → отправить как `quote` frame, потом подписаться на live. | `routing/QuotesRoutes.kt` |
| 9 | JWT validation в handshake: query `?token=`. Истекший в handshake → 4001 close. Истечение внутри сессии — НЕ закрываем (§5.3.3). | `routing/QuotesRoutes.kt` |
| 10 | Graceful shutdown: Application stop → `hub.closeAll(1001)`, QuotesSubscriber.unsubscribe(). | `Application.kt` |
| 11 | **Stub publisher в `DevPriceFixture`** (Core): после `HSET` сделать `PUBLISH channel:quotes:$ticker` с JSON shape ADR-011. `TODO(TASK-011): drop.` | `core-service/.../quotes/DevPriceFixture.kt` |
| 12 | Metrics: `ws_connections_active`, `ws_subscriptions_total`, `ws_frames_sent_total`, `ws_frames_dropped_backpressure_total`, `redis_pubsub_messages_received_total`. | `ws/WsHub.kt` |

**Tester:**

| # | Шаг |
|---|---|
| T1 | Unit (WsMessages): (de)serialization всех frames. Unknown action → `INVALID_FRAME`. |
| T2 | Unit (WsHub): register/unregister/subscribe/unsubscribe; reverse index консистентен; hard cap 100. |
| T3 | Unit (WsHub): outbound `DROP_OLDEST` — `trySend` всегда успешен. |
| T4 | IT (Testcontainers Redis + Ktor TestApp): WS handshake JWT валидный → OK; невалидный → 4001. |
| T5 | IT: subscribe ["SBER"] → `subscribed`. Внешний `PUBLISH channel:quotes:SBER` → клиент получает `quote`. |
| T6 | IT: 2 клиента subscribe ["SBER"], 1 PUBLISH → оба получают. |
| T7 | IT: subscribe ["UNKNOWN"] → `INVALID_TICKER`, остальные подписки целы. |
| T8 | IT: subscribe 100 + 1 → `SUBSCRIPTION_LIMIT`. |
| T9 | IT: 6-й коннект того же user → 4002 close. |
| T10 | IT: idle 70s → server closes 1008. |
| T11 | IT: unsubscribe → ack, далее PUBLISH SBER не доходит. |
| T12 | IT: reconnect → snapshot first из HGETALL, потом live. |
| T13 | Chaos: Redis Pub/Sub down → reconnect, после восстановления — PUBLISH доходит. |
| T14 | Stress: 100 connections × 50 tickers × 10 ticks/sec → loss < 1%, drop_oldest растёт пропорционально. |
| T15 | E2E через stub-publisher: PUBLISH JSON в cents → клиент видит cents, без 100× ошибки. |

**Reviewer:**
- Cents-only в payload (R2/ADR-011), не Decimal.
- WsHub thread-safety: ConcurrentHashMap + compound update locks.
- Backpressure: DROP_OLDEST настройка корректна, нет block на send.
- JWT в query — приемлемо для MVP, документировано.
- `userId` доступен в WS-session после auth.

### ADR
**ADR-013 (NEW): Single pattern subscribe + in-process WsHub fanout, not subscribe-per-WS.**
- Context: 10k WS × 50 тикеров = до 500k подписочных слотов.
- Decision: один `psubscribe("channel:quotes:*")` на Gateway + in-process `Map<Ticker, Set<ConnId>>`.
- Alternatives: per-connection SUBSCRIBE (10k subscriptions на Redis — деградация), per-ticker pool (overkill для 50).
- Consequences: если несколько Gateway — каждый получает все сообщения (OK для MVP с одним Gateway).

**ADR-014 (NEW, опц.): JWT in query-param for WS handshake.**
- Decision: `?token=<jwt>`. Subprotocol-based — backlog.
- Consequences: JWT попадает в access-logs; mitigated коротким TTL (15m).

### Risks с митигациями
| Риск | Likelihood | Impact | Митигация |
|---|---|---|---|
| Тестирование без Quotes Service блокировано | High | Medium | Stub-publisher в DevPriceFixture (шаг 11) + IT через прямой PUBLISH к Testcontainers Redis. |
| Pattern subscribe slow на больших каналах | Low | Medium | 50 каналов — мизер. |
| Memory leak в WsHub при разрыве | Medium | High | Unregister hook в `webSocket {}` `finally`; IT T11. |
| JSON parse failure ломает subscriber | Medium | High | Try/catch в `QuotesSubscriber.onMessage`, log + skip. |
| outbound DROP_OLDEST теряет control frames | Medium | Low | Sub/error frames редкие — в FIFO теряются только под жёстким backlog. OK для MVP. |
| RN не умеет subprotocols | Medium | Low | Query-only достаточно (ADR-014). |
| JWT истёк во время WS-сессии | Medium | Low | Не проверяем (§5.3.3). |

### Estimated complexity: **MEDIUM-LARGE**
5–7 ч/дней. WsHub thread-safety и backpressure — нетто-новая поверхность; tested через stress.

### Suggested next role
`/backend TASK-010` (один Kotlin-разработчик с Ktor WS).

## Handoff Log
- 2026-05-11T18:00:00Z: создан через /architect — design complete; suggested next: `/backend TASK-010` (может стартовать параллельно с TASK-008/009).
