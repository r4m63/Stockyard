# TASK-002: Rename DB Service → Core Service

## Meta
- ID: TASK-002
- Created: 2026-05-10T23:30:00Z
- Last updated: 2026-05-10T23:30:00Z
- Stage: architect-done
- Touched roles: architect

## Original Request
переимнуй DB Service (Kotlin) в Core Service везде!

## Architect Design

### 1. Контекст и обоснование

Внутреннее имя сервиса меняется с **DB Service** на **Core Service**. Имя «DB Service» сужает роль до «БД-обёртки», тогда как фактически этот сервис — единственный транзакционный writer (ADR-004), хост бизнес-логики ордеров/позиций/балансов и владелец интеграции с Redis (за ценой) и ClickHouse (за свечами). «Core» точнее отражает зону ответственности.

**Соответствие ТЗ.** REQUIREMENTS.md §2.4 называет компонент «Микросервис для работы с БД». Это **функциональное описание роли**, не имя. В отчёте пишем: «**Core Service** реализует роль "Микросервис для работы с БД" (ТЗ §2.4) — ACID-транзакции, бизнес-логика ордеров, чтение Redis/ClickHouse». Это безопасно для оценки, ТЗ требует наличия такого сервиса, а не конкретного имени.

### 2. Affected components

| Компонент | Затрагивается | Что меняется |
|---|---|---|
| **Core Service (Kotlin/Ktor)** — бывший DB Service | да | Имя сервиса, директория, container_name, OTel `service.name`, package-naming в будущем коде |
| **API Gateway** | косвенно | Конфиг URL'а внутреннего сервиса (`http://core-service:...` вместо `http://db-service:...`) |
| **Quotes Service (Go)** | нет | Не общается с этим сервисом напрямую |
| **C Driver, Android, RN, Load Simulator** | нет | — |
| Вся документация и ADR | да | Все упоминания «DB Service» / `db-service` / Mermaid id `DBSvc` |
| Task ledger TASK-001 | да | Переименование вместе с архитектурными доками |
| `.claude/commands/*.md` | да | Описания ролей backend/committer упоминают сервис |
| docker-compose, конфиги, .env.example | да | container_name, paths, OTEL_SERVICE_NAME |

### 3. Замены

Сквозные перестановки (mass-replace), три pattern'а:

| Pattern | Замена | Где |
|---|---|---|
| `DB Service` | `Core Service` | человеко-читаемое имя в .md/.kt-комментариях |
| `db-service` | `core-service` | container_name, директория, OTel service.name, env, volumes mount paths |
| `DBSvc` | `CoreSvc` | Mermaid-id в C4-диаграммах |

Mermaid-id формально внутренние (рендеринг не меняется), но для консистентности с именем сервиса заменяем.

### 4. API contract changes

Никаких. Внешние API клиентов (REST + WS) идут только через API Gateway — внутреннее имя downstream-сервиса невидимо мобильным/RN-клиентам.

**Внутренний API** между Gateway и Core Service: эндпоинты `/internal/...` сохраняются. Меняется **base URL**: `http://db-service:8080` → `http://core-service:8080`. Это конфиг-уровневое изменение, не контракт.

### 5. Data model changes

Никаких. PostgreSQL DDL, Redis ключи, ClickHouse schema — без изменений. Меняется только пользователь подключения (если в коде где-то будет хардкод — таких мест нет, всё через env).

### 6. Implementation plan

| # | Шаг | Роль | Артефакты |
|---|---|---|---|
| 1 | Сквозной rename текстовых упоминаний (`DB Service` / `db-service` / `DBSvc`) во всех `.md` / `.yml` / `.yaml` / `.sql` / `.sh` / `.xml` / `.conf` / `.env.example`. Безопасно через `perl -pi -e` — три уникальных pattern'а, не пересекающихся с другими словами. | architect (этот шаг) | 27 файлов: 12 архитектурных доков, 6 ADR, 9 файлов в `.claude/`, `CLAUDE.md`, `HOWTO.md`, `docker-compose.yml`, `.env.example`, `deploy/postgres/postgresql.conf`, `core-service/src/main/resources/db/seed/dev_users.sql`, и др. |
| 2 | Переименование директории `db-service/` → `core-service/` (filesystem mv). | architect (этот шаг) | Все Flyway-миграции и dev seed мигрируют целиком. |
| 3 | Smoke verification: `grep -rn "DB Service\|db-service\|DBSvc"` → пусто; `docker compose config` → валиден. | architect (этот шаг) | — |
| 4 | (Будущее) Когда появятся реальные Kotlin/Go проекты — поправить package `com.stockyard.db.*` → `com.stockyard.core.*`, Gradle module name. | backend | новые модули после scaffold |

