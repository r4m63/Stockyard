---
description: "Automate the role chain: architect → dev (backend/mobile/frontend) → tester → reviewer. Stops on the first blocker."
argument-hint: "<task description> | TASK-NNN"
allowed-tools: Read, Bash(ls:*), Bash(grep:*), Bash(awk:*), Bash(head:*), Bash(tail:*), Skill
---

# /sy — end-to-end Stockyard task orchestrator

You are running the Stockyard task pipeline automatically.  The
sequence is fixed: **architect → dev role(s) → tester → reviewer**.
Each step is a real `/`-skill invoked through the Skill tool; the
orchestrator only decides which step to run next based on the task
ledger.  Never edit code or git state directly — that is the role
skills' job.

## Input

`$ARGUMENTS` is either a free-form task description (new task) or
an existing `TASK-NNN` ID (resume from current stage).

## Step 0 — Pre-flight

Before the first skill call:

- `git status --short` — note unstaged/untracked changes that aren't
  part of this task.  If working tree is dirty and the dirt is not
  obviously TASK-related, **stop and ask the user**.
- `git branch --show-current` — log it.  If the current branch is a
  feature branch from another task, **stop and ask**.

If pre-flight is clean, proceed.

## Step 1 — Architect

Invoke `/architect` with `$ARGUMENTS` via the Skill tool
(`skill: "architect"`, `args: "$ARGUMENTS"`).

When it finishes:

1. Find the newly created (or updated) ledger: `ls .claude/tasks/ |
   grep -E '^TASK-[0-9]{3}'` and pick the most recent that matches
   the description, OR — if `$ARGUMENTS` was a TASK-NNN ID — read
   that file directly.
2. Capture the **TASK-NNN** ID.  It is reused for every later step.
3. Read the ledger's `## Architect Design → Affected components`
   and `Implementation steps (per role)` sections.

Print one short status line: `[architect] TASK-NNN created.
affected: <list>.  needed dev roles: <list>.`

## Step 2 — Detect dev role(s)

From the architect ledger, decide which dev skills must run:

| Cue in "Affected components" / "Implementation steps" | Skill |
|---|---|
| Android, Jetpack Compose, Kotlin client | `mobile` |
| React Native, RN, TypeScript client | `frontend` |
| Gateway, Core Service, Quotes Service, Driver | `backend` |

If multiple match (e.g., backend **and** mobile in one task), run all
of them, in this order: `backend → mobile → frontend`.  Backend
contracts typically need to exist before clients can call them.

If the architect design is ambiguous, **stop and ask the user**
which dev roles to run.  Do not guess.

## Step 3 — Dev role(s)

For each needed role (in the order above):

1. Invoke the skill via Skill tool: `skill: "backend" | "mobile" |
   "frontend"`, `args: "TASK-NNN"`.
2. When it returns, re-read the ledger.
3. Inspect `Meta → Stage` and the role's own implementation section
   for **open questions / blockers**:
   - `Stage: backend-blocked` / `mobile-blocked` / `frontend-blocked`
     → STOP.  Print the blocker, suggest the user how to unblock
     (often `/architect TASK-NNN` to clarify).
   - Open questions tagged Q-something requiring sign-off → STOP,
     report each Q with context.
   - Otherwise → continue.

Print: `[<role>] TASK-NNN done. files changed: <N>.  open Qs: <count>.`

## Step 4 — Tester

Invoke `skill: "tester"`, `args: "TASK-NNN"`.

When it returns:

1. Read the ledger's `## Tests` section.
2. Inspect **Findings**:
   - Any finding with severity HIGH or CRITICAL → STOP.  Print the
     findings, suggest the dev role to re-invoke.
   - LOW / MEDIUM findings (including ones the tester explicitly
     marks as "for reviewer to triage") → continue.  Note them in
     the status line.

Print: `[tester] TASK-NNN — <N pass / M fail>. findings: <list>.`

## Step 5 — Reviewer

Invoke `skill: "reviewer"`, `args: "TASK-NNN"`.

When it returns:

1. Read `## Review → Gate`.
2. Decision matrix:
   - **PASS** → finished.  Print the gate line and the suggested
     next step (`/ship TASK-NNN` for clean ship, or
     `/committer TASK-NNN` for the slower step-by-step path).
   - **NEEDS_WORK** → STOP.  Print top findings, suggest which dev
     skill to re-invoke (often the same as Step 3).  Do not auto-
     re-run dev — the user decides.
   - **FAIL** → STOP.  Print findings, escalate to the user.  Most
     FAILs are stack violations (ORM, money-as-float) that need a
     conversation, not a re-run.

Print: `[reviewer] TASK-NNN — gate: <verdict>. critical: <N>, high: <N>.`

## Step 6 — Final report

At the very end, output a single block:

```
TASK-NNN: <title>
  architect    ✓
  <dev-role>   ✓
  tester       ✓
  reviewer     <PASS | NEEDS_WORK | FAIL>
suggested next: <command>
```

## Hard rules

- **Never call `/committer` from `/feature`.**  Commits are always
  the user's call — they may want to inspect the diff first, or
  bundle multiple tasks into one PR.
- **Never auto-retry a failed step.**  If a role returns blockers,
  the user decides whether to re-run the same role, escalate to
  `/architect`, or fix the underlying issue manually.
- **Don't fall back to a wrong role.**  If the architect's design
  is ambiguous about which dev role to use, ASK.  Picking the wrong
  role wastes a whole pipeline pass.
- **Status lines are brief.**  Full role output is verbose enough;
  the orchestrator's job is just to glue the steps together.
- **One task per `/feature` invocation.**  If the architect splits
  the work into multiple TASK-NNNs (as TASK-008 did with the
  quotes pipeline), stop after the architect step and let the user
  re-invoke `/feature` per subtask.
