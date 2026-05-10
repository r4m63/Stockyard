# 08. Масштабирование и производительность

Целевые показатели нагрузки, расчёт capacity по компонентам, узкие места, стратегия горизонтального и вертикального масштабирования. Обоснование, почему архитектура держит 10 000 одновременных клиентов.

> ### MVP must-have vs 📦 Backlog
>
> Раздел описывает и текущее MVP-состояние, и план роста. Чтобы команда не пугалась объёма:
>
> - MVP (реализуем): базовый расчёт нагрузки (§8.2), обязательные настройки (§8.5 «Обязательные»), backpressure WS (§8.7), доказательство SLO через Load Simulator (§8.9).
> - 📦 **Backlog (для отчёта/защиты, не реализуем):** async order queue (§8.4.3), circuit breaker (§8.6), ZGC, HTTP/2, ETag, шардинг PG. Все такие пункты дополнительно помечены 📦 в заголовке.

---

## 8.1. Целевые SLO

### Функциональные цели нагрузки

| Метрика | Цель MVP | Stretch goal |
|---|---|---|
| Concurrent users (CCU) | 10 000 | 50 000 |
| DAU | 10 000 | 100 000 |
| WS-сообщений на клиента/сек | 5 (5 подписок × 1 тик/сек) | то же |
| REST RPS пиковый | ~500 | 5 000 |
| Ордеров в секунду | ~170 | 1 000 |
| Уникальных тикеров | 50 | 500 |

### Latency SLO

| Сценарий | p50 | p95 | p99 |
|---|---|---|---|
| WS push тика (от Quotes до Mobile) | < 100 мс | < 500 мс | < 1 с |
| `GET /quotes/{ticker}` (cached) | < 5 мс | < 20 мс | < 50 мс |
| `GET /portfolio` | < 30 мс | < 100 мс | < 300 мс |
| `POST /orders` | < 100 мс | < 300 мс | < 1 с |
| `GET /quotes/.../history` | < 200 мс | < 800 мс | < 2 с |

### Availability

| Уровень | Значение |
|---|---|
| MVP | best effort, перерывы допустимы |
| Stretch | 99% (≈7 ч/мес простоя) |

---

## 8.2. Расчёт нагрузки

Допущения:
- **CCU = 10 000**.
- 50 инструментов, тики ~1/сек на инструмент = **50 тиков/сек**.
- Каждый клиент подписан на 5 инструментов в среднем.
- Каждый клиент делает 1 ордер раз в 60 сек = **~170 ордеров/сек**.
- Каждый клиент дёргает `/portfolio` раз в 30 сек = **~330 RPS**.

### По компонентам

#### API Gateway (Ktor)

| Метрика | Значение | Запас одного инстанса |
|---|---|---|
| Открытые WS-соединения | 10 000 | до 50 000 на JVM с 4 GB heap |
| Исходящих WS-сообщений/сек | 10 000 × 5 = **50 000** | один инстанс ~200 000/сек |
| REST RPS | ~500 (включая ордера, портфели, history) | один инстанс ~10 000 RPS |
| CPU | ~30% от 2 ядер | — |
| RAM | ~1.5 GB | — |

→ **Один инстанс держит, есть запас ×3–5.** В demo деплое — 2 реплики для надёжности.

#### Core Service

| Метрика | Значение | Запас |
|---|---|---|
| Внутренний RPS | ~500 | один инстанс ~3 000 RPS |
| Транзакций PostgreSQL | ~170 TPS (ордера) + ~330 SELECT (портфели) | пул 30 соединений достаточно |
| CPU | ~20% | — |

→ **Одного инстанса хватает.** Для отказоустойчивости — 2.

#### PostgreSQL

| Операция | Объём | Потолок |
|---|---|---|
| Ордера (TX с 4–5 SQL) | 170 TPS | 3 000–5 000 TPS |
| Чтение портфеля | 330 RPS | 10 000+ RPS с индексом |
| Размер БД через 1 год | ~2 GB | помещается в RAM |

→ **Работает на 5–10% потолка.**

#### Quotes Service + Redis + ClickHouse

| Операция | Объём |
|---|---|
| Тиков из драйвера | 50/сек |
| Redis PUBLISH | 50/сек |
| Redis PUB/SUB delivery (с учётом fanout) | 50 × N подписчиков, эффективно ~50 000 messages/sec по downstream |
| ClickHouse INSERT (батчами) | 50 строк/сек |

Redis и ClickHouse даже не вспотеют — это **в тысячи раз ниже их потолков**.

---

## 8.3. Где упрёмся в потолок

### При увеличении CCU и RPS

```
   текущая нагрузка                FD limit Gateway
   10к CCU, 500 RPS    ───────▶    → 2-3 реплики
         │
         │ масштабирование
         ▼
     50к CCU, 2.5к RPS  ─────▶    (всё ещё OK)
         │
         │ в этом месте ломается
         ▼
   100к CCU, 5к ордеров/сек  ─▶   PostgreSQL writes
         │                       → async queue, replicas
         │ глубокая переработка
         ▼
   1М CCU, 50к ордеров/сек  ──▶  Redis Pub/Sub  → Kafka
                            ──▶  ClickHouse single node
                                 → ClickHouse Cluster
```

