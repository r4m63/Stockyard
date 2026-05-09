# ADR-002: ClickHouse для истории тиков

## Status
**Accepted** (2026-05-09)

## Context

Нужно хранить **историю котировок** для построения свечных графиков (1m / 5m / 1h) в мобильном клиенте.

Требования:
- Высокий write rate: 50 тиков/сек = ~4.3 млн строк/сутки.
- Range-сканы по `(ticker, time_range)` для построения свечей.
- Эффективное сжатие (тики хорошо сжимаются — много повторов).
- Retention с TTL.
- Готовые агрегаты для UI без recompute.

Из ТЗ §4: для хранения больших объёмов и быстрой выдачи — **ClickHouse** (`https://clickhouse.com/docs/ru`). Это формальное ограничение.

## Decision

Используем **ClickHouse** для:
- Сырой таблицы тиков `quotes_ticks` с `ENGINE = MergeTree`, partition by month, order by `(ticker, ts)`, TTL 6 месяцев.
- Materialized view `quotes_candles_1m` (`AggregatingMergeTree`) с `argMinState/argMaxState` для open/close и `min/max/sum` для high/low/volume.

Запросы графиков идут не в сырые тики, а в готовые свечи.

## Consequences

**Положительные:**
- Колоночное хранение + сжатие (LZ4 по умолчанию) — ~3–5× меньше места, чем PostgreSQL.
- Range-сканы по партиции и order-key — миллисекунды на миллионах строк.
- `LowCardinality(String)` для тикеров (их 50, не миллион) — почти бесплатно.
- Materialized view даёт готовые свечи без оркестрации batch jobs.
- Партиционирование по месяцам — удобный `DROP PARTITION` для retention.

**Отрицательные:**
- Ещё один компонент инфраструктуры (CPU, RAM, диск).
- ClickHouse слабее PostgreSQL в OLTP (но мы и не используем его для OLTP).
- Eventual consistency для свечей: materialized view обновляется не мгновенно, последняя минута может «дрожать».

**Нейтральные:**
- Размер хранения: ~15 GB на год при TTL 6 месяцев — помещается на любую VM.

## Alternatives considered

- **TimescaleDB** (расширение PostgreSQL для time-series):
  - Не входит в стек ТЗ.
  - Хуже сжимает; чанк-партиции менее гибкие.
- **PostgreSQL без расширений** (с PARTITION BY RANGE):
  - Нагружает primary OLTP-инстанс.
  - Range-скан 4 млн строк за сутки — секунды на каждом запросе графика.
- **InfluxDB**: не в стеке ТЗ.
- **Redis Streams для истории**: нет SQL, нет range-сканов, не подходит.
- **Хранить только агрегированные свечи без сырых тиков**:
  - Невозможно пересчитать свечи на новых интервалах задним числом.
  - Сырые тики нужны для отладки и аудита.
