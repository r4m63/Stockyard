# 10. Ключевые сценарии

Как компоненты взаимодействуют в основных пользовательских и системных потоках. Связывает структурные и эксплуатационные документы в сквозные сценарии.

---

## 10.1. Список сценариев

| # | Сценарий | Тип |
|---|---|---|
| S1 | Регистрация пользователя | критический путь |
| S2 | Логин и обновление токена | критический путь |
| S3 | Подписка на котировки и поток тиков | критический путь |
| S4 | Получение исторических котировок (график) | важный |
| S5 | Покупка акций (BUY) | критический путь |
| S6 | Продажа акций (SELL) | критический путь |
| S7 | Просмотр портфеля и истории ордеров | важный |
| S8 | Реконнект после потери сети | устойчивость |
| S9 | Доставка котировки от драйвера до клиента | end-to-end pipeline |
| S10 | Прогон Load Simulator (10к клиентов) | системный тест |

---

## 10.2. S1 — Регистрация

```
   Mobile      Gateway       Core        PostgreSQL     Redis
     │            │            │              │            │
     │ POST /v1/auth/register {email, password}            │
     │───────────▶│            │              │            │
     │            │ validate format            │            │
     │            │ (argon2 hash на сервере, не на клиенте)│
     │            │ POST /internal/users {email, password} │
     │            │───────────▶│              │            │
     │            │            │ argon2.hash(password)     │
     │            │            │ BEGIN        │            │
     │            │            │─────────────▶│            │
     │            │            │ INSERT users (id, email, hash)
     │            │            │─────────────▶│            │
     │            │            │ INSERT accounts (user_id, │
     │            │            │   balance=100000_00, RUB) │
     │            │            │─────────────▶│            │
     │            │            │ COMMIT       │            │
     │            │            │─────────────▶│            │
     │            │ {userId}   │              │            │
     │            │◀───────────│              │            │
     │            │ issue JWT (access + refresh)           │
     │            │ SET session:{jti} userId TTL=15m       │
     │            │───────────────────────────────────────▶│
     │            │ SET refresh:{rid} userId TTL=30d       │
     │            │───────────────────────────────────────▶│
     │ 201 {userId, accessToken, refreshToken}             │
     │◀───────────│            │              │            │
```

**Инварианты:**
- Email уникален (UNIQUE-индекс).
- Стартовый баланс начисляется в той же транзакции, что и `users`.
- Токены выдаются только если транзакция успешна.

---

## 10.3. S2 — Логин

```
   Mobile      Gateway       Core         PostgreSQL    Redis
     │            │            │              │            │
     │ POST /v1/auth/login {email, password}               │
     │───────────▶│            │              │            │
     │            │ POST /internal/auth {email, password}  │
     │            │───────────▶│              │            │
     │            │            │ SELECT id, password_hash  │
     │            │            │ FROM users WHERE email=?  │
     │            │            │─────────────▶│            │
     │            │            │ argon2.verify(password, hash)
     │            │            │              │            │
     │            │            │ ┌── invalid ──────────────────────┐
     │            │ 401 {INVALID_CREDENTIALS} │                    │
     │            │◀───────────│              │            │       │
     │ 401        │            │              │            │       │
     │◀───────────│            │              │            │       │
     │            │            │ └────────────────────────────────┘
     │            │            │ ┌── valid ────────────────────────┐
     │            │ {userId}   │              │            │       │
     │            │◀───────────│              │            │       │
     │            │ issue JWT  │              │            │       │
     │            │ SET session:{jti}, refresh:{rid}       │       │
     │            │───────────────────────────────────────▶│       │
     │ 200 {accessToken, refreshToken}        │            │       │
     │◀───────────│            │              │            │       │
     │            │            │ └────────────────────────────────┘
```

### S2-bis — Refresh

