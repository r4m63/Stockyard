# HOWTO: разработка Stockyard через Claude

Полный гайд по тому, как устроен workflow проекта и как им пользоваться.
Если читаешь впервые — начни с **TL;DR**.

---

## TL;DR (за 60 секунд)

Каждая фича в Stockyard разрабатывается как **задача (TASK)**, которая последовательно проходит через **роли**:

```
/architect → /backend | /mobile | /frontend → /tester → /reviewer → /committer
```

Все решения и контекст сохраняются в файле `.claude/tasks/TASK-NNN-<slug>.md`.
Каждая роль читает этот файл и дописывает свой блок. Передача задачи между ролями = передача файла.

**Команды:**

| Команда | Когда |
|---|---|
| `/architect <описание>` | начало любой нетривиальной фичи |
| `/backend TASK-NNN` | реализация Kotlin/Go/C на бэкенде |
| `/mobile TASK-NNN` | реализация Android/Compose клиента |
| `/frontend TASK-NNN` | реализация React Native клиента |
| `/tester TASK-NNN` | юнит + интеграционные + системные тесты |
| `/reviewer TASK-NNN` | финальный gate перед merge |
| `/committer TASK-NNN` | ветка + атомарные коммиты (Angular convention) + push |
| `/task list` / `/task show TASK-NNN` / `/task next` | просмотр задач |

---

## 1. Что у тебя уже есть

```
Stockyard/
├── CLAUDE.md                              ← главный контекст (Claude читает автоматически)
├── HOWTO.md                               ← этот файл
├── REQUIREMENTS.md                        ← требования ТЗ
├── README.md                              ← описание репозитория
│
├── docs/architecture/                     ← полная архитектура (12 документов)
│   ├── README.md                          ← навигация
│   ├── 01..11-*.md                        ← разделы
│   └── adr/                               ← 6 архитектурных решений
│
└── .claude/                               ← конфиг Claude
    ├── commands/                          ← 7 слэш-команд
    │   ├── architect.md
    │   ├── backend.md
    │   ├── frontend.md
    │   ├── mobile.md
    │   ├── tester.md
    │   ├── reviewer.md
    │   └── task.md
    └── tasks/                             ← task ledger
        ├── README.md                      ← формат задач
        └── TASK-NNN-<slug>.md             ← одна задача = один файл
```

**Куда смотреть в каких ситуациях:**

| Хочу… | Открой |
|---|---|
| понять, что система должна делать | [REQUIREMENTS.md](REQUIREMENTS.md) |
| понять, как устроена архитектура | [docs/architecture/README.md](docs/architecture/README.md) |
| увидеть, что Claude знает о проекте | [CLAUDE.md](CLAUDE.md) |
| понять формат задач | [.claude/tasks/README.md](.claude/tasks/README.md) |
| увидеть пример заполненной задачи | [.claude/tasks/TASK-000-example-template.md](.claude/tasks/TASK-000-example-template.md) |
| узнать текущую версию | [VERSION](VERSION) |
| что менялось в проекте | [CHANGELOG.md](CHANGELOG.md) |
| вспомнить, какие команды есть | этот файл (HOWTO.md), раздел 2 |

---

## 2. Восемь ролей: краткий справочник

### `/architect <описание задачи>`

**Когда вызывать:**
- Любая новая фича больше тривиальной правки.
- Не уверен, что и как делать.
- Архитектура требует обновления (новый микросервис, новая таблица, новый эндпоинт).

**Что делает:**
- Создаёт `TASK-NNN-<slug>.md` (или продолжает существующий).
- Запускает архитектора-субагента.
- Архитектор читает требования и архитектуру, проектирует решение.
- В файл попадают: список затронутых компонентов, изменения API, изменения данных, шаги реализации **с указанием роли для каждого**, ADR (если нужно), риски, оценка сложности.

**Что НЕ делает:**
- Не пишет код.
- Не предлагает выйти за стек.

**Пример:**
```
/architect добавить эндпоинт для получения истории ордеров пользователя с пагинацией
```

