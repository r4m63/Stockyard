# Task Ledger System

Система задач для Stockyard. Каждая задача — отдельный markdown-файл, в который роли (архитектор, backend, frontend, mobile, tester, reviewer) дописывают свой вклад. Это позволяет передавать задачу между ролями с сохранением контекста.

## Правила именования

- Файл: `TASK-NNN-<slug>.md`, где `NNN` — трёхзначный ID, `<slug>` — kebab-case описание (до 4 слов).
- Пример: `TASK-001-user-registration.md`, `TASK-042-order-buy-flow.md`.
- ID монотонно растёт. Не переиспользуется.

## Структура файла

Каждый task ledger имеет следующие разделы. Если роль ещё не работала над задачей — её раздел пустой / отсутствует.

```markdown
# TASK-NNN: <название>

## Meta
- ID: TASK-NNN
- Created: <ISO timestamp>
- Last updated: <ISO timestamp>
- Stage: architect | backend-in-progress | tester-in-progress | review | done
- Touched roles: architect, backend, mobile

## Original Request
<исходный текст пользователя>

## Architect Design
<заполняет /architect>

- Affected components
- API contract changes
- Data model changes
- Implementation steps (per role)
- ADR references
- Risks
- Suggested next role

## Backend Implementation
<заполняет /backend>

- Files changed
- Key decisions
- API endpoints implemented
- SQL migrations applied
- Open questions / blockers

## Frontend Implementation
<заполняет /frontend (React Native)>

## Mobile Implementation
<заполняет /mobile (Android)>

## Tests
<заполняет /tester>

- Unit tests added
- Integration tests added
- System test results (если запускался Load Simulator)
- Coverage delta

## Review
<заполняет /reviewer>

- Gate: PASS | NEEDS_WORK | FAIL
- Critical findings
- High findings
- Medium findings
- Low findings

## Handoff Log
- 2026-05-09T12:00Z: created via /architect — design complete, suggested /backend
- 2026-05-09T14:30Z: /backend started
- 2026-05-09T18:00Z: /backend complete — DB Service endpoints ready, suggested /mobile and /tester
- ...
```

## Жизненный цикл задачи

```
[нет файла]
   │
   │  /architect <описание>
   ▼
TASK-NNN создан, Stage = architect
   │
   │  /backend | /frontend | /mobile  TASK-NNN
   ▼
Stage = <role>-in-progress  →  <role>-done
   │
   │  следующая роль или /tester или /reviewer
   ▼
Stage = review
   │
   │  /reviewer финальный гейт
   ▼
Stage = done
```

## Команды для работы с задачами

| Команда | Что делает |
|---|---|
| `/architect <описание>` | Создаёт новый TASK-NNN, запускает дизайн |
| `/architect TASK-NNN` | Возвращает задачу архитектору (если что-то пересмотреть) |
| `/backend TASK-NNN` | Backend-роль выполняет шаги, относящиеся к ней |
| `/frontend TASK-NNN` | RN-роль |
| `/mobile TASK-NNN` | Android-роль |
| `/tester TASK-NNN` | Тестировщик |
| `/reviewer TASK-NNN` | Code review |
| `/task list` | Список всех задач с их Stage |
| `/task show TASK-NNN` | Показать содержимое задачи |

## Soft Rules

- **Не редактируй чужие разделы.** Каждая роль владеет своим блоком. Если нужно скорректировать — добавь запись в Handoff Log с пояснением.
- **Handoff Log — append-only.** Каждый вход роли в задачу — новая строка с timestamp.
- **Stage обновляется** при каждом запуске роли.
- **Closed-tasks хранятся** — не удаляем, они часть истории.

## .gitignore?

Файлы `TASK-*.md` коммитим в репозиторий — это часть проектной истории и помогает команде синхронизироваться. Удалять их не нужно.