```
   Mobile      Gateway       Redis
     │            │            │
     │ POST /v1/auth/refresh {refreshToken}
     │───────────▶│            │
     │            │ verify signature
     │            │ GET refresh:{rid}
     │            │───────────▶│
     │            │            │
     │            │ ┌── не найден / истёк ──┐
     │ 401 {INVALID_REFRESH}    │           │
     │◀───────────│            │            │
     │            │ └────────────────────┘  │
     │            │ ┌── найден ────────────┐
     │            │ issue new accessToken   │
     │            │ SET session:{new_jti}   │
     │            │───────────▶│            │
     │ 200 {accessToken}       │            │
     │◀───────────│            │            │
     │            │ └────────────────────┘
```

---

## 10.4. S3 — Подписка на котировки

```
   Mobile     Gateway      Redis        Quotes      C Driver
     │           │           │             │            │
     │ WSS /v1/ws (Authorization: Bearer)               │
     │──────────▶│           │             │            │
     │           │ validate JWT                          │
     │ 101 Switching Protocols (WS handshake)           │
     │◀──────────│           │             │            │
     │           │           │             │            │
     │ {action: "subscribe", tickers: ["SBER","GAZP"]}  │
     │──────────▶│           │             │            │
     │           │ register subscription in WsHub        │
     │           │ SUBSCRIBE channel:quotes:SBER        │
     │           │──────────▶│             │            │
     │           │ SUBSCRIBE channel:quotes:GAZP        │
     │           │──────────▶│             │            │
     │ {type: "subscribed", tickers: ["SBER","GAZP"]}   │
     │◀──────────│           │             │            │
     │           │           │             │            │
   . . . непрерывный поток в фоне . . .                  │
     │           │           │             │            │
     │           │           │             │ tick(SBER, 285.50/285.70)
     │           │           │             │◀───────────│
     │           │           │ HSET quotes:SBER …       │
     │           │           │◀────────────│            │
     │           │           │ PUBLISH channel:quotes:SBER {…}
     │           │           │◀────────────│            │
     │           │ message on channel:quotes:SBER       │
     │           │◀──────────│             │            │
     │           │ lookup subscribers (WsHub)            │
     │ {type:"quote", ticker:"SBER", bid:285.50, ask:285.70, …}
     │◀──────────│           │             │            │
     │           │           │             │            │
   . . . heartbeat каждые 30 сек . . .                    │
     │ {type: "pong"}                       │            │
     │◀──────────│           │             │            │
```

**Особенности:**
- Подписка добавляет тикеры в map `userId → Set<ticker>` внутри WsHub.
- Один процесс Gateway — один SUBSCRIBE на канал. При нескольких клиентах на один тикер канал не дублируется.
- При unsubscribe — если на канале не осталось клиентов, делаем UNSUBSCRIBE (опционально).

---

## 10.5. S4 — История котировок (график)

```
   Mobile      Gateway       Core         ClickHouse
     │            │            │              │
     │ GET /v1/quotes/SBER/history?from=…&to=…&interval=1m
     │───────────▶│            │              │
     │            │ GET /internal/quotes/history?…
     │            │───────────▶│              │
     │            │            │ SELECT … FROM quotes_candles_1m
     │            │            │ WHERE ticker='SBER' …
     │            │            │─────────────▶│
     │            │            │ [(ts, o, h, l, c, v), …]
     │            │            │◀─────────────│
     │            │ { ticker, candles: [...] }│
     │            │◀───────────│              │
     │ 200 OK     │            │              │
     │◀───────────│            │              │
```

**Кэширование:**
- Можно добавить ETag / `If-None-Match` — для одного и того же `from/to/interval` ответ детерминирован (кроме самой свежей минуты).
- В MVP — без кэша, ClickHouse достаточно быстр.

---

## 10.6. S5 — Покупка акций (BUY)

Самый ответственный сценарий — здесь живут деньги.