---

### `/backend TASK-NNN`

**Когда вызывать:**
- В архитекте отмечены шаги для backend-роли.
- Меняется Kotlin (Gateway / Core Service), Go (Quotes Service), C (driver).
- Меняется SQL-схема (миграции).

**Что делает:**
- Читает task ledger целиком.
- Имплементирует строго по плану архитектора.
- Пишет код в правильные модули, добавляет миграции, OTel, логи.
- Обновляет `## Backend Implementation` в task ledger.

**Hard rules:**
- Никаких ORM (только JDBC + raw SQL).
- Деньги — `BIGINT cents`.
- Все мутации балансов — в одной TX.
- Идемпотентность через `UNIQUE(user_id, idempotency_key)`.

---

### `/mobile TASK-NNN`

**Android-разработчик** (Kotlin + Jetpack Compose + Hilt + StateFlow).

**Что делает:**
- Реализует UI и ViewModel-логику в `android-app/`.
- Пишет Retrofit-интерфейсы, repositories, screens.
- Не запускает эмулятор и Espresso (это `/tester`).

**Hard rules:**
- Только Compose, без XML-layouts.
- StateFlow, не LiveData.
- Корутины, не RxJava.

---

### `/frontend TASK-NNN`

**React Native разработчик** (TypeScript + Redux Toolkit + axios).

**Что делает:**
- Реализует UI и state в `rn-app/`.
- Пишет Redux slices, async thunks, screens.
- `tsc --noEmit` должен быть чистым.

**Hard rules:**
- TypeScript strict, никаких `any`.
- Functional components + hooks, никаких classes.
- Redux Toolkit, без Redux-saga.

---

### `/tester TASK-NNN`

**Что делает:**
- Запускает тестера-субагента.
- По 3 уровням ([11-testing.md](docs/architecture/11-testing.md)):
  - **unit** (быстро, без зависимостей)
  - **integration** (Testcontainers для PG/Redis/CH; Fake Driver для Quotes)
  - **system** (Load Simulator, опционально)
- Запускает тесты, проверяет что зелёные.
- Если найден реальный баг — заносит в **Findings**, не правит код.

**Hard rules:**
- Реальные зависимости через Testcontainers, не моки PG/Redis.
- Не подгоняй тест под баг.
- Не используй `Thread.sleep` — это flaky.

---

### `/reviewer TASK-NNN`

**Что делает:**
- Запускает ревьюера-субагента.
- Читает task ledger + `git diff` и сверяет реализованное с заявленным.
- Чек-лист: correctness, error handling, performance, conventions, security, test coverage.
- Финальный gate: **PASS / NEEDS_WORK / FAIL**.

**Что отлавливает:**
- Деньги в Float/Double → CRITICAL.
- ORM → CRITICAL.
- Логирование секретов → CRITICAL.
- SQL-инъекция → CRITICAL.
- Отсутствие тестов на бизнес-логику с деньгами → HIGH.

**Hard rules:**
- Не правит код сам — только диагностирует.
- Не пропускает нарушения стека.

---

### `/committer TASK-NNN | push | release ... | <запрос>`

**Когда вызывать:**
- После того как `/reviewer` дал PASS — пора фиксировать работу в git.
- Когда нужна любая git-операция (создание ветки, push, rebase, revert, log).
- Когда пора выпустить релиз (`/committer release auto`).

**Что делает (commits-режим):**
- Читает task ledger.
- Если ветки нет — создаёт по неймингу `<type>/TASK-NNN-<slug>`.
- Группирует diff в **атомарные коммиты** по Angular Commit Convention.
- Показывает **превью** коммитов перед запуском.
- Делает `git commit` (HEREDOC, с правильным форматированием).
- **Обновляет `CHANGELOG.md`** в секции `[Unreleased]` для user-visible коммитов (feat / fix / perf / BREAKING).
- Финальный коммит: `docs(changelog): ...`.
- По запросу — push с `--set-upstream` при первом разе.
- Обновляет Handoff Log с SHA коммитов.

