# 03. Внутреннее устройство сервисов

## Назначение

Раскрыть **внутреннее устройство каждого микросервиса** — на какие модули/компоненты он делится, как они связаны и где какой код живёт.

Это уровень детализации, на котором разработчик может начать кодить, понимая, какой код в какой пакет/модуль попадает.

---

## 3.1. API Gateway (Ktor)

### Назначение
Единственная публичная точка входа. Принимает мобильные запросы, аутентифицирует, маршрутизирует во внутренние сервисы, делает fanout котировок по WebSocket.

### Компонентная диаграмма

```mermaid
graph TB
    subgraph GW["API Gateway (Kotlin + Ktor)"]
        Router["<b>HTTP Router</b><br/>Ktor routing"]
        WSHub["<b>WS Hub</b><br/>connection registry,<br/>fanout"]
        Auth["<b>Auth Module</b><br/>JWT validation,<br/>refresh"]
        RateLim["<b>Rate Limiter</b><br/>per-user quota"]
        DBClient["<b>DB Service Client</b><br/>HTTP/JSON"]
        RedisClient["<b>Redis Client</b><br/>Pub/Sub<br/>+ HASH read"]
        TelemHook["<b>Telemetry Hook</b><br/>OTel SDK"]
    end

    Mobile["📱 Mobile"]
    DBSvc["DB Service"]
    Redis[("Redis")]
    OTel["OTel"]

    Mobile -->|REST| Router
    Mobile -->|WS upgrade| WSHub
    Router --> Auth
    Router --> RateLim
    Router --> DBClient
    WSHub --> Auth
    WSHub --> RedisClient
    Router --> RedisClient
    DBClient --> DBSvc
    RedisClient --> Redis
    Auth -.-> TelemHook
    Router -.-> TelemHook
    WSHub -.-> TelemHook
    TelemHook -.-> OTel
```

### Компоненты

| Компонент | Ответственность |
|---|---|
| **HTTP Router** | Маршрутизация REST: `/auth`, `/orders`, `/portfolio`, `/instruments`, `/quotes/*`. |
| **WS Hub** | Реестр открытых WS-соединений, подписка/отписка на тикеры, fanout входящих pub/sub-сообщений. |
| **Auth Module** | Выпуск/валидация JWT, refresh-tokens, blacklist в Redis. |
| **Rate Limiter** | Ограничение RPS на пользователя (token bucket в Redis). |
| **DB Service Client** | HTTP-клиент к DB Service (Ktor client + retry/timeout). |
| **Redis Client** | Подписка на `channel:quotes:*`, чтение `quotes:{ticker}`. |
| **Telemetry Hook** | Перехват входящих/исходящих вызовов для трейсинга. |

### Структура исходников

```
gateway/
├── src/main/kotlin/com/stockyard/gateway/
│   ├── Application.kt              # точка входа Ktor
│   ├── config/
│   │   ├── AppConfig.kt
│   │   └── Plugins.kt              # Auth, RateLimit, OTel installation
│   ├── routing/
│   │   ├── AuthRoutes.kt
│   │   ├── OrdersRoutes.kt
│   │   ├── PortfolioRoutes.kt
│   │   ├── InstrumentsRoutes.kt
│   │   └── QuotesRoutes.kt
│   ├── ws/
│   │   ├── WsHub.kt                # connection registry
│   │   └── QuotesSubscriber.kt     # Redis pub/sub bridge
│   ├── auth/
│   │   ├── JwtVerifier.kt
│   │   └── SessionStore.kt
│   ├── client/
│   │   └── DbServiceClient.kt
│   └── telemetry/
│       └── OtelInit.kt
└── build.gradle.kts
```

---

## 3.2. DB Service (Kotlin)

### Назначение
Источник истины по бизнес-сущностям: пользователи, балансы, ордера, портфели. Вся транзакционная логика — здесь.

### Компонентная диаграмма