### Карта узких мест

| Узкое место | Срабатывает при | Решение |
|---|---|---|
| `ulimit -n` на хосте Gateway | 30k+ открытых WS | `ulimit -n 65536+` (must-have!) |
| GW heap / GC паузы | 50k+ WS на инстанс | реплики GW + ZGC |
| Connection pool Core Service → PG | 1k+ TPS на инстанс | HikariCP 30–50, 2+ реплики Core Service |
| PostgreSQL primary writes | 3–5k TPS | PgBouncer + read replicas |
| PostgreSQL TX latency на ордерах | 5–10k TPS | async queue (Redis Streams), исполнение в фоне |
| Redis Pub/Sub fanout | сотни тысяч msg/sec | Redis Cluster или замена на Kafka |
| ClickHouse single node | миллионы тиков/сек | CH Cluster + Kafka между Quotes и CH |
| Сеть на одном хосте | ~1 Гбит/с | разнести по нескольким хостам |

---

## 8.4. Стратегии масштабирования

### 8.4.1. По типу

| Сервис | Тип | Как |
|---|---|---|
| API Gateway | **horizontal** | stateless, любое число реплик за L4 LB; sticky sessions не нужны (Pub/Sub fanout-ит во все) |
| Core Service | **horizontal** | stateless; реплики делают свои TX к одной PG |
| Quotes Service | **vertical** + опц. partitioning | один драйвер = один читатель; при росте — раздельные процессы по диапазону тикеров |
| PostgreSQL | **vertical** + read replicas | бо́льшая VM + read-replicas для GET-эндпоинтов |
| Redis | **vertical** + Cluster | до миллиона ops/sec на ноде; дальше — Cluster |
| ClickHouse | **vertical** + Cluster | до сотен GB на ноде; дальше — Cluster + Kafka-buffer |

### 8.4.2. Сценарий горизонтального масштабирования Gateway

```
                 ┌──────────────────────────┐
                 │ L4 Load Balancer         │
                 │ haproxy / nginx          │
                 └────┬─────────┬─────────┬─┘
                      │         │         │
                      ▼         ▼         ▼
   ── API Gateway replicas ────────────────────
                  ┌────────┐┌────────┐┌────────┐
                  │ GW-1   ││ GW-2   ││ GW-3   │
                  │ 10k WS ││ 10k WS ││ 10k WS │
                  └─┬────▲─┘└─┬────▲─┘└─┬────▲─┘
                    │    │    │    │    │    │
                    │    │ PUBLISH (каждый GW подписан)
                    │    └────┴────┴────┴────┘
                    │                   ▲
                    │             ┌──────────────────┐
                    │             │ Redis Pub/Sub    │
                    ▼             └──────────────────┘
              ┌──────────────┐
              │ Core Service │
              └──────────────┘
```

**Свойства:**
- Любой клиент может попасть на любой GW.
- Каждый GW подписан на все каналы → получает все тики и шлёт нужным клиентам.
- Дублирования сообщений между клиентами нет (клиент подключён только к одному GW).

### 8.4.3. Async-очередь для всплесков ордеров 📦 Backlog

> Не реализуется в MVP — расчётная нагрузка ~170 ордеров/сек, до триггера далеко. Описано как точка эволюции для отчёта.

Если ордеров > 1k/сек:

```
   Mobile      Gateway        Core          Redis Stream      Worker
     │            │             │                 │             │
     │ POST /orders             │                 │             │
     │───────────▶│             │                 │             │
     │            │ validate + persist (PENDING)  │             │
     │            │────────────▶│                 │             │
     │            │             │ XADD orders_queue {orderId}   │
     │            │             │────────────────▶│             │
     │            │ 202 Accepted│                 │             │
     │            │◀────────────│                 │             │
     │ 202 Accepted (с orderId) │                 │             │
     │◀───────────│             │                 │             │
     │            │             │                 │             │
   . . . фоновый воркер . . .                                    │
     │            │             │                 │ XREADGROUP  │
     │            │             │                 │◀────────────│
     │            │             │ execute(orderId)              │
     │            │             │◀──────────────────────────────│
     │            │             │ TX: списать, обновить позицию, EXECUTED
     │            │             │──────────────────────────────▶│ ok
     │            │             │                               │
   . . . WS-уведомление об исполнении . . .                     │
     │ подписан на orders.events│                               │
     │───────────▶│             │                               │
     │ {type:"order.executed", orderId, price}                  │
     │◀───────────│             │                               │
```

В MVP можно обойтись без этого, но в архитектуру заложено как опция.

---

## 8.5. Оптимизации, которые надо сделать сразу

### Обязательные (без них упрёмся даже на малой нагрузке)