**Что делает (release-режим):**
- `/committer release auto` — анализирует commits с last tag, выбирает MAJOR/MINOR/PATCH bump.
- Обновляет `VERSION` файл.
- В `CHANGELOG.md` фиксирует `[Unreleased]` как `[X.Y.Z] - YYYY-MM-DD`, заводит свежий пустой `[Unreleased]`.
- Создаёт коммит `chore(release): vX.Y.Z`.
- Создаёт annotated tag `vX.Y.Z` с содержимым релиз-секции changelog.
- Подсказывает `/committer push --tags`.

**Hard rules (важно):**
- Никогда не пушит в `main`/`master` напрямую.
- Никогда не делает `--force` push без явного указания.
- Никогда не использует `--no-verify` для пропуска hooks.
- Никогда не коммитит `.env*`, `*.key`, `secrets*` — стопит и предупреждает.
- Один логический change = один коммит.
- `VERSION` редактирует ТОЛЬКО через `release`-режим (не вручную).
- Опубликованные релиз-секции CHANGELOG **иммутабельны**.

**Шаблон сообщения** (Angular convention):
```
<type>(<scope>): <короткое описание в нижнем регистре>

[опциональное тело — почему]

[опциональные футеры, например "Refs: TASK-NNN"]
```

**Допустимые типы:** `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`.

**Допустимые скоупы для Stockyard:** `gateway`, `core-service`, `quotes`, `driver`, `mobile`, `rn`, `simulator`, `arch`, `adr`, `docs`, `deploy`, `ci`, `deps`, `tests`, `changelog`, `release`.

**Шаблон ветки:** `<type>/[TASK-NNN-]<kebab-slug>`. Примеры: `feature/TASK-001-user-registration`, `fix/TASK-012-buy-race`, `docs/update-architecture-readme`, `chore/upgrade-kotlin-1.9`.

**Mapping коммит → CHANGELOG → bump:**

| Тип коммита | CHANGELOG section | SemVer bump |
|---|---|---|
| `feat` | Added | MINOR |
| `fix` | Fixed | PATCH |
| `perf` | Changed | PATCH |
| `BREAKING CHANGE:` (любой тип) | Changed (с пометкой) | MAJOR |
| `chore(deps)` для CVE | Security | PATCH |
| `docs/style/test/chore/ci/build/refactor` (внутр.) | (skip) | NONE |

---

### `/task list | show TASK-NNN | next`

**Read-only**, ничего не меняет.

| Подкоманда | Что покажет |
|---|---|
| `/task list` | таблицу всех задач (ID, title, stage, roles, last update) |
| `/task show TASK-001` | полное содержимое одного task ledger |
| `/task next` | подсказку какую команду запустить дальше для самой свежей задачи |

---

## 3. Канонический flow одной задачи

Подробный пошаговый сценарий «от идеи до merge».

### Шаг 0: формулируем задачу

**Хорошие задачи** для `/architect`:
- ✅ «реализовать регистрацию пользователя с email и стартовым балансом 100k RUB»
- ✅ «добавить WebSocket-эндпоинт для подписки на котировки с проверкой JWT»
- ✅ «прикрутить ClickHouse materialized view для свечей 5m»

**Плохие задачи:**
- ❌ «сделай всё» — слишком крупно, разбивай на части
- ❌ «исправь баг» — без деталей какой и где
- ❌ «отрефакторь Gateway» — нет конкретного выхода

**Размер задачи:** 1–5 затронутых файлов в идеале. Если архитектор оценил **LARGE** — подумай, не разбить ли на несколько задач.

### Шаг 1: дизайн через `/architect`

```
/architect добавить /v1/orders с поддержкой BUY/SELL и идемпотентности
```

Что произойдёт:
1. Создастся файл `.claude/tasks/TASK-001-add-orders-endpoint.md`.
2. Архитектор-субагент прочитает требования и архитектуру.
3. В файл запишутся: затронутые компоненты (Gateway + Core Service + PG), API-контракт, SQL-миграции, шаги реализации (роль → шаг), ADR-ссылки, риски, suggested next role.
4. Тебе покажут краткий summary.

