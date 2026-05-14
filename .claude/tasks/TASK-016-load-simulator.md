# TASK-016: Load Simulator service

## Meta
- ID: TASK-016
- Created: 2026-05-14T14:55:00Z
- Last updated: 2026-05-14T15:00:00Z
- Stage: backend-done
- Touched roles: architect, backend

## Original Request
ТЗ §2 п.5 — отдельный сервис load-simulator. Закрыть последний gap в составе системы (4 микросервиса вместо 3).

## Architect Design

### Affected components
- Новый Kotlin gradle-проект `load-simulator/` (single-module, ADR-009).
- `docker-compose.yml`: новый service `load-simulator` под profile `sim`.

### API contract changes
- Нет. Симулятор — клиент REST + WS гейтвея.

### Data model changes
- Нет.

### Implementation steps
1. Скаффолд (build.gradle.kts, settings, gradle wrapper из gateway-service).
2. `SimConfig` — env-based config.
3. `Metrics` — counters + sorted-array p50/p95/p99.
4. `SimClient` — Ktor client с register/login/order/deposit/portfolio + startWs.
5. `UserSession` / `Scenario` — register → WS subscribe → BUY/SELL loop с jitter.
6. `Main.kt` — линейный ramp, hold, drain. Печать метрик периодически.
7. Dockerfile (multi-stage, application plugin → installDist).
8. compose-сервис под `profiles: ["sim"]`.

### ADRs referenced
- ADR-013 (новый): Load Simulator как Kotlin sibling-сервис (стек-uniformity, общий Ktor client).
- ADR-009 (existing): single-module per service.

### Risks
- Per-IP rate limit (TASK-015) дросселит симулятор: все юзеры под одним IP контейнера. Митigation: override `RATELIMIT_PER_IP=100000` для sim-прогона; или один процесс sim'а с lots of internal sessions == rate-limit ловит только outbound rps, который остаётся высоким. Документировать в README запуска.
- Argon2id @ register CPU-bound: 10k sequential register'ов = ~minutes. Митigation: ramp 60s даёт core время на разогрев; argon2 параметры m=19MiB t=2 p=1 уже мягкие.
- WS connection cap (5 per user в TASK-010) — одна WS-сессия на юзера ОК.

### Suggested complexity: MEDIUM (7 файлов new + Dockerfile + compose-entry)
### Suggested next: /tester TASK-016 (unit на ramp) → /committer

## Backend Implementation

### Files changed
- `load-simulator/build.gradle.kts` (new)
- `load-simulator/settings.gradle.kts` (new)
- `load-simulator/gradle.properties` (new)
- `load-simulator/gradle/wrapper/*` (copied from gateway-service)
- `load-simulator/gradlew*` (copied from gateway-service)
- `load-simulator/Dockerfile` (new — multi-stage, installDist runtime)
- `load-simulator/src/main/kotlin/com/stockyard/sim/Config.kt` (new)
- `load-simulator/src/main/kotlin/com/stockyard/sim/Metrics.kt` (new)
- `load-simulator/src/main/kotlin/com/stockyard/sim/Client.kt` (new)
- `load-simulator/src/main/kotlin/com/stockyard/sim/Scenario.kt` (new)
- `load-simulator/src/main/kotlin/com/stockyard/sim/Main.kt` (new)
- `load-simulator/src/main/resources/logback.xml` (new)
- `docker-compose.yml` (add `load-simulator` service under `profiles: ["sim"]`)

### Key decisions
- In-process метрики (counters + sorted-array quantiles) вместо Prometheus — slim для MVP. Real-time мониторинг прогрузки делает Prometheus на стороне gateway/core.
- Ramp = линейный delay rampMs/users между корутинами. Самая дешёвая реализация; для production-grade нагрузки потребуется token bucket — Backlog.
- WS — одна connection per user, subscribe на 5 тикеров (default). Соответствует typical mobile client.
- Регистрация: `sim_user_<ULID>@stockyard.test` чтобы UNIQUE email не конфликтовал между прогонами.
- `compileKotlin` зелёный.

### API endpoints implemented
- Нет — симулятор только consumer.

### SQL migrations
- Нет.

### Open questions
- Хотим ли SLO assertion внутри Main (например, exit 1 если p99 > 200ms)? Перенесено в TASK-017 (slo_run.sh).

## Tests
*(заполнит /tester — unit на ramp distribution, на random ticker selection)*

## Review
*(заполнит /reviewer)*

## Handoff Log
- 2026-05-14T14:55:00Z: /architect — design из /plan, упрощён до minimal-but-functional.
- 2026-05-14T15:00:00Z: /backend — gradle project + 5 Kotlin файлов + Dockerfile + compose-entry; `./gradlew compileKotlin` зелёный. Suggested next: /tester (ramp unit-test) + /committer.
