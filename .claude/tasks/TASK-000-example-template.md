# TASK-000: Example template (reference, не реальная задача)

> Этот файл — **пример формата** task ledger. Не редактируй его в рамках работы. Реальные задачи начинаются с TASK-001.

## Meta
- ID: TASK-000
- Created: 2026-05-09T20:00:00Z
- Last updated: 2026-05-09T20:00:00Z
- Stage: done
- Touched roles: architect, backend, mobile, tester, reviewer

## Original Request
Реализовать регистрацию пользователя с email + паролем и стартовым балансом 100 000 RUB.

## Architect Design

### Affected components
- API Gateway (новый эндпоинт `POST /v1/auth/register`)
- Core Service (новая бизнес-логика `UserService.register`)
- PostgreSQL (миграция: `users`, `accounts`)
- Android client (экран Login → Register)
- RN client (экран Login → Register)

### API contract changes
```http
POST /v1/auth/register
{ "email": "...", "password": "..." }

→ 201 Created
{ "userId": "u_xxx", "accessToken": "...", "refreshToken": "...", "expiresIn": 900 }
```

### Data model changes
- Таблица `users`: `id TEXT PK, email TEXT UNIQUE, password_hash TEXT, created_at TIMESTAMPTZ`.
- Таблица `accounts`: создание строки RUB с balance_cents = 10_000_000 (100k RUB) в той же транзакции.
- Миграция `V1__init_users.sql`.

### Implementation steps
1. **backend**: создать миграцию V1, реализовать `UserService.register` + `UserApi`.
2. **backend**: добавить `POST /v1/auth/register` в Gateway.
3. **mobile**: экран Register + ViewModel + repository.
4. **frontend**: экран Register + Redux thunk.
5. **tester**: юнит-тесты на UserService, IT с Testcontainers, smoke на UI.
6. **reviewer**: финальный gate.

### ADRs referenced
- ADR-005 (idempotency через UNIQUE — registration не идемпотентна, но это OK; email — естественный uniqueness)
- ADR-006 (argon2id для хэша)

### Risks
- Argon2 CPU-cost при burst registration (см. 08-scaling §«Учитываемые риски»). Митигация: вынести verify в отдельный пул потоков.
- Email-валидация на клиенте и сервере должна совпадать.

### Suggested complexity: MEDIUM (5 файлов)
### Suggested next: /backend TASK-000

## Backend Implementation

### Files changed
- `core-service/src/main/resources/db/migration/V1__init_users.sql` — DDL для users, accounts.
- `core-service/src/main/kotlin/com/stockyard/db/domain/user/UserService.kt` — register, hash через argon2.
- `core-service/src/main/kotlin/com/stockyard/db/domain/user/UserRepository.kt` — INSERT users + accounts в одной TX.
- `core-service/src/main/kotlin/com/stockyard/db/api/UserApi.kt` — `POST /internal/users`.
- `gateway/src/main/kotlin/com/stockyard/gateway/routing/AuthRoutes.kt` — `POST /v1/auth/register` + JWT issuance.

### Key decisions
- Стартовый баланс задаётся через константу `INITIAL_DEPOSIT_CENTS = 10_000_000`.
- Email-валидация: regex + длина ≤ 255.
- Argon2id с параметрами m=19MiB, t=2, p=1 (по ADR-006).

### API endpoints implemented
- `POST /v1/auth/register` — наружу (Gateway).
- `POST /internal/users` — внутренний (Core Service).

### SQL migrations
- `V1__init_users.sql`

### Open questions
- Нет.

## Frontend Implementation
*(не реализовывалось в этом примере, для краткости)*

## Mobile Implementation

### Files changed
- `android-app/.../ui/screens/register/RegisterScreen.kt`
- `android-app/.../viewmodel/RegisterViewModel.kt`
- `android-app/.../data/repository/AuthRepository.kt`
- `android-app/.../data/api/AuthApi.kt` — Retrofit interface.

### Screens added
- RegisterScreen — email field, password field, submit button.

### ViewModels added
- RegisterViewModel — обрабатывает submit, ошибки 422, ретраи 5xx.

### Repositories
- AuthRepository: `register(email, password): Result<AuthTokens>`.

### Open questions
- Нет.

## Tests

### Unit tests added
- `UserServiceTest`: `register с уникальным email — успех`, `register с дублем email — UserAlreadyExistsException`, `password слишком короткий — ValidationException`.
- `RegisterViewModelTest`: `submit при ошибке 422 показывает поле email с ошибкой`.

### Integration tests added
- `UserApiIT` (Testcontainers PG): `POST /internal/users — INSERT в users + accounts в одной TX`.
- `AuthRoutesIT` (Testcontainers PG + Redis): `POST /v1/auth/register — выдаёт валидные JWT, session создан в Redis`.

### System test results
- Не запускался для этого изолированного сценария.

### Coverage delta
- Core Service: +6% (был 0%, стал 6% — ожидаемо для одной фичи).

### Findings
- Нет.

## Review

### Gate: PASS

### Critical findings
- Нет.

### High findings
- Нет.

### Medium findings
- `UserService.kt:42` — magic number `10_000_000`. → Вынести в `INITIAL_DEPOSIT_CENTS` константу. *(уже сделано в Backend Implementation, проверено)*

### Low findings
- `RegisterScreen.kt` — error messages захардкожены, нужна локализация. → отложить, добавить в backlog.

### Positive observations
- Транзакция `users + accounts` в одной TX — соответствует ADR-004 (single tx writer).
- Argon2 параметры берутся из конфига, не захардкожены — хорошо.

## Handoff Log
- 2026-05-09T20:00:00Z: создан через /architect — design complete; suggested next: /backend
- 2026-05-09T20:30:00Z: /backend — реализованы V1, UserService, AuthRoutes; suggested next: /mobile + /tester
- 2026-05-09T21:15:00Z: /mobile — RegisterScreen + ViewModel + repository; suggested next: /tester
- 2026-05-09T22:00:00Z: /tester — 6 unit + 2 IT, все зелёные; suggested next: /reviewer
- 2026-05-09T22:30:00Z: /reviewer — gate PASS, готово к merge.