**Важно:** прочитай дизайн глазами. Если что-то не так — скажи прямо в чат («перепроектируй с учётом X», «добавь обоснование почему Redis Pub/Sub»). Можно повторно вызвать `/architect TASK-001 пересмотри ...` для итерации.

**Когда переходить дальше:** когда дизайн выглядит ок и suggested next role понятна.

### Шаг 2: реализация (одна или несколько ролей)

Архитектор скажет, кто что делает. Типично:
- backend → 1 шаг
- mobile + frontend → независимо
- (иногда) C-driver → отдельная роль (тоже `/backend`, в нём есть C-инструкции)

```
/backend TASK-001
```

Что произойдёт:
1. Backend-роль прочитает task ledger.
2. Прочитает релевантные архитектурные доки (05-communication, 06-data, 07-consistency).
3. Прочитает существующий код в `gateway/` и `core-service/`.
4. Напишет код по плану архитектора.
5. Обновит `## Backend Implementation` в task ledger.

**Параллельно** можно запустить `/mobile TASK-001` или `/frontend TASK-001`, если они независимы. Если есть зависимость (например, mobile ждёт API от backend) — делай последовательно.

### Шаг 3: тесты через `/tester`

```
/tester TASK-001
```

Тестер прочитает что было сделано в Backend/Mobile/Frontend и напишет тесты. Если найдёт баг — занесёт в **Findings**, **не** правя код.

**Если есть Findings:** возвращайся к нужной dev-роли:
```
/backend TASK-001  # тестер нашёл что balance может стать отрицательным при гонке
```

После правки — снова `/tester TASK-001`.

### Шаг 4: review через `/reviewer`

```
/reviewer TASK-001
```

Финальный gate. Возможные исходы:
- **PASS** → можно коммитить.
- **NEEDS_WORK** → возвращайся к нужной роли с findings.
- **FAIL** → крупное архитектурное нарушение, может потребоваться `/architect` пересмотреть план.

### Шаг 5: коммит через `/committer`

Когда `/reviewer` дал PASS — передавай задачу `/committer`. Он:

1. Создаст feature-ветку с правильным неймингом, если её ещё нет.
2. Прочитает diff и разобьёт на **атомарные коммиты** (один логический change = один commit).
3. Покажет **превью** коммитов с их сообщениями ДО запуска.
4. После твоего «ок» — выполнит `git commit` каждый по Angular convention.
5. По запросу `/committer push` — отправит на remote с `--set-upstream`.
6. Обновит Handoff Log в task ledger с SHA коммитов.

```
/committer TASK-001
```

**Никогда не коммить вручную через `git commit ...`** — иначе:
- Не будет соблюдена Angular convention (тип/скоуп/wrap).
- Не будет автоматической ссылки `Refs: TASK-NNN` в footer.
- Не обновится Handoff Log в task ledger.
- Велика вероятность скоммитить случайные файлы (`.env`, `node_modules`).

**Что делать НЕ через `/committer`** (всё равно безопасно):
- Чтение: `git log`, `git diff`, `git status`, `git blame` — можно вручную, это read-only.
- Stash: `git stash`, `git stash pop` — можно, но `/committer` тоже умеет.

---

## 4. Примеры реальных flow

### Пример A: маленькая задача (только backend)

> «Добавь endpoint `GET /v1/instruments` который возвращает каталог»

```
[1] /architect добавить GET /v1/instruments возвращающий весь каталог инструментов
   → TASK-005 создан, complexity SMALL, suggested: /backend

[2] /backend TASK-005
   → реализован эндпоинт в Gateway + Core Service.

[3] /tester TASK-005
   → юнит + IT с Testcontainers, всё зелёное.

[4] /reviewer TASK-005
   → PASS, готово.

[5] /committer TASK-005
   → создал ветку feature/TASK-005-instruments-catalog
   → 2 атомарных коммита:
     • feat(core-service): add InstrumentRepository.listAll
     • feat(gateway): add GET /v1/instruments endpoint

[6] /committer push
   → push --set-upstream origin feature/TASK-005-instruments-catalog
```