```mermaid
graph TB
    subgraph DBSvc["DB Service (Kotlin + Ktor)"]
        IntAPI["<b>Internal HTTP API</b><br/>Ktor routing"]

        subgraph Domain["Domain layer"]
            UserSvc["<b>User Service</b><br/>register, auth"]
            OrderSvc["<b>Order Service</b><br/>place, list,<br/>execute"]
            PortfolioSvc["<b>Portfolio Service</b><br/>positions,<br/>balance"]
            InstrSvc["<b>Instrument Service</b><br/>каталог"]
        end

        subgraph Repos["Repository layer (raw SQL)"]
            UserRepo["UserRepo"]
            OrderRepo["OrderRepo"]
            PositionRepo["PositionRepo"]
            TxnRepo["TxnRepo"]
            InstrRepo["InstrumentRepo"]
        end

        TxMgr["<b>TransactionManager</b><br/>JDBC + HikariCP"]
        QuotesPort["<b>Quotes Port</b><br/>Redis HGET<br/>+ ClickHouse SELECT"]
    end

    GW["Gateway"]
    PG[("PostgreSQL")]
    Rds[("Redis")]
    CH[("ClickHouse")]

    GW -->|HTTP| IntAPI
    IntAPI --> UserSvc
    IntAPI --> OrderSvc
    IntAPI --> PortfolioSvc
    IntAPI --> InstrSvc

    UserSvc --> UserRepo
    OrderSvc --> OrderRepo
    OrderSvc --> PositionRepo
    OrderSvc --> TxnRepo
    OrderSvc --> QuotesPort
    PortfolioSvc --> PositionRepo
    InstrSvc --> InstrRepo

    UserRepo & OrderRepo & PositionRepo & TxnRepo & InstrRepo --> TxMgr
    TxMgr -->|JDBC| PG
    QuotesPort --> Rds
    QuotesPort --> CH
```

### Компоненты

| Компонент | Ответственность |
|---|---|
| **Internal HTTP API** | REST-эндпоинты для Gateway (`/internal/*`). |
| **User Service** | Регистрация, логин, хэш паролей (Argon2/BCrypt). |
| **Order Service** | Создание ордера, валидация, исполнение по рынку, обновление позиции и баланса в одной TX. |
| **Portfolio Service** | Чтение портфеля, балансов, истории транзакций. |
| **Instrument Service** | Чтение каталога инструментов. |
| **Repositories** | Голый SQL (по требованию ТЗ), без ORM. |
| **TransactionManager** | Connection pool (HikariCP) + явное управление транзакциями. |
| **Quotes Port** | Адаптер для чтения текущей цены из Redis и истории из ClickHouse. |

### Структура исходников

```
db-service/
├── src/main/kotlin/com/stockyard/db/
│   ├── Application.kt
│   ├── api/
│   │   ├── UserApi.kt
│   │   ├── OrderApi.kt
│   │   ├── PortfolioApi.kt
│   │   └── InstrumentApi.kt
│   ├── domain/
│   │   ├── user/
│   │   │   ├── User.kt
│   │   │   ├── UserService.kt
│   │   │   └── UserRepository.kt
│   │   ├── order/
│   │   │   ├── Order.kt
│   │   │   ├── OrderService.kt
│   │   │   └── OrderRepository.kt
│   │   ├── portfolio/
│   │   │   ├── Position.kt
│   │   │   ├── PortfolioService.kt
│   │   │   └── PositionRepository.kt
│   │   └── instrument/
│   │       ├── Instrument.kt
│   │       └── InstrumentRepository.kt
│   ├── persistence/
│   │   └── TransactionManager.kt
│   ├── ports/
│   │   └── QuotesPort.kt
│   └── telemetry/
│       └── OtelInit.kt
├── src/main/resources/
│   └── db/migration/              # Flyway миграции
│       ├── V1__init.sql
│       ├── V2__instruments.sql
│       └── ...
└── build.gradle.kts
```

---

## 3.3. Quotes Service (Go)

### Назначение
Stream-процессор: читает поток тиков из C-драйвера и распространяет его по системе (Redis pub/sub, Redis cache, ClickHouse).

### Компонентная диаграмма

```mermaid
graph TB
    subgraph QSvc["Quotes Service (Go)"]
        Reader["<b>Driver Reader</b><br/>open /dev/stockyard,<br/>read loop"]
        Parser["<b>Tick Parser</b><br/>binary → struct"]
        Fanout["<b>Fanout</b><br/>goroutines per sink"]

        RedisPub["<b>Redis Publisher</b><br/>PUBLISH +<br/>HSET +<br/>XADD"]
        CHWriter["<b>ClickHouse Batcher</b><br/>буфер 1 сек,<br/>batch INSERT"]

        Health["<b>Health/Metrics</b><br/>HTTP :8080<br/>/healthz, /metrics"]
    end

    Drv["/dev/stockyard"]
    Rds[("Redis")]
    CH[("ClickHouse")]

    Drv --> Reader
    Reader --> Parser
    Parser --> Fanout
    Fanout --> RedisPub
    Fanout --> CHWriter
    RedisPub --> Rds
    CHWriter --> CH
```