```
   Mobile      Gateway       Core         Redis      PostgreSQL
     │            │            │            │            │
     │ POST /v1/orders   Idempotency-Key: K              │
     │ {ticker:"SBER", side:"BUY", qty:10}               │
     │───────────▶│            │            │            │
     │            │ validate JWT, rate limit│            │
     │            │ POST /internal/orders {userId, K, SBER, BUY, 10}
     │            │───────────▶│            │            │
     │            │            │ SELECT id FROM orders   │
     │            │            │ WHERE user_id=? AND idempotency_key=K
     │            │            │────────────────────────▶│
     │            │            │                         │
     │            │            │ ┌── повтор ───────────────────────┐
     │            │            │ existing order          │         │
     │            │            │◀────────────────────────│         │
     │            │ 200 OK (existing)        │           │         │
     │            │◀───────────│             │           │         │
     │ 200 OK (тот же ответ)   │             │           │         │
     │◀───────────│            │             │           │         │
     │            │            │ └────────────────────────────────┘
     │            │            │ ┌── первый раз ───────────────────┐
     │            │            │ HGET quotes:SBER ask    │         │
     │            │            │────────────▶│           │         │
     │            │            │ 28570 (cents)           │         │
     │            │            │◀────────────│           │         │
     │            │            │ cost = 10 * 28570 = 285700        │
     │            │            │ BEGIN                   │         │
     │            │            │────────────────────────▶│         │
     │            │            │ SELECT balance_cents FROM accounts│
     │            │            │ WHERE user_id=? FOR UPDATE        │
     │            │            │────────────────────────▶│         │
     │            │            │ 1000000                 │         │
     │            │            │◀────────────────────────│         │
     │            │            │                         │         │
     │            │            │ ┌── balance < cost ───────────┐   │
     │            │            │ INSERT orders (status=REJECTED)   │
     │            │            │ COMMIT                  │     │   │
     │            │            │────────────────────────▶│     │   │
     │            │ 422 INSUFFICIENT_FUNDS   │           │     │   │
     │            │◀───────────│             │           │     │   │
     │ 422        │            │             │           │     │   │
     │◀───────────│            │             │           │     │   │
     │            │            │ └────────────────────────────┘   │
     │            │            │ ┌── balance >= cost ──────────┐   │
     │            │            │ UPDATE accounts SET balance -= cost
     │            │            │ INSERT positions … ON CONFLICT DO UPDATE
     │            │            │ INSERT orders (status=EXECUTED, price=ask)
     │            │            │ INSERT transactions (type=BUY, amount=-cost)
     │            │            │ COMMIT                  │     │   │
     │            │            │────────────────────────▶│     │   │
     │            │ 201 {orderId, status:EXECUTED, price:285.70}  │
     │            │◀───────────│             │           │     │   │
     │ 201        │            │             │           │     │   │
     │◀───────────│            │             │           │     │   │
     │            │            │ └────────────────────────────┘   │
     │            │            │ └────────────────────────────────┘
   . . . опционально: пуш-уведомление об исполнении . . .         │
     │ WS {type:"order.executed", orderId, …}                     │
     │◀───────────│            │             │           │        │
```

**Что важно:**
- Идемпотентность по `K` — двойной клик не списывает деньги дважды.
- `FOR UPDATE` на `accounts` сериализует параллельные ордера одного пользователя.
- Цена из Redis (`ask`) фиксируется ДО `BEGIN`, чтобы транзакция была короткой.
- Все мутации — в одной транзакции PostgreSQL.

---

## 10.7. S6 — Продажа акций (SELL)

Симметрично BUY, но с проверкой позиции вместо баланса.

