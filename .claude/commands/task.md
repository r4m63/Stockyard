---
description: "View Stockyard task ledger. Use 'list' for an overview of all tasks, 'show TASK-NNN' for a specific task, 'next' to see suggested next action."
argument-hint: "list | show TASK-NNN | next"
---

You are showing the user the state of the **Stockyard task ledger**. No work, no agents, just listing/reading.

## Input
$ARGUMENTS

---

## Pipeline

### Subcommand: `list`

If `$ARGUMENTS` is `list` (or empty):

1. Run:
   ```bash
   ls .claude/tasks/ 2>/dev/null | grep -E '^TASK-[0-9]{3}' | sort
   ```

2. Для каждого файла извлеки:
   - ID (TASK-NNN из имени).
   - Title (первая `# ...` строка).
   - Stage (из `## Meta`).
   - Touched roles (из `## Meta`).
   - Last updated (из `## Meta`).

3. Покажи таблицу:

| ID | Title | Stage | Roles | Last update |
|---|---|---|---|---|
| TASK-001 | User registration | done | architect, backend, mobile, tester, reviewer | 2026-05-09 |
| TASK-002 | Order BUY flow | backend-done | architect, backend | 2026-05-10 |
| TASK-003 | Quote streaming | architect-done | architect | 2026-05-10 |

4. В конце покажи короткий summary:
   ```
   Всего задач: N
   Готовых (done): X
   В работе: Y
   Заблокированных: Z
   ```

### Subcommand: `show TASK-NNN`

Если `$ARGUMENTS` начинается с `show TASK-`:

1. Извлеки `TASK-NNN`.
2. Найди файл: `ls .claude/tasks/ | grep "^TASK-NNN"`.
3. Прочитай полностью и покажи пользователю **отрендеренный** через `cat` или просто как Markdown.
4. В конце добавь блок «What's next»:
   - Прочитай `Stage` и `Handoff Log`.
   - Подскажи следующую команду.

### Subcommand: `next`

Если `$ARGUMENTS` это `next`:

1. Найди задачу с `Stage` отличным от `done` и **самым свежим** `Last updated`.
2. По её Stage предложи следующую команду:
   - `architect-done` → `/backend TASK-NNN` или `/mobile TASK-NNN` (по suggested next в Handoff Log)
   - `backend-done` / `frontend-done` / `mobile-done` → `/tester TASK-NNN`
   - `tested` → `/reviewer TASK-NNN`
   - `needs-fixes` → роль из `## Review` findings
3. Покажи one-liner: «Twоя следующая активность: `/backend TASK-002` (продолжаем User registration after architect)».

### Если файл не найден

- Сообщи пользователю по-человечески, перечисли существующие TASK-NNN ID, спроси что он хотел.

---

## Hard rules

- **Только чтение.** Эта команда не должна редактировать ни один файл.
- **Не запускай агентов.** Это purely informational.
- **Если задач нет** — скажи так и предложи: «Запусти `/architect <описание задачи>` чтобы начать».