### Пример B: фича на трёх ролях параллельно

> «Реализуй экран подписки на котировки на Android и RN с подключением к WS»

```
[1] /architect реализуй WS-подписку на котировки в Android и RN с авто-reconnect

   → TASK-007 создан. План:
     - mobile: WsClient + QuotesViewModel + QuotesScreen
     - frontend: WS slice + QuotesScreen
     - tester: контрактные тесты на WS frame format

[2a] /mobile TASK-007  ← параллельно
[2b] /frontend TASK-007  ← параллельно
   (можно в разных вкладках Claude или последовательно — оба клиента независимы)

[3] /tester TASK-007
   → smoke-тесты на ViewModel + Jest на slice. Системный тест отложен.

[4] /reviewer TASK-007
   → PASS.

[5] /committer TASK-007
   → создал ветку feature/TASK-007-quotes-ws-subscription
   → 4 атомарных коммита:
     • feat(mobile): add WsClient with reconnect logic
     • feat(mobile): add QuotesViewModel and QuotesScreen
     • feat(rn): add quotes slice with WebSocket integration
     • feat(rn): add QuotesScreen connected to redux store
```

### Пример C: задача с блокировкой и возвратом

> «Реализуй покупку акций (BUY)»

```
[1] /architect реализуй POST /v1/orders BUY с проверкой баланса и идемпотентностью
   → TASK-012 создан, complexity MEDIUM.

[2] /backend TASK-012
   → backend пишет код, обновляет ledger.

[3] /tester TASK-012
   → находит баг: при гонке двух BUY от одного юзера
     с балансом ровно на один ордер — оба исполняются.
     Заносит в Findings, ledger Stage = needs-fixes.

[4] /backend TASK-012
   → backend читает Findings, добавляет SELECT FOR UPDATE на accounts.
     Обновляет ledger.

[5] /tester TASK-012
   → тест проходит, ledger Stage = tested.

[6] /reviewer TASK-012
   → PASS.

[7] /committer TASK-012
   → создал ветку feature/TASK-012-buy-orders
   → 3 атомарных коммита:
     • feat(core-service): add orders + positions migrations V3
     • feat(core-service): implement OrderService.placeBuy with FOR UPDATE
     • feat(gateway): add POST /v1/orders endpoint
   → "Запустить /committer push?"
```

---

## 5. Параллелизм и ветвление

### Когда можно работать параллельно

✅ **Можно:**
- `/mobile TASK-NNN` и `/frontend TASK-NNN` для одной задачи (если у обоих есть готовый API).
- Несколько разных TASK одновременно (TASK-005 и TASK-007 разными парами окон).

❌ **Нельзя:**
- `/backend TASK-NNN` и `/mobile TASK-NNN` если mobile ждёт API от backend.
- Две роли одновременно на один TASK ledger — могут перезаписать друг друга.

### Как разделить большую задачу

Если архитектор оценил **LARGE** или ты видишь что задача слишком толстая:

```
/architect разбей TASK-020 на отдельные подзадачи
```

Архитектор разделит на TASK-021, TASK-022, TASK-023 с явными зависимостями (TASK-022 ждёт TASK-021).

---

## 6. Что делать, если…

### …архитектор предлагает выйти за стек

Например, говорит «давай Kafka вместо Redis Pub/Sub». **Откажи.**

```
Это противоречит ТЗ §3 и ADR-001. Перепроектируй в рамках утверждённого стека.
```

### …reviewer вернул NEEDS_WORK

Прочитай findings внимательно. Запусти соответствующую роль:

```
/backend TASK-NNN  # критичные findings были про SQL транзакцию
```

После правки — `/tester TASK-NNN` (если поменялась логика) → `/reviewer TASK-NNN`.