### Компоненты

| Компонент | Ответственность |
|---|---|
| **Driver Reader** | Открыть `/dev/stockyard`, читать в цикле, обрабатывать reconnect. |
| **Tick Parser** | Парсинг бинарного формата тика в структуру. |
| **Fanout** | Распределение каждого тика по нескольким приёмникам через каналы (channels). |
| **Redis Publisher** | `PUBLISH channel:quotes:{ticker}` + `HSET quotes:{ticker}` + `XADD stream:quotes`. |
| **ClickHouse Batcher** | Накопление тиков в буфере, batch INSERT раз в секунду. |
| **Health/Metrics** | Минимальный HTTP: `/healthz`, `/metrics` для Prometheus. |

### Структура исходников

```
quotes-service/
├── cmd/quotes/
│   └── main.go
├── internal/
│   ├── driver/
│   │   ├── reader.go              # /dev/stockyard
│   │   └── parser.go
│   ├── pipeline/
│   │   ├── fanout.go
│   │   └── tick.go                # типы
│   ├── sinks/
│   │   ├── redis.go
│   │   └── clickhouse.go
│   ├── health/
│   │   └── server.go
│   └── telemetry/
│       └── otel.go
├── go.mod
└── go.sum
```

---

## 3.4. Load Simulator

### Назначение
Имитирует 10 000 одновременных клиентов, бьющихся в публичный API Gateway. Используется для системного тестирования и доказательства соответствия SLO.

### Компонентная диаграмма

```mermaid
graph TB
    subgraph Sim["Load Simulator (Kotlin coroutines / Go / Python asyncio)"]
        CLI["<b>CLI / Config</b><br/>--users, --duration,<br/>--scenario"]
        ScenarioRunner["<b>Scenario Runner</b><br/>оркестрация"]
        UserPool["<b>Virtual User Pool</b><br/>N корутин,<br/>каждая = клиент"]
        HttpClient["<b>HTTP Client</b><br/>auth, orders,<br/>portfolio"]
        WsClient["<b>WS Client</b><br/>подписка на тикеры,<br/>чтение тиков"]
        Metrics["<b>Metrics Collector</b><br/>latency p50/p95/p99,<br/>error rate, RPS"]
        Reporter["<b>Reporter</b><br/>console + OTel/<br/>Prometheus"]
    end

    GW["API Gateway"]
    OTel["OTel"]

    CLI --> ScenarioRunner
    ScenarioRunner --> UserPool
    UserPool --> HttpClient
    UserPool --> WsClient
    HttpClient -->|REST| GW
    WsClient -->|WSS| GW
    HttpClient --> Metrics
    WsClient --> Metrics
    Metrics --> Reporter
    Reporter -.->|OTLP| OTel
```

### Компоненты

| Компонент | Ответственность |
|---|---|
| **CLI / Config** | Параметры запуска: число клиентов, длительность, тип сценария. |
| **Scenario Runner** | Оркестрация: ramp-up, steady state, ramp-down. |
| **Virtual User Pool** | N лёгких единиц параллелизма, каждая ведёт себя как один клиент. |
| **HTTP Client** | REST-вызовы (login, orders, portfolio). |
| **WS Client** | Подписка на котировки, чтение тиков. |
| **Metrics Collector** | Сбор latency, ошибок, RPS. |
| **Reporter** | Вывод в консоль и/или OTel/Prometheus. |

### Сценарии

| Сценарий | Описание |
|---|---|
| `smoke` | 10 клиентов, 1 минута — проверка работоспособности |
| `realistic` | 10 000 клиентов, 10 минут, mix 80/20 read/write |
| `stress` | плавный ramp-up до отказа, фиксация breaking point |
| `soak` | 5 000 клиентов, 1 час — поиск утечек |

### Структура исходников

