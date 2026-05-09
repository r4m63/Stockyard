# Stockyard — Trading & Investment Platform

Учебный проект курса «Разработка мобильных приложений», ИТМО, весна 2026.
MVP экосистемы для трейдинга на 10 000 одновременных клиентов. Команда до 10 человек, один семестр.

---

## Source of Truth (читать перед любым изменением)

| Документ | Что внутри |
|---|---|
| [REQUIREMENTS.md](REQUIREMENTS.md) | Требования ТЗ. Стек обязателен — отклонение штрафуется. |
| [docs/architecture/README.md](docs/architecture/README.md) | Полная архитектура (11 разделов + ADR) |
| [docs/architecture/adr/](docs/architecture/adr/) | Архитектурные решения (ADR-001 … ADR-006) |
| [docs/architecture/11-testing.md](docs/architecture/11-testing.md) | Стратегия тестирования |
| [VERSION](VERSION) | Текущая версия проекта (SemVer 2.0). Изменяется только через `/committer release ...`. |
| [CHANGELOG.md](CHANGELOG.md) | Журнал изменений (Keep a Changelog 1.1.0). Поддерживается `/committer`. |

**Перед серьёзным изменением кода/архитектуры — обязательно прочитать релевантный документ.**

---

## Состав системы

- **4 микросервиса:** API Gateway (Ktor), DB Service (Kotlin), Quotes Service (Go), Load Simulator
- **2 мобильных клиента:** Android (Kotlin + Jetpack Compose) и React Native (TypeScript)
- **1 драйвер Linux (C):** имитация биржи через `/dev/stockyard`
- **Хранилища:** PostgreSQL (OLTP), Redis/KeyDB (cache + pub/sub), ClickHouse (time-series)
- **Observability:** OpenTelemetry → Prometheus + Jaeger + Grafana

---

## Стек технологий (обязательный)

> ⚠️ Отклонение от этого списка снижает балл. Это требование ТЗ §3.

| Слой | Технологии |
|---|---|
| **Android** | Kotlin, Jetpack Compose, Hilt, OkHttp, Retrofit, kotlinx.serialization |
| **Cross-platform** | React Native, TypeScript, Redux Toolkit, axios |
| **Backend (Gateway, DB Service)** | Kotlin, Ktor, корутины, kotlinx.serialization |
| **Backend (Quotes)** | Go (стандартная библиотека + go-redis + clickhouse-go) |
| **Driver** | C (Linux kernel module / character device) |
| **БД OLTP** | PostgreSQL — **только голый SQL, без ORM!** |
| **Кэш/брокер** | Redis / KeyDB |
| **Time-series** | ClickHouse |
| **Observability** | OpenTelemetry SDK во всех сервисах |
| **Build** | Gradle (Kotlin), Go modules, Make (C), Yarn/npm (RN) |

---

## Конвенции

### Деньги
- Всегда `BIGINT cents`, никогда `NUMERIC`/`DECIMAL`/`Float`.
- Все мутации балансов и позиций — в **одной транзакции PostgreSQL**.
- Цена для исполнения берётся из Redis **до** `BEGIN`.

### Идентификаторы
- Текстовые ULID с префиксом: `u_xxx` (user), `o_xxx` (order).
- В БД: тип `TEXT`, не UUID.

### API
- Префикс `/v1/`.
- Тело запроса/ответа: JSON.
- В БД snake_case, в JSON camelCase.
- Ошибки: `{ "error": { "code", "message", "details" } }`.
- Идемпотентность через `Idempotency-Key` для всех мутирующих POST.
- JWT в заголовке `Authorization: Bearer ...`.

### Безопасность
- Argon2id для паролей.
- Refresh-tokens в Redis с TTL 30 дней.
- Никогда не логировать пароли, JWT целиком, PII.

### Тесты
- 3 уровня: **unit** / **integration (Testcontainers)** / **system (Load Simulator)**.
- Подробности в [11-testing.md](docs/architecture/11-testing.md).

### Документирование
- Документация в исходниках (Dokka для Kotlin, godoc для Go) + ключевые ADR в `docs/architecture/adr/`.
- Не плодить отдельные `.md` без необходимости.