```
   Core           Redis        PostgreSQL
     │              │             │
   . . . после идемпотентности и rate-check . . .
     │              │             │
     │ HGET quotes:SBER bid       │
     │─────────────▶│             │
     │ 28550        │             │
     │◀─────────────│             │
     │              │             │
     │ BEGIN        │             │
     │──────────────────────────▶│
     │ SELECT qty FROM positions  │
     │ WHERE user_id=? AND ticker=? FOR UPDATE
     │──────────────────────────▶│
     │ 10           │             │
     │◀──────────────────────────│
     │              │             │
     │ ┌── qty < requested ─────────────┐
     │ INSERT orders (status=REJECTED)  │
     │ COMMIT       │             │     │
     │──────────────────────────▶│     │
     │ └────────────────────────────────┘
     │ ┌── qty >= requested ────────────┐
     │ UPDATE positions SET qty -= req  │
     │ UPDATE accounts SET balance += proceeds
     │ INSERT orders (status=EXECUTED, price=bid)
     │ INSERT transactions (type=SELL, amount=+proceeds)
     │ COMMIT       │             │     │
     │──────────────────────────▶│     │
     │ └────────────────────────────────┘
```

---

## 10.8. S7 — Портфель и история

```
   Mobile      Gateway       Core         PostgreSQL    Redis
     │            │            │              │            │
     │ GET /v1/portfolio       │              │            │
     │───────────▶│            │              │            │
     │            │ GET /internal/users/{userId}/portfolio │
     │            │───────────▶│              │            │
     │            │            │              │            │
   . . . параллельно . . .                                  │
     │            │            │ SELECT balance FROM accounts
     │            │            │ WHERE user_id=?           │
     │            │            │─────────────▶│            │
     │            │            │ SELECT ticker, qty, avg_price
     │            │            │ FROM positions WHERE user_id=?
     │            │            │─────────────▶│            │
     │            │            │ HGET quotes:SBER last     │
     │            │            │ (батчем для всех тикеров) │
     │            │            │──────────────────────────▶│
     │            │            │ формирует ответ с current_price из Redis
     │            │ { balance, positions:[{ticker, qty, avg, current}] }
     │            │◀───────────│              │            │
     │ 200 OK     │            │              │            │
     │◀───────────│            │              │            │
```

`current_price` берётся из Redis, чтобы не делать join с ClickHouse каждый раз.

---

## 10.9. S8 — Реконнект мобильного клиента

```
   Mobile      Gateway       Redis
     │            │            │
   . . . WS-соединение разорвано (плохая сеть) . . .
   . . . клиент детектирует разрыв . . .
     │            │            │
     │ WSS reconnect (с тем же JWT)
     │───────────▶│            │
     │            │ JWT validate
     │ 101 Switching Protocols │
     │◀───────────│            │
     │ {action:"subscribe", tickers:[<сохранённые>]}
     │───────────▶│            │
     │            │ register subscriptions
     │            │            │
   . . . параллельно . . .     │
     │            │ HGETALL quotes:SBER (snapshot)
     │            │───────────▶│
     │ {type:"quote", ticker:"SBER", …} (snapshot)
     │◀───────────│            │
     │            │ SUBSCRIBE channel:quotes:SBER (live updates)
     │            │───────────▶│
```

**Свойства:**
- При reconnect клиент получает **snapshot текущей цены**, чтобы не ждать следующего тика.
- Дальше — обычные live-тики.
- Никакой репликации/догона пропущенных тиков (eventual consistency).

---

## 10.10. S9 — End-to-end путь котировки

Это пайплайн, который объединяет драйвер → клиента в одной картине.

