# 02. Структура системы

## Назначение

Раскрыть «чёрный ящик» Stockyard в **развёртываемые единицы**: микросервисы, клиенты, хранилища и инфраструктурные компоненты. Под «контейнером» здесь понимается отдельный исполняемый процесс — не Docker-контейнер (хотя для Stockyard почти всё пакуется в Docker).

## Container-диаграмма

```mermaid
graph TB
    %% Clients
    Android["📱 <b>Android App</b><br/>Kotlin + Jetpack Compose"]
    RN["📱 <b>Cross-platform App</b><br/>React Native"]
    Sim["🤖 <b>Load Simulator</b><br/>Kotlin / Go / Python"]

    %% Edge / Gateway
    GW["🚪 <b>API Gateway</b><br/>Kotlin + Ktor<br/><i>BFF, WS-fanout, JWT</i>"]

    %% Backend services
    CoreSvc["⚙️ <b>Core Service</b><br/>Kotlin + Ktor<br/><i>бизнес-логика, ордера</i>"]
    QSvc["📡 <b>Quotes Service</b><br/>Go<br/><i>сбор котировок</i>"]

    %% Driver
    Drv["🔌 <b>C Linux Driver</b><br/><i>/dev/stockyard</i><br/>имитация биржи"]

    %% Storages
    PG[("🗄️ <b>PostgreSQL</b><br/>users, orders,<br/>positions, txns")]
    Rds[("⚡ <b>Redis / KeyDB</b><br/>cache, sessions,<br/>pub/sub, streams")]
    CH[("📊 <b>ClickHouse</b><br/>история тиков<br/>(time-series)")]

    %% Observability
    OTC["📈 <b>OTel Collector</b><br/>traces, metrics, logs"]

    %% External flows
    Android -->|"HTTPS / WSS"| GW
    RN -->|"HTTPS / WSS"| GW
    Sim -->|"HTTPS / WSS<br/>(тот же API)"| GW

    %% Internal
    GW -->|"HTTP/JSON<br/>internal API"| CoreSvc
    GW -->|"PUB/SUB<br/>HSET / HGET"| Rds
    CoreSvc -->|"SQL"| PG
    CoreSvc -->|"HGET<br/>(текущая цена)"| Rds
    CoreSvc -->|"SELECT<br/>(история)"| CH

    %% Quotes pipeline
    Drv -->|"read()"| QSvc
    QSvc -->|"PUBLISH<br/>HSET<br/>XADD"| Rds
    QSvc -->|"INSERT (батч)"| CH

    %% Telemetry
    GW -.->|"OTLP"| OTC
    CoreSvc -.->|"OTLP"| OTC
    QSvc -.->|"OTLP"| OTC

    %% Styles
    classDef client fill:#dae8fc,stroke:#6c8ebf
    classDef service fill:#d5e8d4,stroke:#82b366
    classDef storage fill:#fff2cc,stroke:#d6b656
    classDef driver fill:#f8cecc,stroke:#b85450
    classDef observ fill:#e1d5e7,stroke:#9673a6

    class Android,RN,Sim client
    class GW,CoreSvc,QSvc service
    class PG,Rds,CH storage
    class Drv driver
    class OTC observ
```

## Каталог контейнеров

### Клиенты

| Контейнер | Технология | Назначение | Источник в ТЗ |
|---|---|---|---|
| **Android App** | Kotlin + Jetpack Compose | Нативное мобильное приложение | п.1 |
| **Cross-platform App** | React Native | Кросс-платформенный клиент | п.2 |
| **Load Simulator** | Kotlin / Go / Python | Имитация 10к клиентов для тестов | п.5 |

### Микросервисы

| Контейнер | Технология | Назначение | Источник в ТЗ |
|---|---|---|---|
| **API Gateway** | Kotlin + Ktor | Единая точка входа, JWT-auth, fanout котировок | п.3 |
| **Core Service** | Kotlin + Ktor | Бизнес-логика, ордера, портфели | п.4 |
| **Quotes Service** | Go | Чтение драйвера и публикация котировок | п.6 |

### Системные компоненты

| Контейнер | Технология | Назначение | Источник в ТЗ |
|---|---|---|---|
| **C Linux Driver** | C, kernel module / character device | Имитация биржи, поток тиков | п.7 |

### Хранилища (готовые компоненты)

| Контейнер | Назначение |
|---|---|
| **PostgreSQL** | OLTP: пользователи, ордера, позиции, транзакции |
| **Redis / KeyDB** | Кэш текущих котировок, сессии, pub/sub, streams |
| **ClickHouse** | Time-series: история тиков для графиков |

### Observability

| Контейнер | Назначение |
|---|---|
| **OpenTelemetry Collector** | Приёмник трейсов/метрик/логов, экспорт в Jaeger/Prometheus/Loki |

## Группировка по слоям (logical layering)

```mermaid
graph TB
    subgraph L1["🔵 Client Layer"]
        Android
        RN
        Sim
    end

    subgraph L2["🟢 Edge Layer"]
        GW["API Gateway"]
    end

    subgraph L3["🟢 Application Layer"]
        CoreSvc["Core Service"]
        QSvc["Quotes Service"]
    end

    subgraph L4["🟡 Data Layer"]
        PG[("PostgreSQL")]
        Rds[("Redis")]
        CH[("ClickHouse")]
    end

    subgraph L5["🔴 Driver Layer"]
        Drv["C Driver"]
    end

    L1 --> L2 --> L3 --> L4
    L5 --> L3

    style L1 fill:#dae8fc
    style L2 fill:#d5e8d4
    style L3 fill:#d5e8d4
    style L4 fill:#fff2cc
    style L5 fill:#f8cecc
```

## Принципы границ контейнеров

1. **Один процесс — одна зона ответственности** (Single Responsibility).
2. **Stateless по умолчанию** — состояние только в Data Layer.
3. **Однонаправленность зависимостей** — Edge зовёт Application, Application пишет в Data; обратных вызовов нет.
4. **Разные языки → разные контейнеры** (требование ТЗ): Kotlin, Go, C — отдельные процессы.
5. **Один публичный вход** — API Gateway. Все остальные сервисы — приватные.

## Какие контейнеры публичны

API Gateway — **multi-homed**: один процесс с интерфейсами в обеих сетях. Снаружи слушает HTTPS/WSS на :443, изнутри ходит к Core Service и Redis. Все остальные сервисы и хранилища — только в приватной сети.

```mermaid
graph LR
    Internet((Internet))

    subgraph Public["🌐 Public-facing"]
        GWp["API Gateway :443<br/>(public face)"]
    end

    subgraph Private["🔒 Private network"]
        GWi["API Gateway<br/>(private face,<br/>тот же процесс)"]
        CoreSvc
        QSvc
        PG
        Rds
        CH
    end

    Internet --> GWp
    GWp -.->|"один процесс,<br/>две сети"| GWi
    GWi --> CoreSvc
    GWi --> Rds
    QSvc --> Rds
    QSvc --> CH
    CoreSvc --> PG
    CoreSvc --> Rds
    CoreSvc --> CH
```

**Только API Gateway** доступен из публичной сети. Прямого доступа извне к Core Service, Quotes Service или хранилищам нет.

## Связанные документы

- ⬅ [01. Контекст системы](01-context.md)
- ➡ [03. Внутреннее устройство сервисов](03-components.md) — что внутри каждого сервиса.
- ➡ [04. Развёртывание и топология](04-deployment.md) — как сервисы разворачиваются физически.
- ➡ [05. Коммуникация и API](05-communication.md) — какие протоколы используются между ними.