### …нужно срочно поправить мелкий баг

Можно без архитектора, если правка тривиальная (1–3 строки, без изменения архитектуры):

```
/backend быстрая правка: <описание>
```

Backend-роль создаст task сам. Но для этого нужно подкорректировать `.claude/commands/backend.md` или просто вне формата написать коммит. **По умолчанию правила требуют архитектора.**

Если бага влияет на API/схему — обязательно через `/architect`.

### …задача больше неактуальна

Просто пометь файл вручную:
- В `## Meta` поставь `Stage: cancelled`.
- В `## Handoff Log` добавь причину отмены.
- Не удаляй файл — это часть истории.

### …хочется посмотреть, что вообще происходит

```
/task list           # все задачи и их Stage
/task next           # что делать прямо сейчас
/task show TASK-005  # развернуть конкретную
```

### …Claude уехал не туда

Останови его (Esc / Ctrl-C), скажи прямо:
> «Стоп. Не делай X. Вместо этого: <правильное действие>».

Если Claude всё равно ошибается — проверь, что в `CLAUDE.md` корректно описаны конвенции, и при необходимости добавь специфичное правило туда.

### …нужно научить Claude новой команде/правилу

- Новая команда → создай файл в `.claude/commands/<name>.md` по образцу существующих.
- Новое правило для всех ролей → добавь в `CLAUDE.md` в секцию «Конвенции» или «Workflow Rules».
- Изменилась архитектура → обнови `docs/architecture/`, потом обнови ссылающиеся ADR при необходимости.

---

## 7. Best practices

### Формулировка задач

✅ **Хорошо:**
- «реализовать `POST /v1/orders` с поддержкой BUY/SELL, идемпотентностью и записью в audit log»
- «добавить экран портфеля на Android с подгрузкой из `/v1/portfolio` и обновлением раз в 5 секунд»

❌ **Плохо:**
- «сделай ордера»
- «улучши код»

Хорошая формулировка — это **действие** + **scope** + (опц.) **acceptance criteria**.

### Размер коммита

- **1 task = 1 коммит** в большинстве случаев.
- Если задача затронула 3 роли (backend, mobile, frontend) — всё ещё один коммит, потому что они логически связаны.
- Большие refactor-задачи можно бить на серию коммитов внутри одного TASK, но это редкий случай.

### Когда обновлять архитектурные доки

Если задача:
- Добавила новый компонент / endpoint / таблицу → обнови `docs/architecture/05-*` или `06-*`.
- Приняла нестандартное решение → создай новый ADR (`docs/architecture/adr/ADR-NNN-*.md`).
- Изменила то, что упомянуто в `docs/architecture/10-scenarios.md` → обнови sequence-диаграмму.

Команды `/architect` и `/backend` обычно сами обновляют доки в рамках задачи. Проверь после окончания.

### Когда **не** использовать ролевой flow

- Тривиальные правки опечаток, форматирования.
- Эксперименты в playground (но не коммить их в main).
- Обновление зависимостей по security-патчу (фиксируется отдельным коммитом без TASK).

### Гигиена task ledger

- Не редактируй чужие разделы (Backend не правит Architect Design).
- Если нужно скорректировать — добавь запись в Handoff Log с пояснением.
- Closed задачи (Stage = done) **не удаляй** — это история проекта.
- Коммить файлы `TASK-*.md` в репозиторий — они часть документации.

---

## 8. FAQ

**Q: Я могу пропускать архитектора и сразу звать `/backend`?**
A: Технически да, но не рекомендуется. Backend-роль ожидает заполненный `## Architect Design` и иначе запросит запустить `/architect` сначала. Это специально — без дизайна качество страдает.

**Q: А если задача очень простая?**
A: Архитектор быстро отработает (его дизайн будет короткий — пара строк). Это страховка от того что ты считаешь задачу простой, но она трогает что-то важное. На простом — потеряешь 30 секунд, на сложном — спасёт от багов.

