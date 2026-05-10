# 10. Ключевые сценарии

## Назначение

Показать, как **компоненты системы взаимодействуют** в ключевых пользовательских и системных потоках. Этот раздел связывает структурные и эксплуатационные документы в осмысленные сквозные сценарии.

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

```mermaid
sequenceDiagram
    participant M as Mobile
    participant GW as Gateway
    participant DB as Core Service
    participant PG as PostgreSQL
    participant R as Redis

    M->>GW: POST /v1/auth/register {email, password}
    GW->>GW: validate format
    Note over GW: argon2 hash на стороне клиента?<br/>нет — на сервере
    GW->>DB: POST /internal/users {email, password}
    DB->>DB: argon2.hash(password)
    DB->>PG: BEGIN
    DB->>PG: INSERT users (id, email, hash)
    DB->>PG: INSERT accounts (user_id, balance=100000_00, RUB)
    Note over DB,PG: starter balance 100k RUB
    DB->>PG: COMMIT
    DB-->>GW: {userId}
    GW->>GW: issue JWT (access+refresh)
    GW->>R: SET session:{jti} userId TTL=15m
    GW->>R: SET refresh:{rid} userId TTL=30d
    GW-->>M: 201 {userId, accessToken, refreshToken}
```

**Инварианты:**
- Email уникален (UNIQUE-индекс).
- Стартовый баланс начисляется в той же транзакции, что и `users`.
- Токены выдаются только если транзакция успешна.

---

## 10.3. S2 — Логин

```mermaid
sequenceDiagram
    participant M as Mobile
    participant GW as Gateway
    participant DB as Core Service
    participant PG as PostgreSQL
    participant R as Redis

    M->>GW: POST /v1/auth/login {email, password}
    GW->>DB: POST /internal/auth {email, password}
    DB->>PG: SELECT id, password_hash FROM users WHERE email=?
    DB->>DB: argon2.verify(password, hash)
    alt invalid
        DB-->>GW: 401 {INVALID_CREDENTIALS}
        GW-->>M: 401
    else valid
        DB-->>GW: {userId}
        GW->>GW: issue JWT
        GW->>R: SET session:{jti}, refresh:{rid}
        GW-->>M: 200 {accessToken, refreshToken}
    end
```

### S2-bis — Refresh

```mermaid
sequenceDiagram
    participant M as Mobile
    participant GW as Gateway
    participant R as Redis

    M->>GW: POST /v1/auth/refresh {refreshToken}
    GW->>GW: verify signature
    GW->>R: GET refresh:{rid}
    alt не найден / истёк
        GW-->>M: 401 {INVALID_REFRESH}
    else найден
        GW->>GW: issue new accessToken
        GW->>R: SET session:{new_jti}
        GW-->>M: 200 {accessToken}
    end
```

---

## 10.4. S3 — Подписка на котировки

```mermaid
sequenceDiagram
    participant M as Mobile
    participant GW as Gateway
    participant R as Redis
    participant Q as Quotes Service
    participant D as C Driver

    M->>GW: WSS /v1/ws (Authorization: Bearer)
    GW->>GW: validate JWT
    GW-->>M: 101 Switching Protocols (WS handshake)

    M->>GW: {action: "subscribe", tickers: ["SBER","GAZP"]}
    GW->>GW: register subscription in WsHub
    GW->>R: SUBSCRIBE channel:quotes:SBER
    GW->>R: SUBSCRIBE channel:quotes:GAZP
    GW-->>M: {type: "subscribed", tickers: ["SBER","GAZP"]}

    Note over D,Q: непрерывный поток в фоне
    D->>Q: tick(SBER, 285.50/285.70)
    Q->>R: HSET quotes:SBER ...
    Q->>R: PUBLISH channel:quotes:SBER {...}

    R-->>GW: message on channel:quotes:SBER
    GW->>GW: lookup subscribers (WsHub)
    GW-->>M: {type: "quote", ticker:"SBER", bid:285.50, ask:285.70, ...}

    Note over GW,M: heartbeat
    loop каждые 30 сек
        GW-->>M: {type: "pong"}
    end
```

**Особенности:**
- Подписка добавляет тикеры в map `userId → Set<ticker>` внутри WsHub.
- Один процесс Gateway — один SUBSCRIBE на канал. При нескольких клиентах на один тикер канал не дублируется.
- При unsubscribe — если на канале не осталось клиентов, делаем UNSUBSCRIBE (опционально).