Все три шага architect-роли — выполнены в этом запуске.

### 7. ADR

**ADR не пишем.** Это переименование внутреннего идентификатора, а не архитектурное решение. ADR-004 (single-tx-writer) ссылается на DB Service по роли — после rename ссылается на Core Service по той же роли, **смысл сохранён**. Аналогично ADR-005, ADR-006.

Если бы мы меняли границы сервиса (например, сливали Gateway+DB Service в один Monolith Core) — нужен был бы ADR. Здесь — pure rename.

### 8. Risks

| Риск | Вероятность | Импакт | Митигация |
|---|---|---|---|
| Раскол с формулировкой ТЗ §2.4 «Микросервис для работы с БД» | средняя | low | в отчёте по ГОСТ §3 указываем явное соответствие: «Core Service реализует роль из ТЗ §2.4». Имя — внутреннее. |
| Где-то остался хардкод `db-service` (в Dockerfile/CI, которые ещё не существуют) | низкая | low | grep после rename — пусто. Скаффолд проектов в будущей задаче будет с правильным именем сразу. |
| Mermaid-диаграммы перестали рендериться | низкая | medium | id `DBSvc` → `CoreSvc` тоже заменены, синтаксис Mermaid не пострадал. Проверка визуально — не блокирует merge, GitHub-render отрабатывает. |
| Существующая ветка/PR с упоминанием `db-service` | низкая | low | в репо нет открытых веток кроме `main`; коммитов с `db-service` тоже нет (storage-layer не закоммичен). |

### 9. Estimated complexity: **SMALL**

3 файла кода (mass replace + директория mv + ledger), 0 архитектурных решений, 0 новых ADR, 0 новых тестов. Время выполнения — минуты.

### 10. Suggested next role

`/committer TASK-002` — оформить rename как отдельный коммит с типом `refactor` и scope нет (затрагивает несколько модулей одновременно):

```
refactor: rename DB Service to Core Service
```

Альтернативно, если rename ещё не выпускался во внешний мир — слить с TASK-001 storage commits в один pre-release commit.

## Files Affected

Сквозной rename затронул 27 файлов (см. список ниже после grep). Директория `db-service/` переименована в `core-service/`.

Документация:
- `CLAUDE.md`, `HOWTO.md`
- `docs/architecture/02-containers.md` … `12-storage-operations.md`
- `docs/architecture/adr/ADR-003`, `ADR-004`, `ADR-005`, `ADR-006`
- `docs/architecture/adr/README.md`

Конфигурация / инфра:
- `docker-compose.yml`, `.env.example`
- `deploy/postgres/postgresql.conf`
- `core-service/src/main/resources/db/seed/dev_users.sql`

Task ledger:
- `.claude/tasks/README.md`, `.claude/tasks/TASK-000-example-template.md`, `.claude/tasks/TASK-001-storage-layer-design.md`

Команды:
- `.claude/commands/backend.md`, `.claude/commands/committer.md`

## Handoff Log
- 2026-05-10T23:30:00Z: создан через /architect — выполнен сквозной rename DB Service → Core Service: 3 паттерна (DB Service, db-service, DBSvc) через `perl -pi -e` по всем .md/.yml/.yaml/.sql/.sh/.xml/.conf/.env*; директория db-service/ → core-service/ через mv. Smoke: grep на старые pattern → пусто; `docker compose config` → валиден. ADR не требуется (pure rename внутреннего идентификатора). Suggested next: /committer TASK-002.
