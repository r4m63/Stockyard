# Architecture Decision Records (ADR)

Каждый ADR фиксирует **одно архитектурное решение** в формате: контекст → решение → последствия → рассмотренные альтернативы. ADR пишется на момент принятия решения и **не переписывается задним числом** — если решение поменялось, заводится новый ADR с пометкой о суперсиде.

## Список решений

| # | Статус | Тема |
|---|---|---|
| [ADR-001](ADR-001-redis-pubsub.md) | Accepted | Redis Pub/Sub как брокер котировок (вместо Kafka) |
| [ADR-002](ADR-002-clickhouse-timeseries.md) | Accepted | ClickHouse для истории тиков (вместо TimescaleDB / PG) |
| [ADR-003](ADR-003-rest-ws-not-grpc.md) | Accepted | REST + WebSocket между мобилкой и Gateway (вместо gRPC) |
| [ADR-004](ADR-004-single-tx-writer.md) | Accepted | Единственный транзакционный writer — Core Service (без распределённых транзакций) |
| [ADR-005](ADR-005-idempotency-key.md) | Accepted | Идемпотентность через UNIQUE-индекс по `Idempotency-Key` |
| [ADR-006](ADR-006-argon2.md) | Accepted | Argon2id для хэширования паролей |
| [ADR-007](ADR-007-idempotency-key-retention.md) | Proposed | Idempotency-keys хранятся бессрочно вместе с ордером |
| [ADR-008](ADR-008-pg-no-partitioning-mvp.md) | Proposed | PostgreSQL — без партиционирования `orders`/`transactions` в MVP |
| [ADR-009](ADR-009-gradle-single-module.md) | Proposed | Gradle: single-module per backend service, без composite build |

## Шаблон ADR

```markdown
# ADR-XXX: <короткое название>

## Status
Accepted | Superseded by ADR-YYY | Deprecated

## Context
Какую проблему решаем, какие ограничения.

## Decision
Что мы решили делать.

## Consequences
**Положительные:** ...
**Отрицательные:** ...
**Нейтральные:** ...

## Alternatives considered
- Альтернатива A — почему не выбрали
- Альтернатива B — почему не выбрали
```