---

## 10.5. S4 — История котировок (график)

```mermaid
sequenceDiagram
    participant M as Mobile
    participant GW as Gateway
    participant DB as Core Service
    participant CH as ClickHouse

    M->>GW: GET /v1/quotes/SBER/history?from=...&to=...&interval=1m
    GW->>DB: GET /internal/quotes/history?...
    DB->>CH: SELECT ... FROM quotes_candles_1m WHERE ticker='SBER' ...
    CH-->>DB: [(ts, o, h, l, c, v), ...]
    DB-->>GW: { ticker, candles: [...] }
    GW-->>M: 200 OK
```

**Кэширование:**
- Можно добавить ETag / `If-None-Match` — для одного и того же `from/to/interval` ответ детерминирован (кроме самой свежей минуты).
- В MVP — без кэша, ClickHouse достаточно быстр.

---

## 10.6. S5 — Покупка акций (BUY)

Самый ответственный сценарий — здесь живут деньги.

```mermaid
sequenceDiagram
    participant M as Mobile
    participant GW as Gateway
    participant DB as Core Service
    participant R as Redis
    participant PG as PostgreSQL

    M->>GW: POST /v1/orders<br/>Idempotency-Key: K<br/>{ticker:"SBER", side:"BUY", qty:10}
    GW->>GW: validate JWT, rate limit
    GW->>DB: POST /internal/orders {userId, K, SBER, BUY, 10}

    DB->>PG: SELECT id FROM orders WHERE user_id=? AND idempotency_key=K
    alt уже есть (повтор)
        PG-->>DB: existing order
        DB-->>GW: 200 OK (existing)
        GW-->>M: 200 OK (тот же ответ)
    else первый раз
        DB->>R: HGET quotes:SBER ask
        R-->>DB: 28570 (cents)
        DB->>DB: cost = 10 * 28570 = 285700

        DB->>PG: BEGIN
        DB->>PG: SELECT balance_cents FROM accounts<br/>WHERE user_id=? FOR UPDATE
        PG-->>DB: 1000000

        alt balance < cost
            DB->>PG: INSERT orders (status=REJECTED)
            DB->>PG: COMMIT
            DB-->>GW: 422 INSUFFICIENT_FUNDS
            GW-->>M: 422
        else balance >= cost
            DB->>PG: UPDATE accounts SET balance -= cost
            DB->>PG: INSERT INTO positions ... ON CONFLICT DO UPDATE
            DB->>PG: INSERT orders (status=EXECUTED, price=ask)
            DB->>PG: INSERT transactions (type=BUY, amount=-cost)
            DB->>PG: COMMIT
            DB-->>GW: 201 {orderId, status:EXECUTED, price:285.70}
            GW-->>M: 201
        end
    end

    opt пуш-уведомление об исполнении
        GW-->>M: WS {type:"order.executed", orderId, ...}
    end
```

**Что важно:**
- Идемпотентность по `K` — двойной клик не списывает деньги дважды.
- `FOR UPDATE` на `accounts` сериализует параллельные ордера одного пользователя.
- Цена из Redis (`ask`) фиксируется ДО `BEGIN`, чтобы транзакция была короткой.
- Все мутации — в одной транзакции PostgreSQL.

---

## 10.7. S6 — Продажа акций (SELL)

Симметрично BUY, но с проверкой позиции вместо баланса.

```mermaid
sequenceDiagram
    participant DB as Core Service
    participant R as Redis
    participant PG as PostgreSQL

    Note over DB: после идемпотентности и rate-check

    DB->>R: HGET quotes:SBER bid
    R-->>DB: 28550

    DB->>PG: BEGIN
    DB->>PG: SELECT qty FROM positions WHERE user_id=? AND ticker=? FOR UPDATE
    PG-->>DB: 10

    alt qty < requested
        DB->>PG: INSERT orders (status=REJECTED)
        DB->>PG: COMMIT
    else qty >= requested
        DB->>PG: UPDATE positions SET qty -= req
        DB->>PG: UPDATE accounts SET balance += proceeds
        DB->>PG: INSERT orders (status=EXECUTED, price=bid)
        DB->>PG: INSERT transactions (type=SELL, amount=+proceeds)
        DB->>PG: COMMIT
    end
```