**Q: Можно ли вызвать `/reviewer` посреди разработки, чтобы получить раннюю обратную связь?**
A: Можно, но reviewer ожидает что хотя бы одна dev-роль закончила. Лучше использовать прямой вопрос: «посмотри код в gateway/Routes.kt — нет ли проблем?». Это проще и быстрее.

**Q: Что если я хочу два разных подхода обсудить с архитектором?**
A: В первом запросе попроси сравнить: «архитектор, спроектируй X, рассмотри 2 подхода: (а) ..., (б) ..., рекомендуй один с обоснованием».

**Q: Где Claude хранит контекст между задачами?**
A: В файлах `.claude/tasks/*.md` и в `CLAUDE.md`. Эти файлы в git — поэтому контекст переживает закрытие чата, рестарт компа, ребут. Любой из команды может прийти и продолжить.

**Q: Как добавить ещё одну роль (например, `/devops`)?**
A:
1. Создай `.claude/commands/devops.md` по образцу других.
2. Добавь раздел в `.claude/tasks/README.md` (структура файла).
3. Обнови шаблон `## DevOps Implementation` в `TASK-000-example-template.md`.
4. Добавь команду в таблицу в `CLAUDE.md` и в этот HOWTO.

**Q: Может ли Claude сам создавать коммиты?**
A: Технически да, но в `CLAUDE.md` явно прописано: **никогда не коммитить без явной команды пользователя**. Запускай git вручную или попроси: «закоммить TASK-007 одним коммитом, conventional commits».

**Q: Как откатить плохую задачу?**
A: Если ещё не коммитили — `git restore .` чтобы откатить файлы + руками удали записи из task ledger или пометь Stage: cancelled.

Если уже закоммитили — `git revert <sha>`. Сам task ledger оставь — он показывает что попробовали и не получилось.

**Q: Что если Claude советует уйти от Stockyard-стека?**
A: Скажи «нет, это противоречит ТЗ §3 и ADR-XXX, найди решение в рамках утверждённого стека». Hard rules в командах должны это ловить, но иногда LLM упрямится — направь его.

---

## 9. Чек-листы

### Перед запуском `/architect`
- [ ] Чётко сформулирована задача (action + scope).
- [ ] Понятно что хочется получить на выходе.
- [ ] Ничего из этого уже не сделано в существующем коде.

### Перед запуском dev-роли (`/backend` / `/mobile` / `/frontend`)
- [ ] Соответствующий TASK-NNN существует.
- [ ] `## Architect Design` заполнен.
- [ ] Ты понимаешь, что должно быть сделано.

### Перед `/committer TASK-NNN`
- [ ] `/reviewer TASK-NNN` показал PASS.
- [ ] Тесты зелёные (`./gradlew test` / `go test ./...`).
- [ ] Архитектурные доки обновлены, если нужно.
- [ ] Файл TASK-NNN-*.md тоже будет добавлен в коммит (committer проследит).
- [ ] Подтверждаешь превью коммитов перед запуском.

---

## 10. Один простой совет

> **Доверяй процессу, но проверяй результат.**

Ролевой flow — это «лестница защит»: архитектор не пускает плохой дизайн, тестер ловит баги, ревьюер не пускает плохой код.

Но Claude — это инструмент, не оракул. Каждые 10–15 минут просматривай:
- Что попадает в task ledger.
- Что попадает в `git diff`.
- Соответствует ли это твоему пониманию задачи.

Если что-то не так — останови, скорректируй, продолжай. Не дай Claude уйти в дрифт на час.

---

## Связанные документы

- [CLAUDE.md](CLAUDE.md) — главный контекст проекта.
- [REQUIREMENTS.md](REQUIREMENTS.md) — требования ТЗ.
- [docs/architecture/README.md](docs/architecture/README.md) — навигация по архитектуре.
- [.claude/tasks/README.md](.claude/tasks/README.md) — формат task ledger.
- [.claude/tasks/TASK-000-example-template.md](.claude/tasks/TASK-000-example-template.md) — пример заполненной задачи.