```
   C Driver     Quotes      Redis       ClickHouse    Gateway     Mobile
      │           │           │              │           │           │
      │ timer tick (каждые 1 сек)                                    │
      │ random walk цен по всем тикерам                              │
      │ write to ring buffer                                         │
      │           │           │              │           │           │
      │ read() from /dev/stockyard                                   │
      │◀──────────│           │              │           │           │
      │ tick {SBER, ts, bid, ask, last, vol}                         │
      │──────────▶│           │              │           │           │
      │           │ parse, fanout                                    │
      │           │           │              │           │           │
   . . . сразу . . .          │              │           │           │
      │           │ PUBLISH channel:quotes:SBER …                    │
      │           │──────────▶│              │           │           │
      │           │ HSET quotes:SBER …       │           │           │
      │           │──────────▶│              │           │           │
      │           │ XADD stream:quotes …     │           │           │
      │           │──────────▶│              │           │           │
   . . . батчем (каждую секунду) . . .       │           │           │
      │           │ накопление в буфере      │           │           │
      │           │ INSERT INTO quotes_ticks VALUES (…)              │
      │           │─────────────────────────▶│           │           │
      │           │           │              │           │           │
      │           │           │ message on channel:quotes:SBER       │
      │           │           │─────────────────────────▶│           │
      │           │           │              │ lookup subscribers (WsHub)
      │           │           │              │           │ WS frame {type:"quote", …}
      │           │           │              │           │──────────▶│
      │           │           │              │           │           │
   . . . total p95: < 500 мс . . .           │           │           │
```

---

## 10.11. S10 — Прогон Load Simulator

```
   Operator    Simulator    Gateway     Stockyard    Grafana
     │            │            │            │            │
     │ ./load-simulator --users 10000 --duration 10m     │
     │───────────▶│            │            │            │
     │            │ spawn 10k coroutines                 │
     │            │            │            │            │
   . . . для каждой . . .      │            │            │
     │            │ POST /auth/login → JWT  │            │
     │            │───────────▶│            │            │
     │            │ WS subscribe SBER, GAZP, …           │
     │            │───────────▶│            │            │
     │            │            │            │            │
   . . . loop каждые N сек . . .            │            │
     │            │ GET /portfolio          │            │
     │            │───────────▶│            │            │
     │            │ POST /orders            │            │
     │            │───────────▶│            │            │
     │            │            │ обработка запросов      │
     │            │            │───────────▶│            │
     │            │            │            │ . OTel . .▶│
     │            │ собирает latency, errors             │
     │            │            │            │            │
   . . . после 10 минут . . .  │            │            │
     │            │ report:    │            │            │
     │            │  p95 /orders = 240 ms   │            │
     │            │  error rate = 0.3%      │            │
     │            │  WS msg lost = 0.001%   │            │
     │◀───────────│            │            │            │
     │ открывает Grafana, делает скриншоты               │
     │───────────────────────────────────────────────────▶│
```

Результаты прогона идут в раздел «Тестирование» отчёта.

---

## 10.12. Покрытие сценариями компонентов системы

| Сценарий | Сервисы | Тип взаимодействия | Хранилища | Среда |
|---|---|---|---|---|
| S1 Register | GW, DB | sync HTTP, INSERT | PostgreSQL, Redis | Dev/Demo |
| S2 Login | GW, DB | sync HTTP, SELECT | PostgreSQL, Redis | Dev/Demo |
| S3 Subscribe | GW, Quotes, Driver | WS, Pub/Sub | Redis | Dev/Demo |
| S4 History | GW, DB | sync HTTP, SELECT | ClickHouse | Dev/Demo |
| S5 Buy | GW, DB | TX, FOR UPDATE | PostgreSQL, Redis | Dev/Demo |
| S6 Sell | GW, DB | TX, FOR UPDATE | PostgreSQL, Redis | Dev/Demo |
| S7 Portfolio | GW, DB | параллельные SELECT | PostgreSQL, Redis | Dev/Demo |
| S8 Reconnect | GW | WS reconnect, snapshot | Redis | Dev/Demo |
| S9 Tick pipeline | Driver, Quotes, GW | end-to-end stream | Redis, ClickHouse | Dev/Demo |
| S10 Load test | все | массовый ramp-up | все хранилища | Demo |

---

## Связанные документы

- ⬅ [09. Наблюдаемость](09-observability.md)
- ➡ [11. Стратегия тестирования](11-testing.md)
- ↩ [README](README.md)