---

## Task Flow (как ведём разработку)

Проект разрабатывается через **систему ролевых команд** с общим **task ledger**. Каждая команда вызывает определённую роль, которая выполняет свою часть и сохраняет результат в файле задачи. Задача передаётся от роли к роли с полным контекстом.

| Команда | Роль | Когда вызывать |
|---|---|---|
| `/architect <описание>` | Архитектор | начало любой нетривиальной фичи: дизайн, ADR, план реализации |
| `/backend TASK-NNN` | Backend-разработчик | реализация Kotlin/Go/C на бэкенде |
| `/frontend TASK-NNN` | RN-разработчик | реализация React Native клиента |
| `/mobile TASK-NNN` | Android-разработчик | реализация Android/Compose клиента |
| `/tester TASK-NNN` | Тестировщик | unit / integration / system тесты |
| `/reviewer TASK-NNN` | Ревьюер | code review + security check перед merge |
| `/committer TASK-NNN` или `/committer push` | Git-специалист / коммитер | создание ветки, атомарные коммиты по Angular convention, push |
| `/task list` или `/task show TASK-NNN` | — | просмотр задач |

Каждая задача живёт в `.claude/tasks/TASK-NNN-<slug>.md` — это **task ledger**, куда каждая роль дописывает свои решения. Подробности: [.claude/tasks/README.md](.claude/tasks/README.md).

### Типичный flow

```
┌─ /architect "user registration"  →  TASK-001 создан, дизайн готов
│
├─ /backend TASK-001              →  Kotlin DB Service implementation
│
├─ /mobile TASK-001               →  Android UI implementation
│
├─ /tester TASK-001               →  unit + integration тесты
│
├─ /reviewer TASK-001             →  финальный review, gate PASS
│
└─ /committer TASK-001            →  ветка feature/TASK-001-...,
                                     атомарные коммиты по Angular convention,
                                     push (по запросу)
```

Можно идти не по порядку и в любом наборе ролей. Главное — каждая роль читает task ledger и дописывает свой блок. **`/committer` всегда последний** — после reviewer PASS.

---

## Workflow Rules

1. **Стек неприкасаем.** Не предлагать ORM, не предлагать Kafka вместо Redis Pub/Sub, не предлагать TimescaleDB и т.п. Это ТЗ.
2. **Только голый SQL.** Никаких Hibernate/Exposed/jOOQ — `JDBC + PreparedStatement`.
3. **Сначала архитектор.** Любая фича > 10 строк начинается с `/architect`. Без дизайна — не кодим.
4. **Один task — одна роль за раз.** Не запускай две роли параллельно на одну задачу.
5. **Читай task ledger.** Прежде чем работать над TASK-NNN, прочитай весь файл — там контекст от предыдущих ролей.
6. **Никаких новых компонентов** без согласования с архитектором (= новый ADR).
7. **Никогда не коммить вручную и не звать git напрямую.** Все git-операции — через `/committer`. Только `/committer` создаёт ветки, коммиты и делает push.
8. **Тесты идут последними** в task flow, но проектируются (стратегия) в фазе архитектора.
9. **Документация рядом с кодом.** Изменил API — обнови комментарии для Dokka и проверь, что архитектурный документ всё ещё валиден.
10. **MVP scope > совершенство.** Если что-то можно отложить (📦 Backlog в архитектуре) — откладывай.

---

## Quick Reference

- `docs/architecture/README.md` — навигация по архитектуре
- `docs/architecture/02-containers.md` — какой сервис где живёт
- `docs/architecture/05-communication.md` — API контракты (REST + WS)
- `docs/architecture/06-data.md` — DDL и ключи Redis/CH
- `docs/architecture/07-consistency.md` — транзакции BUY/SELL
- `docs/architecture/10-scenarios.md` — sequence-диаграммы
- `docs/architecture/adr/` — почему выбрано так

---

## Контактная информация курса

- Преподаватель: Ключев А.О. (kluchev@yandex.ru)
- Семестр: весна 2026
- Группа в Telegram: «РМП 2026 весна»
