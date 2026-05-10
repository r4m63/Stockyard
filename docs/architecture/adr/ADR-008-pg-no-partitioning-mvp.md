# ADR-008: PostgreSQL — без партиционирования в MVP

## Status

Proposed (2026-05-09)

## Context

В [06-data §6.5 «Жизненный цикл данных»](../06-data.md#65-жизненный-цикл-данных-data-lifecycle) у строк `orders` и `transactions` в колонке «Архивация» написано «партиции по месяцам». При этом DDL в [§6.2.2](../06-data.md#622-ddl) — обычные heap-таблицы без партиционирования. Возникает противоречие, требующее однозначного решения.

Объёмы (см. [06-data §6.6](../06-data.md#66-объёмы-хранилищ)) для целевой нагрузки:

| Таблица | Записей за год | Размер за год |
|---|---|---|
| `orders` | ~3.6 М (10к юзеров × 1 ордер/день) | ~1 GB |
| `transactions` | ~3.6 М | ~700 MB |

При `shared_buffers = 1 GB` (см. [12-storage-operations §12.1.1](../12-storage-operations.md#1211-postgresqlconf)) обе таблицы вместе с индексами помещаются в кэш. B-tree-индексы по `(user_id, created_at DESC)` на этих объёмах отвечают за единицы миллисекунд.

Партиционирование PostgreSQL даёт выигрыш на:

- сканах огромных диапазонов (десятки миллионов строк),
- быстром отрезании старых данных через `DETACH PARTITION` + `DROP`,
- bulk-load в одну партицию без блокировки остальных.

Стоимость партиционирования:

- ежемесячное создание новой partition (вручную либо через `pg_partman`),
- управление default-partition'ом,
- ограничения PostgreSQL: нет глобальных UNIQUE-индексов, FK на партиционированную таблицу до недавнего времени имели нюансы,
- особенности `pg_dump`/`pg_restore`,
- partition pruning bugs в плане запросов при сложных WHERE.

## Decision

В MVP **не используем партиционирование** для `orders` и `transactions`. Применяем обычные heap-таблицы с DDL из [06-data §6.2.2](../06-data.md#622-ddl) и индексами из [12-storage-operations §12.1.4](../12-storage-operations.md#1214-migrations--seeding-flyway) (`idx_orders_user_ticker` в V6 + унаследованные).

Текст «партиции по месяцам» в `06-data §6.5` — преждевременная оптимизация, корректируется заметкой со ссылкой на этот ADR.

## Consequences

**Положительные:**

- Меньше операционной сложности: нет partition manager, нет default partition, нет ручного `CREATE PARTITION` каждый месяц.
- Глобальные UNIQUE-индексы работают штатно — это критично для `UNIQUE (user_id, idempotency_key)` (см. [ADR-005](ADR-005-idempotency-key.md), [ADR-007](ADR-007-idempotency-key-retention.md)).
- Простые миграции — `ALTER TABLE … ADD COLUMN` без необходимости касаться каждой партиции.
- `pg_dump` / `pg_restore` без partition-aware режима.
- FK от `transactions.ref_order_id → orders.id` без подводных камней.

**Отрицательные:**

- При росте до 50 М+ строк (при 10× scale-up — это 1.5 года жизни системы; на текущей нагрузке — 14 лет) индексы и vacuum начнут болеть. Точка эволюции, не блокер MVP.
- Невозможно «отрезать» старые ордера через `DETACH PARTITION` — но в финансовой системе ордера являются audit-данными и не архивируются никогда, это и не требуется.

**Нейтральные:**

- ClickHouse-партиционирование `quotes_ticks` (`PARTITION BY toYYYYMM(ts)` в [06-data §6.4.1](../06-data.md#641-сырые-тики)) остаётся — оно нативное и обязательное для MergeTree-TTL. Это другая история, не связанная с PostgreSQL.

## Alternatives considered

- **Range partitioning по `created_at` (monthly)** сейчас. Помесячное создание партиций (вручную или `pg_partman`). Выигрыш на 3.6 М/год — единицы процентов по latency, не оправдывает сложности.
- **Hash partitioning по `user_id`.** Помогает при шардировании по юзерам, чего в MVP не делаем (один PG-инстанс).
- **Declarative partitioning + `pg_partman`** для автоматизации создания партиций. Снижает ручную работу, но добавляет внешнюю зависимость и не отменяет остальных недостатков.

## Точка эволюции

Триггеры для перехода на partitioning:

- объём `orders` превысил 20 М строк, **или**
- runtime `VACUUM` / `ANALYZE` на `orders` превысил 30 минут, **или**
- p95 запроса «история ордеров пользователя за месяц» превысил 200 мс на тёплом кэше.

Будущая миграция обратима: создать партиционированную таблицу `orders_v2`, скопировать данные через `INSERT ... SELECT`, переключить FK и переименовать. Применяется через стандартную Flyway-миграцию `V<N>__partition_orders.sql`.

## References

- [06-data §6.2.2](../06-data.md#622-ddl) — DDL `orders` / `transactions`.
- [06-data §6.5](../06-data.md#65-жизненный-цикл-данных-data-lifecycle) — lifecycle (с заметкой об отсутствии партиционирования).
- [06-data §6.6](../06-data.md#66-объёмы-хранилищ) — расчётные объёмы.
- [12-storage-operations §12.1.1](../12-storage-operations.md#1211-postgresqlconf) — ресурсы PG в demo.
- [ADR-005](ADR-005-idempotency-key.md), [ADR-007](ADR-007-idempotency-key-retention.md) — UNIQUE-индекс по idempotency-key, требующий глобальной уникальности.
