-- ClickHouse schema for Stockyard.
-- Запускается через /docker-entrypoint-initdb.d/init.sql при первом старте контейнера.
-- См. docs/architecture/06-data.md §6.4.

CREATE DATABASE IF NOT EXISTS stockyard;

-- ---------------------------------------------------------------------------
-- 1. Сырые тики
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS stockyard.quotes_ticks
(
    ticker  LowCardinality(String),
    ts      DateTime64(3, 'UTC'),
    bid     Decimal(18, 4),
    ask     Decimal(18, 4),
    last    Decimal(18, 4),
    volume  UInt64
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(ts)
ORDER BY (ticker, ts)
TTL toStartOfMonth(ts) + INTERVAL 6 MONTH;

-- ---------------------------------------------------------------------------
-- 2. Минутные свечи (агрегация через Materialized View)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS stockyard.quotes_candles_1m
(
    ticker      LowCardinality(String),
    ts_minute   DateTime,
    open        AggregateFunction(argMin, Decimal(18, 4), DateTime64(3)),
    close       AggregateFunction(argMax, Decimal(18, 4), DateTime64(3)),
    high        AggregateFunction(max,    Decimal(18, 4)),
    low         AggregateFunction(min,    Decimal(18, 4)),
    volume      AggregateFunction(sum,    UInt64)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(ts_minute)
ORDER BY (ticker, ts_minute);

CREATE MATERIALIZED VIEW IF NOT EXISTS stockyard.quotes_candles_1m_mv
TO stockyard.quotes_candles_1m AS
SELECT
    ticker,
    toStartOfMinute(ts) AS ts_minute,
    argMinState(last, ts)  AS open,
    argMaxState(last, ts)  AS close,
    maxState(last)         AS high,
    minState(last)         AS low,
    sumState(volume)       AS volume
FROM stockyard.quotes_ticks
GROUP BY ticker, ts_minute;

-- ---------------------------------------------------------------------------
-- 3. Часовые свечи (агрегация поверх минутных через тот же паттерн)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS stockyard.quotes_candles_1h
(
    ticker      LowCardinality(String),
    ts_hour     DateTime,
    open        AggregateFunction(argMin, Decimal(18, 4), DateTime64(3)),
    close       AggregateFunction(argMax, Decimal(18, 4), DateTime64(3)),
    high        AggregateFunction(max,    Decimal(18, 4)),
    low         AggregateFunction(min,    Decimal(18, 4)),
    volume      AggregateFunction(sum,    UInt64)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(ts_hour)
ORDER BY (ticker, ts_hour);

CREATE MATERIALIZED VIEW IF NOT EXISTS stockyard.quotes_candles_1h_mv
TO stockyard.quotes_candles_1h AS
SELECT
    ticker,
    toStartOfHour(ts) AS ts_hour,
    argMinState(last, ts)  AS open,
    argMaxState(last, ts)  AS close,
    maxState(last)         AS high,
    minState(last)         AS low,
    sumState(volume)       AS volume
FROM stockyard.quotes_ticks
GROUP BY ticker, ts_hour;
