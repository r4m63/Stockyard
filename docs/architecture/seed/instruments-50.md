# Seed: 50 инструментов

## Назначение

Список 50 инструментов для сидинга `instruments` в PostgreSQL и инициализации стартовых котировок в Redis. Используется в [V2 миграции Flyway](../12-storage-operations.md#1214-migrations--seeding-flyway) и в `quotes-service/config/seed-prices.yaml`.

Состав — российский «голубой чип»-набор + ликвид второго эшелона MOEX. Учебный MVP, поэтому `lot_size` округлены до близких к реальным; для трейдинг-логики важна **только математика лотов**, не точное соответствие реальной бирже.

## V2 миграция (DML)

```sql
INSERT INTO instruments (ticker, name, type, lot_size) VALUES
-- Top-30 по ликвидности (Index MOEX)
('SBER',  'Сбербанк ао',        'STOCK', 10),
('GAZP',  'Газпром ао',         'STOCK', 10),
('LKOH',  'Лукойл',             'STOCK', 1),
('GMKN',  'ГМК Норникель',      'STOCK', 1),
('ROSN',  'Роснефть',           'STOCK', 1),
('NVTK',  'Новатэк',            'STOCK', 1),
('MGNT',  'Магнит',             'STOCK', 1),
('MTSS',  'МТС',                'STOCK', 10),
('YNDX',  'Яндекс',             'STOCK', 1),
('OZON',  'Озон',               'STOCK', 1),
('VKCO',  'VK',                 'STOCK', 1),
('PLZL',  'Полюс',              'STOCK', 1),
('AFKS',  'АФК Система',        'STOCK', 100),
('AFLT',  'Аэрофлот',           'STOCK', 10),
('ALRS',  'Алроса',             'STOCK', 10),
('CHMF',  'Северсталь',         'STOCK', 1),
('FEES',  'ФСК-Россети',        'STOCK', 100000),
('HYDR',  'Русгидро',           'STOCK', 1000),
('IRAO',  'ИнтерРАО',           'STOCK', 100),
('MOEX',  'Московская биржа',   'STOCK', 10),
('NLMK',  'НЛМК',               'STOCK', 10),
('PHOR',  'Фосагро',            'STOCK', 1),
('POLY',  'Полиметалл',         'STOCK', 1),
('RTKM',  'Ростелеком ао',      'STOCK', 10),
('RUAL',  'РУСАЛ',              'STOCK', 10),
('SNGS',  'Сургутнефтегаз ао',  'STOCK', 100),
('TATN',  'Татнефть ао',        'STOCK', 1),
('TCSG',  'TCS Group',          'STOCK', 1),
('TRMK',  'ТМК',                'STOCK', 10),
('VTBR',  'ВТБ',                'STOCK', 10000),
-- Ликвид 2 эшелона
('PIKK',  'ПИК',                'STOCK', 1),
('SBERP', 'Сбербанк ап',        'STOCK', 10),
('SNGSP', 'Сургутнефтегаз ап',  'STOCK', 100),
('TATNP', 'Татнефть ап',        'STOCK', 1),
('RASP',  'Распадская',         'STOCK', 10),
('UPRO',  'Юнипро',             'STOCK', 1000),
('LSRG',  'ЛСР',                'STOCK', 1),
('MAGN',  'ММК',                'STOCK', 10),
('SMLT',  'Самолёт',            'STOCK', 1),
('SGZH',  'Сегежа',             'STOCK', 10),
('FIVE',  'X5 Retail Group',    'STOCK', 1),
('AGRO',  'Русагро',            'STOCK', 1),
('FLOT',  'Совкомфлот',         'STOCK', 1),
('GLTR',  'Globaltrans',        'STOCK', 1),
('CBOM',  'МКБ',                'STOCK', 100),
('ENPG',  'En+',                'STOCK', 1),
('MTLR',  'Мечел ао',           'STOCK', 10),
('MTLRP', 'Мечел ап',           'STOCK', 10),
('NMTP',  'Новороссийский МТП', 'STOCK', 1000),
('BSPB',  'Банк СПб',           'STOCK', 10);
```

## Стартовые цены (Redis init)

Цены **не хранятся в `instruments`** — `instruments` это только справочник. Стартовые `last/bid/ask` инициализируются Quotes Service при старте через `HSET quotes:<ticker>`. Конфиг отдельный (`quotes-service/config/seed-prices.yaml`), не в БД, не в этом файле — здесь только справочник тикеров.

Драйвер `/dev/stockyard` далее двигает цену случайным walk'ом вокруг стартового значения с амплитудой ±2% и тиком 50–500 мс.

## Связанные документы

- [12. Эксплуатация уровня хранения](../12-storage-operations.md) — где это используется в Flyway V2.
- [06. Архитектура данных §6.2.2](../06-data.md#622-ddl) — DDL `instruments`.
