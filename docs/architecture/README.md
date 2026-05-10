# Архитектурная документация Stockyard

12 разделов, каждый отвечает на один вопрос. Читай линейно (от границ системы к эксплуатации) или открывай нужный по теме.

- [01. Контекст системы. Кто пользуется системой и с какими внешними системами она интегрируется?](01-context.md)
- [02. Структура системы. Из каких микросервисов, клиентов и хранилищ она состоит?](02-containers.md)
- [03. Внутреннее устройство сервисов. На какие модули делится каждый микросервис и как организован код?](03-components.md)
- [04. Развёртывание и топология. Как сервисы упаковываются и разворачиваются физически?](04-deployment.md)
- [05. Коммуникация и API. По каким протоколам и контрактам общаются компоненты?](05-communication.md)
- [06. Архитектура данных. Какие данные где хранятся, как структурированы и как живут во времени?](06-data.md)
- [07. Согласованность и транзакции. Какие гарантии согласованности обеспечиваются и как работают транзакции?](07-consistency.md)
- [08. Масштабирование и производительность. Какие нагрузки система выдерживает и за счёт чего масштабируется?](08-scaling.md)
- [09. Наблюдаемость. Какие метрики, трейсы и логи собираются и как мониторится система?](09-observability.md)
- [10. Ключевые сценарии. Как компоненты взаимодействуют в основных пользовательских и системных потоках?](10-scenarios.md)
- [11. Стратегия тестирования. Какие три уровня тестов используются и какое тестовое инструментальное обеспечение?](11-testing.md)
- [12. Эксплуатация уровня хранения. Какие конфиги, connection-pool'ы, health-checks, бэкапы и failure modes у PG/Redis/ClickHouse?](12-storage-operations.md)

## Архитектурные решения (ADR)

- [Все ADR с индексом](adr/README.md)
- [ADR-001](adr/ADR-001-redis-pubsub.md) — Redis Pub/Sub как брокер котировок
- [ADR-002](adr/ADR-002-clickhouse-timeseries.md) — ClickHouse для истории тиков
- [ADR-003](adr/ADR-003-rest-ws-not-grpc.md) — REST + WebSocket вместо gRPC
- [ADR-004](adr/ADR-004-single-tx-writer.md) — единственный транзакционный writer
- [ADR-005](adr/ADR-005-idempotency-key.md) — идемпотентность через UNIQUE-индекс
- [ADR-006](adr/ADR-006-argon2.md) — Argon2id для паролей
- [ADR-007](adr/ADR-007-idempotency-key-retention.md) — idempotency-keys бессрочно
- [ADR-008](adr/ADR-008-pg-no-partitioning-mvp.md) — PG без партиционирования в MVP
- [ADR-009](adr/ADR-009-gradle-single-module.md) — Gradle single-module per backend service

## MVP scope vs Backlog

Архитектура описывает и MVP, и точки эволюции (для отчёта/защиты). Чтобы команда из 10 человек не пугалась объёма, разделы помечены:

- MVP must-have — обязательно делаем за семестр.
- Backlog — описано для отчёта, не реализуем в MVP.

Главные backlog-блоки (если встретите — пропускайте при оценке трудозатрат):

| Тема | Где помечено |
|---|---|
| Async order queue (Saga) | [08. §8.4.3](08-scaling.md) |
| Circuit breaker / graceful degradation | [08. §8.6](08-scaling.md) |
| ZGC, HTTP/2, ETag | [08. §8.5](08-scaling.md) |
| Distributed lock | [06. §6.3.2](06-data.md), [07. §7.4.3](07-consistency.md) |
| Outbox pattern | [07. §7.8](07-consistency.md) |
| Slippage check | [07. §7.5.3](07-consistency.md) |
| Prod-like multi-host deployment | [04. §4.2 Вариант C](04-deployment.md) |
| Алертинг и Loki | [09. §9.8](09-observability.md) |

В MVP обязательно: 4 микросервиса + 2 мобильных клиента + C-драйвер + PostgreSQL + Redis + ClickHouse + базовый OpenTelemetry + Load Simulator с прохождением SLO на 10к CCU.

## Соглашения

- Диаграммы — ASCII в code-fences. Mermaid пробовали, сняли: текст читается везде без расширений.
- Контракты API — блоками кода с комментариями.
- Числовые оценки нагрузки расчётные, подтверждаются прогоном Load Simulator.

## Status

Версия 0.2 от 2026-05-09 (после архитектурного аудита). Источник требований — [REQUIREMENTS.md](../../REQUIREMENTS.md).

## Глоссарий

| Термин | Расшифровка |
|---|---|
| **CCU** | Concurrent Users — одновременно подключённые клиенты |
| **DAU** | Daily Active Users — уникальные клиенты за сутки |
| **TPS** | Transactions Per Second |
| **RPS** | Requests Per Second |
| **WS** | WebSocket |
| **MVP** | Minimum Viable Product |
| **OTel** | OpenTelemetry |
| **BFF** | Backend For Frontend |
| **SLO** | Service Level Objective |
| **SLI** | Service Level Indicator |
| **ADR** | Architecture Decision Record |