```
load-simulator/
├── src/main/kotlin/com/stockyard/sim/
│   ├── Main.kt
│   ├── config/
│   │   └── SimConfig.kt
│   ├── runner/
│   │   ├── ScenarioRunner.kt
│   │   ├── RealisticScenario.kt
│   │   └── StressScenario.kt
│   ├── client/
│   │   ├── VirtualUser.kt
│   │   ├── HttpApi.kt
│   │   └── WsApi.kt
│   └── metrics/
│       ├── Histogram.kt
│       └── Reporter.kt
└── build.gradle.kts
```

---

## 3.5. C Linux Driver

### Назначение
Имитирует биржу: генерирует поток тиков (random walk цен) и отдаёт его через character device.

### Компоненты

| Компонент | Ответственность |
|---|---|
| **Module init/exit** | Регистрация character device при `insmod`. |
| **Tick Generator** | Таймер ядра, генерирует тики раз в N мс. |
| **Random Walk Engine** | Изменение цен инструментов с заданной волатильностью. |
| **Char Device Ops** | `read()`/`poll()` интерфейс для пользовательского пространства. |
| **Ring Buffer** | Хранение последних N тиков на случай медленного читателя. |

### Файлы

```
driver/
├── Makefile
├── stockyard_driver.c
├── stockyard_driver.h
└── README.md
```

### Интерфейс пользовательского пространства

```c
// /dev/stockyard, формат тика — packed struct
struct stockyard_tick {
    char     ticker[8];    // null-padded
    uint64_t ts_ns;        // monotonic timestamp
    int64_t  bid_cents;
    int64_t  ask_cents;
    int64_t  last_cents;
    uint32_t volume;
} __attribute__((packed));
```

---

## 3.6. Android-приложение

### Назначение
Нативный мобильный клиент для трейдеров. Показывает котировки в реальном времени, портфель и позволяет размещать ордера.

### Архитектура: MVVM + Clean layers

```mermaid
graph TB
    subgraph App["Android App (Kotlin + Jetpack Compose)"]
        subgraph UI["UI layer (Compose)"]
            Screens["Screens<br/>Login, Quotes, OrderForm,<br/>Portfolio, History"]
            Comps["Reusable Composables<br/>QuoteCard, Chart, Button..."]
        end

        subgraph VM["ViewModel layer"]
            QuotesVM["QuotesViewModel"]
            OrdersVM["OrdersViewModel"]
            PortfolioVM["PortfolioViewModel"]
            AuthVM["AuthViewModel"]
        end

        subgraph Domain["Domain layer"]
            UseCases["UseCases<br/>(PlaceOrder,<br/>SubscribeToTicker, ...)"]
            Models["Domain models"]
        end

        subgraph Data["Data layer"]
            Repos["Repositories<br/>(QuotesRepo, OrdersRepo, ...)"]
            ApiClient["REST API client<br/>(OkHttp + Retrofit + kotlinx.serialization)"]
            WsClient["WebSocket client<br/>(OkHttp WS)"]
            LocalCache["Local cache<br/>(DataStore / Room — опц.)"]
            JwtStore["JWT secure storage<br/>(EncryptedSharedPreferences)"]
        end
    end

    Screens --> VM
    VM --> UseCases
    UseCases --> Repos
    Repos --> ApiClient
    Repos --> WsClient
    Repos --> LocalCache
    ApiClient --> JwtStore
```

### Компоненты

| Компонент | Ответственность |
|---|---|
| **Screens (Compose)** | Декларативная разметка экранов. Без бизнес-логики. |
| **ViewModels** | UI state holders, переживают rotation. Подписываются на потоки из репозиториев через `StateFlow`. |
| **UseCases** | Один use case = одно бизнес-действие (place order, subscribe to quotes). Облегчают юнит-тестирование. |
| **Repositories** | Скрывают источник данных (REST / WS / cache) от верхних слоёв. |
| **REST API client** | Retrofit + OkHttp + kotlinx.serialization. Перехватчик подставляет JWT. |
| **WebSocket client** | OkHttp WebSocket. Авто-reconnect с exponential backoff. |
| **JWT secure storage** | Refresh-token шифруется через EncryptedSharedPreferences. |

### Структура исходников