---

## 10.8. S7 — Портфель и история

```mermaid
sequenceDiagram
    participant M as Mobile
    participant GW as Gateway
    participant DB as Core Service
    participant PG as PostgreSQL
    participant R as Redis

    M->>GW: GET /v1/portfolio
    GW->>DB: GET /internal/users/{userId}/portfolio

    par Параллельно
        DB->>PG: SELECT balance FROM accounts WHERE user_id=?
    and
        DB->>PG: SELECT ticker, qty, avg_price FROM positions WHERE user_id=?
    end

    DB->>R: HGET quotes:SBER last (батчем для всех тикеров портфеля)
    DB->>DB: формирует ответ с current_price из Redis
    DB-->>GW: { balance, positions:[{ticker, qty, avg, current}] }
    GW-->>M: 200 OK
```

`current_price` берётся из Redis, чтобы не делать join с ClickHouse каждый раз.

---

## 10.9. S8 — Реконнект мобильного клиента

```mermaid
sequenceDiagram
    participant M as Mobile
    participant GW as Gateway
    participant R as Redis

    Note over M,GW: WS-соединение разорвано (плохая сеть)
    Note over M: клиент детектирует разрыв

    M->>GW: WSS reconnect (с тем же JWT)
    GW->>GW: JWT validate
    GW-->>M: 101 Switching Protocols

    M->>GW: {action:"subscribe", tickers:[<сохранённые>]}
    GW->>GW: regsiter subscriptions

    par Snapshot
        GW->>R: HGETALL quotes:SBER
        GW-->>M: {type:"quote", ticker:"SBER", ...} (snapshot)
    and Live updates
        GW->>R: SUBSCRIBE channel:quotes:SBER
    end
```

**Свойства:**
- При reconnect клиент получает **snapshot текущей цены**, чтобы не ждать следующего тика.
- Дальше — обычные live-тики.
- Никакой репликации/догона пропущенных тиков (eventual consistency).

---

## 10.10. S9 — End-to-end путь котировки

Это пайплайн, который объединяет драйвер → клиента в одной картине.

```mermaid
sequenceDiagram
    participant Drv as C Driver
    participant Q as Quotes Service
    participant R as Redis
    participant CH as ClickHouse
    participant GW as Gateway
    participant M as Mobile

    Drv->>Drv: timer tick (каждые 1 сек)
    Drv->>Drv: random walk цен по всем тикерам
    Drv->>Drv: write to ring buffer

    Q->>Drv: read() from /dev/stockyard
    Drv-->>Q: tick {SBER, ts, bid, ask, last, vol}

    Q->>Q: parse, fanout

    par сразу
        Q->>R: PUBLISH channel:quotes:SBER ...
        Q->>R: HSET quotes:SBER ...
        Q->>R: XADD stream:quotes ...
    and батчем
        Q->>Q: накопление в буфере
        Note over Q: каждую секунду
        Q->>CH: INSERT INTO quotes_ticks VALUES (...)
    end

    R-->>GW: message on channel:quotes:SBER
    GW->>GW: lookup subscribers (WsHub)
    GW-->>M: WS frame {type:"quote", ...}

    Note over Drv,M: total p95: < 500 мс
```

---

## 10.11. S10 — Прогон Load Simulator

```mermaid
sequenceDiagram
    participant Op as Operator
    participant Sim as Load Simulator
    participant GW as Gateway
    participant Sys as Stockyard
    participant Mon as Grafana

    Op->>Sim: ./load-simulator --users 10000 --duration 10m
    Sim->>Sim: spawn 10k coroutines

    par для каждой
        Sim->>GW: POST /auth/login → JWT
        Sim->>GW: WS subscribe SBER, GAZP, ...
        loop каждые N сек
            Sim->>GW: GET /portfolio
            Sim->>GW: POST /orders
        end
    end

    GW->>Sys: обработка запросов
    Sys-.->Mon: метрики через OTel

    Sim->>Sim: собирает latency, errors

    Note over Sim,Op: после 10 минут
    Sim-->>Op: report:<br/>p95 /orders = 240 ms<br/>error rate = 0.3%<br/>WS msg lost = 0.001%
    Op->>Mon: открывает Grafana, делает скриншоты
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
