# TASK-015: /health/startup probes + rate limit middleware

## Meta
- ID: TASK-015
- Created: 2026-05-14T14:45:00Z
- Last updated: 2026-05-14T14:50:00Z
- Stage: backend-done
- Touched roles: architect, backend

## Original Request
Из /plan: добавить `/health/startup` (отдельный от readiness — нужен для grace-периода cold-start, в т.ч. для WS subscriber готовности) + rate limit middleware.

## Architect Design

### Affected components
- gateway-service: `/health/startup`, `RateLimitPlugin` (новый).
- core-service: `/health/startup`.
- `QuotesSubscriber.isStarted()` — публичный accessor для probe.

### API contract changes
- Новые публичные эндпойнты: `GET /health/startup` на gateway:8080 и core:8080 (на хосте 8081).
- Все остальные эндпойнты теперь могут вернуть 429 `{error:{code:"RATE_LIMITED"}}` при превышении IP-лимита (default 50 rps/IP).

### Data model changes
- Нет PG. Redis: ключи `ratelimit:ip:{ip}:{epochSec}` с TTL 2 сек.

### Implementation steps
1. **gateway**: `QuotesSubscriber.isStarted()`.
2. **gateway**: `/health/startup` — Redis ping + isStarted().
3. **core**: `/health/startup` — PG + Redis ping.
4. **gateway**: `RateLimitPlugin` (createApplicationPlugin), per-IP sliding window 1сек.
5. **gateway**: install plugin в `Plugins.kt`, передать `redis` через `installPlugins(verifiers, redis)`.

### ADRs referenced
- ADR-012 (новый): sliding-window rate limit, per-IP, headers IETF draft.

### Risks
- Per-IP за NAT/CDN — все клиенты под одним IP делят квоту. Митigation: `RATELIMIT_PER_IP` повышаемая через env. Per-user (Backlog).
- Fail-open при Redis outage — DDoS усиление? Маловероятно: gateway без Redis всё равно отдаёт 503 на /v1/* (Redis Pub/Sub нужен), так что злоумышленник не нагрузит.
- Load Simulator под одним IP в TASK-016 — нужно повысить `RATELIMIT_PER_IP` или дать sim'у несколько IP. Митigation: документировать в TASK-016.

### Suggested complexity: SMALL (4 файла new/modified)
### Suggested next: /tester TASK-015 → /committer

## Backend Implementation

### Files changed
- `gateway-service/src/main/kotlin/com/stockyard/gateway/ws/QuotesSubscriber.kt` (+ isStarted())
- `gateway-service/src/main/kotlin/com/stockyard/gateway/routing/HealthRoutes.kt` (+ /health/startup, optional quotesSubscriber param)
- `gateway-service/src/main/kotlin/com/stockyard/gateway/Application.kt` (передаём quotesSubscriber + redis в installPlugins)
- `gateway-service/src/main/kotlin/com/stockyard/gateway/config/Plugins.kt` (+ install RateLimitPlugin)
- `gateway-service/src/main/kotlin/com/stockyard/gateway/plugins/RateLimit.kt` (new)
- `core-service/src/main/kotlin/com/stockyard/core/routing/HealthRoutes.kt` (+ /health/startup)

### Key decisions
- Per-IP-only в MVP. Per-user — Backlog (требует `authenticate { install(RateLimit) }` per-route).
- Fail-open для устойчивости к Redis-flapping.
- Skip-paths: `/health`, `/metrics`, `/v1/ws` — health не должен быть отрезан при перегрузе, WS уже имеет лимит на коннект-уровне (TASK-010).
- Заголовки `RateLimit-*` — стандартный IETF draft (`RateLimit-Limit / -Remaining / -Reset`).

### API endpoints implemented
- `GET /health/startup` (gateway): 200 если Redis up + QuotesSubscriber started, иначе 503.
- `GET /health/startup` (core): 200 если PG + Redis up, иначе 503.
- 429 `{error:{code:"RATE_LIMITED"}}` на всех остальных эндпойнтах при превышении.

### SQL migrations
- Нет.

### Open questions
- Стоит ли отдавать `/health/ready` 503 если `/health/startup` ещё 503? Сейчас они независимы — startup строже (но используется на этапе init), ready — runtime. OK для MVP.

## Tests
*(заполнит /tester)*

## Review
*(заполнит /reviewer)*

## Handoff Log
- 2026-05-14T14:45:00Z: /architect — design из /plan, упрощён до per-IP-only.
- 2026-05-14T14:50:00Z: /backend — все 6 файлов готовы, обе сборки зелёные. Suggested next: /tester (IT на rate-limit) + /committer.