```
android-app/
├── app/src/main/kotlin/com/stockyard/android/
│   ├── MainActivity.kt
│   ├── ui/
│   │   ├── theme/
│   │   ├── screens/
│   │   │   ├── login/LoginScreen.kt
│   │   │   ├── quotes/QuotesScreen.kt
│   │   │   ├── order/OrderFormScreen.kt
│   │   │   ├── portfolio/PortfolioScreen.kt
│   │   │   └── history/HistoryScreen.kt
│   │   └── components/
│   │       ├── QuoteCard.kt
│   │       └── PriceChart.kt
│   ├── viewmodel/
│   ├── domain/
│   │   ├── model/
│   │   └── usecase/
│   ├── data/
│   │   ├── api/                # Retrofit interfaces
│   │   ├── ws/
│   │   ├── repository/
│   │   └── auth/
│   └── di/                     # Hilt modules
└── build.gradle.kts
```

### Технические решения
- **DI:** Hilt (стандарт для Android).
- **Navigation:** Jetpack Navigation Compose.
- **Async:** Kotlin coroutines + Flow.
- **Charts:** простая Compose-реализация на Canvas (или `compose-charts` если время позволит).

---

## 3.7. React Native приложение

### Назначение
Кросс-платформенный клиент (iOS + Android) — выполняет роль аналога Android-клиента, но на одной кодовой базе.

### Архитектура: Container / Presentational + Redux Toolkit

```mermaid
graph TB
    subgraph App["React Native App (TypeScript)"]
        subgraph Screens["Screens (Container components)"]
            S1["LoginScreen"]
            S2["QuotesScreen"]
            S3["OrderFormScreen"]
            S4["PortfolioScreen"]
        end

        subgraph Components["Presentational components"]
            C1["QuoteCard"]
            C2["Chart"]
            C3["Button"]
        end

        subgraph State["Redux Toolkit"]
            Slices["slices/<br/>auth, quotes,<br/>orders, portfolio"]
            Thunks["async thunks<br/>(API calls)"]
        end

        subgraph DataLayer["Data layer"]
            Api["API client<br/>(axios)"]
            Ws["WebSocket client<br/>(reconnecting-websocket)"]
            Storage["AsyncStorage<br/>(JWT)"]
        end
    end

    Screens --> Components
    Screens --> Slices
    Slices --> Thunks
    Thunks --> Api
    Slices --> Ws
    Api --> Storage
```

### Компоненты

| Компонент | Ответственность |
|---|---|
| **Screens** | Container-компоненты, подключены к Redux store через `useSelector` / `useDispatch`. |
| **Presentational components** | Чистые React-компоненты без знания о store. |
| **Redux slices** | State + reducers, по одному на домен (auth, quotes, orders, portfolio). |
| **Async thunks** | Обёртка над API-вызовами (createAsyncThunk). |
| **API client** | Axios + перехватчик с JWT. |
| **WebSocket client** | Реconnecting-WS с auto-resubscribe на тикеры. |
| **AsyncStorage** | Refresh-token (с использованием `react-native-keychain` для надёжности). |

### Структура исходников

```
rn-app/
├── src/
│   ├── App.tsx
│   ├── navigation/
│   │   └── RootNavigator.tsx
│   ├── screens/
│   │   ├── LoginScreen.tsx
│   │   ├── QuotesScreen.tsx
│   │   ├── OrderFormScreen.tsx
│   │   └── PortfolioScreen.tsx
│   ├── components/
│   ├── store/
│   │   ├── index.ts
│   │   └── slices/
│   │       ├── authSlice.ts
│   │       ├── quotesSlice.ts
│   │       └── ordersSlice.ts
│   ├── api/
│   │   ├── client.ts
│   │   └── ws.ts
│   └── types/
├── package.json
└── tsconfig.json
```

### Технические решения
- **Navigation:** React Navigation (stack + bottom tabs).
- **TypeScript:** обязателен — статическая типизация контрактов с API.
- **Charts:** `victory-native` или `react-native-chart-kit`.
- **Тестирование:** Jest + React Testing Library (см. [11. Тестирование](11-testing.md)).

---

## Связанные документы

- ⬅ [02. Структура системы](02-containers.md)
- ➡ [04. Развёртывание и топология](04-deployment.md)
- ➡ [05. Коммуникация и API](05-communication.md)
- ➡ [06. Архитектура данных](06-data.md)