1. **`ulimit -n 65536`** на хосте Gateway. 10к WS = 10к FD; дефолт 1024 — мгновенный отказ.
2. **HikariCP пул** в Core Service: размер 30–50, `connectionTimeout` 1s, `maxLifetime` 30 min.
3. **PostgreSQL config:**
   ```ini
   max_connections = 200
   shared_buffers = 25% RAM
   effective_cache_size = 75% RAM
   work_mem = 16MB
   maintenance_work_mem = 256MB
   ```
4. **WS heartbeat** в Ktor: ping/pong каждые 30 сек, timeout 60 сек — иначе зомби-соединения съедают FD.
5. **JVM GC**: G1GC по умолчанию — на 10к WS справляется без проблем. ZGC (`-XX:+UseZGC`) — 📦 backlog, в MVP не нужен.
6. **Redis pipelining** в Quotes Service: PUBLISH + HSET + XADD одной round-trip.
7. **ClickHouse batch INSERT**: накапливать ≥ 1 секунды или ≥ 1000 строк, не вставлять по одной.

### 📦 Желательные (Backlog, не для MVP)

> Эти пункты дают marginal-выигрыш и в MVP не реализуются. Оставлены как опции при развитии.

8. Кэш `instruments` в памяти Gateway (TTL 5 мин) — каталог редко меняется.
9. ETag/If-None-Match на `/instruments`, `/portfolio`.
10. HTTP/2 на nginx — мультиплексирование REST поверх одного коннекта от мобилки.

### Учитываемые риски

- **Argon2 — CPU-bottleneck при burst-логине.** Одна верификация ~30–80 мс CPU. При 10к одновременных регистраций (Load Simulator) пул потоков Core Service может упереться в CPU **раньше**, чем в PostgreSQL. Митигация: вынести `argon2.verify` в отдельный пул потоков (`Dispatchers.IO` с увеличенным `parallelism`), не блокировать основной пул для ордеров. См. [ADR-006](adr/ADR-006-argon2.md).
- **Pattern subscribe `channel:quotes:*`** в Redis медленнее обычного. На 50 каналах не критично; при росте до 500+ — переходить на explicit-подписки на нужные тикеры.

---

## 8.6. Деградация под нагрузкой (graceful) 📦 Backlog

> Базовый rate limiting реализуем (см. §8.5 «Обязательные»). Полноценный circuit breaker — оставляем на этап развития.

При перегрузке система должна **деградировать предсказуемо**, а не падать.

| Уровень нагрузки | Поведение |
|---|---|
| 0–80% capacity | штатная работа, все SLO |
| 80–100% | rate limiting на пользователя (429), приоритизация по типу запроса |
| 100–120% | дроп новых WS-подписок (1013 Try Again Later); существующие продолжают работать |
| > 120% | дроп новых REST (503); circuit breaker на Core Service |

### Circuit breaker

В Gateway между Core Service: если 50% запросов в окне 30 сек упали → break, отдаём 503 на 10 сек, потом half-open.

---

## 8.7. Backpressure в потоке котировок

Если Gateway не успевает писать в WS медленному клиенту:

```kotlin
// псевдокод
val channel = Channel<Quote>(capacity = 100)  // ограниченный буфер
launch {
    redisPubSub.subscribe { tick ->
        channel.trySend(tick)  // если переполнен — дроп
    }
}
launch {
    for (tick in channel) {
        wsSession.send(tick)  // блокируется на медленном клиенте
    }
}
```

**Правило:** **дроп старых тиков, а не блокировка publisher**. Клиенту лучше пропустить пару тиков, чем заморозить весь pipeline.

---

## 8.8. Capacity planning (sizing)

### Demo (10к CCU, одна VM)

См. [04. Deployment](04-deployment.md) — итого ~11 vCPU, ~15 GB RAM. Влезает в `c5.4xlarge` или 16 vCPU / 16 GB.

### Stretch (50к CCU, multi-host)

| Хост | Сервисы | Sizing |
|---|---|---|
| edge-1 | nginx + GW × 3 | 8 vCPU / 8 GB |
| edge-2 | GW × 2 | 4 vCPU / 4 GB |
| app-1 | Core Service × 2, Quotes Service | 4 vCPU / 8 GB |
| data-1 | PostgreSQL primary | 8 vCPU / 32 GB |
| data-2 | PostgreSQL replica + ClickHouse | 8 vCPU / 32 GB |
| data-3 | Redis primary + replica | 4 vCPU / 16 GB |
| obs-1 | OTel + Jaeger + Prom + Grafana | 4 vCPU / 16 GB |

---

## 8.9. Доказательство соответствия SLO

В отчёте раздел «Тестирование» включает:

1. Прогон Load Simulator с `--scenario realistic --users 10000 --duration 10m`.
2. Сбор метрик через OpenTelemetry: latency-гистограммы, error rate, RPS.
3. Сравнение с целями из [8.1](#81-целевые-slo).
4. Графики из Grafana, приложенные в отчёт.

---

## Связанные документы

- ⬅ [07. Согласованность и транзакции](07-consistency.md)
- ➡ [09. Наблюдаемость](09-observability.md)
- ➡ [10. Ключевые сценарии](10-scenarios.md)
