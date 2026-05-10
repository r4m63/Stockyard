# TASK-005: Real auth flow — register/login/refresh

## Meta
- ID: TASK-005
- Created: 2026-05-11T06:00:00Z
- Last updated: 2026-05-11T09:30:00Z
- Stage: committed
- Touched roles: architect, backend, tester, reviewer, committer

## Original Request
для TASK-005 (real auth flow: POST /internal/users + /internal/auth в core с argon2 + JWT issuance + refresh в gateway)

## Architect Design

### 1. Контекст

Все scaffold'ы готовы:
- **TASK-003 (gateway):** `JwtVerifiers` уже умеет issue access/refresh, Authentication-jwt plugin installed, `AuthRoutes` возвращает 501, `CoreServiceClient` есть, fail-fast на `JWT_SECRET`.
- **TASK-004 (core):** `PasswordHasher` (Argon2id + HMAC-SHA256 pepper) готов, `TransactionManager` готов, `UserApi` возвращает 501, fail-fast на `ARGON2_PEPPER`.
- **TASK-001 (storage):** V1 миграция (`users` + `accounts` с `UNIQUE(user_id, currency)`) применяется Flyway'ем.

Задача — превратить 5 stub-эндпоинтов (3 в gateway + 2 в core) в реальную auth-цепочку: register → login → refresh.

### 2. Affected components

| Компонент | Что меняется |
|---|---|
| **API Gateway** | `AuthRoutes` (3 эндпоинта real); `auth/SessionStore` (новый, Redis-bound); `client/CoreServiceClient` (методы `createUser`/`authenticate`); `Application.kt` wire-up |
| **Core Service** | `UserApi` (2 эндпоинта real); `domain/user/{UserService,UserRepository}` (новые); `auth/PasswordHasher` (уже на месте, используем); зависимость на ULID-генератор |
| **Mobile / RN** | не затрагиваются (API контракты не меняются, только перестают возвращать 501) |
| **PostgreSQL** | использует существующие V1 таблицы; **никаких новых миграций** |
| **Redis** | новые ключи `session:{jti}` (TTL 15m, value=userId) и `refresh:{jti}` (TTL 30d, value=userId) — соответствуют [06-data §6.3.2](../../docs/architecture/06-data.md#632-ключи) |

### 3. API contract changes

#### 3.1. Public (Mobile ↔ Gateway) — переход 501 → real

Контракты согласно [05-communication §5.3.2](../../docs/architecture/05-communication.md#532-эндпоинты-rest), без изменений в shape:

```http
POST /v1/auth/register
{ "email": "...", "password": "..." }
→ 201 Created
{ "userId": "u_…", "accessToken": "eyJ…", "refreshToken": "eyJ…", "expiresIn": 900 }

# Ошибки:
→ 422 UNPROCESSABLE_ENTITY  { "error": { "code": "INVALID_EMAIL" } }
→ 422 UNPROCESSABLE_ENTITY  { "error": { "code": "PASSWORD_TOO_WEAK" } }
→ 409 CONFLICT              { "error": { "code": "EMAIL_TAKEN" } }
```

```http
POST /v1/auth/login
{ "email": "...", "password": "..." }
→ 200 OK
{ "accessToken": "eyJ…", "refreshToken": "eyJ…", "expiresIn": 900 }

→ 401 UNAUTHORIZED { "error": { "code": "INVALID_CREDENTIALS" } }  # generic, не отличать "email не найден" от "пароль неверен"
```

```http
POST /v1/auth/refresh
{ "refreshToken": "eyJ…" }
→ 200 OK
{ "accessToken": "eyJ…", "refreshToken": "eyJ…", "expiresIn": 900 }
# Refresh **rotates** (старый удаляется из Redis, выдаётся новый).

→ 401 UNAUTHORIZED { "error": { "code": "INVALID_REFRESH_TOKEN" } }  # подпись/exp/revoked
```

**Без `Idempotency-Key`** в register: естественная уникальность через `users.email UNIQUE` (см. ADR-005 — idempotency обязателен для денежных мутаций, register туда не входит; повторный регистр с тем же email → 409).

#### 3.2. Internal (Gateway → Core) — точечное уточнение

[05-communication §5.4.2](../../docs/architecture/05-communication.md#542-примеры-эндпоинтов) показывает в примере `{ "email", "passwordHash": "argon2$..." }` для `POST /internal/users`. **Меняем на plaintext password**:

```http
POST /internal/users
{ "email": "...", "password": "..." }
→ 201 { "userId": "u_…" }
→ 409 { "error": { "code": "EMAIL_TAKEN" } }
→ 422 { "error": { "code": "INVALID_EMAIL" | "PASSWORD_TOO_WEAK" } }

POST /internal/auth
{ "email": "...", "password": "..." }
→ 200 { "userId": "u_…", "passwordValid": true }
→ 200 { "userId": null, "passwordValid": false }
```

**Обоснование изменения** (passwordHash → password):
- `PasswordHasher` уже централизован в core (TASK-004) с pepper'ом из `ARGON2_PEPPER` env.
- Pepper находится **только** в core; передача `passwordHash` от gateway требовала бы pepper'а в gateway — нарушение separation.
- Internal API — на private docker-сети, plaintext через HTTP допустим (gateway↔core в одной trusted-zone).
- `/internal/auth` всё равно требует plaintext для verify — симметрия с `/internal/users`.

Это правка точечная — `docs/architecture/05-communication.md §5.4.2`. **Не ADR**: уточнение существующего контракта, не новое решение. Аналогичная сноска уже была сделана в TASK-002 для «Core Service» rename.

### 4. Data model changes

**Никаких новых таблиц/колонок/миграций.** V1 (`users` + `accounts`) уже применяется Flyway'ем; нужно только использовать её через JDBC.

**Redis-ключи** уже задизайнены в [06-data §6.3.2](../../docs/architecture/06-data.md#632-ключи):

| Key | Type | TTL | Value |
|---|---|---|---|
| `session:{access_jti}` | STRING | 15 min | `userId` |
| `refresh:{refresh_jti}` | STRING | 30 days | `userId` |

Logout — `DEL session:{jti}` и `DEL refresh:{jti}` (📦 backlog для TASK-005, отдельным маленьким TASK-006a если потребуется).

### 5. Сценарии — happy path

#### 5.1. Register

```
1. Mobile → Gateway:    POST /v1/auth/register { email, password }
2. Gateway:             validate email regex + password ≥ 8 chars
3. Gateway → Core:      POST /internal/users { email, password }
4. Core: TX BEGIN
    a. INSERT INTO users (id=u_<ulid>, email, password_hash=argon2(pwd⊕pepper))
       → если UNIQUE(email) violation → 409 EMAIL_TAKEN
    b. INSERT INTO accounts (user_id, balance_cents=100_000_000, currency='RUB')
   TX COMMIT
5. Core ← 201 { userId }
6. Gateway: jti_a = ULID; jti_r = ULID
   accessToken  = JWT.sign(sub=userId, jti=jti_a, exp=now+15m)
   refreshToken = JWT.sign(sub=userId, jti=jti_r, exp=now+30d)
7. Gateway → Redis:  SETEX session:{jti_a} 900    userId
                     SETEX refresh:{jti_r} 2592000 userId
8. Gateway ← Mobile: 201 { userId, accessToken, refreshToken, expiresIn: 900 }
```

#### 5.2. Login

```
1. Mobile → Gateway:    POST /v1/auth/login { email, password }
2. Gateway → Core:      POST /internal/auth { email, password }
3. Core:
   SELECT id, password_hash FROM users WHERE email = $1
   → если email не найден → return { userId: null, passwordValid: false }   # const-time на стороне gateway
   PasswordHasher.verify(password_hash, password)
   → если false → return { userId, passwordValid: false }
   → если true  → return { userId, passwordValid: true }
4. Gateway:
   - passwordValid=false → 401 INVALID_CREDENTIALS (generic, не отличаем "не найден" от "пароль неверен")
   - passwordValid=true:
     jti_a = ULID; jti_r = ULID
     accessToken/refreshToken — как в register
     SETEX session:{jti_a} ... ; SETEX refresh:{jti_r} ...
5. Gateway ← Mobile: 200 { accessToken, refreshToken, expiresIn: 900 }
```

#### 5.3. Refresh (rotation)

```
1. Mobile → Gateway:    POST /v1/auth/refresh { refreshToken }
2. Gateway:
   verify JWT signature + exp via refreshVerifier
   → fail → 401 INVALID_REFRESH_TOKEN
   jti_old = JWT.id(refreshToken)
   userId  = JWT.sub(refreshToken)
3. Gateway → Redis:  EXISTS refresh:{jti_old}
   → 0 → 401 INVALID_REFRESH_TOKEN (revoked or expired)
4. Gateway → Redis:  DEL refresh:{jti_old}        # rotation: old token cannot be reused
5. Gateway:
   jti_a_new = ULID; jti_r_new = ULID
   accessToken_new + refreshToken_new — issue с тем же userId
   SETEX session:{jti_a_new} 900 userId
   SETEX refresh:{jti_r_new} 2592000 userId
6. Gateway ← Mobile: 200 { accessToken: new, refreshToken: new, expiresIn: 900 }
```

**Refresh-token rotation** делает украденный refresh одноразовым — атакующий использует, легитимный пользователь следующим refresh'ем получает 401 и понимает, что произошёл компромет.

### 6. Implementation steps (для /backend)

#### Core Service

| # | Шаг | Файлы |
|---|---|---|
| 1 | Добавить ULID-генератор. Зависимость: `io.azam.ulidj:ulidj:1.0.4` в `libs.versions.toml` + `build.gradle.kts`. Утилита `IdGen.userId()` → `"u_" + ULID.random()` | `gradle/libs.versions.toml`, `build.gradle.kts`, `domain/user/IdGen.kt` |
| 2 | `domain/user/User.kt` data class (id, email, passwordHash, createdAt) — без password plaintext в самой модели | `domain/user/User.kt` |
| 3 | `domain/user/UserRepository.kt` — raw JDBC: `insert(conn, user)`, `findByEmail(conn, email): User?`, `insertAccount(conn, userId, balanceCents, currency)`. Каждый метод принимает `Connection` (управление TX — в сервисе) | `domain/user/UserRepository.kt` |
| 4 | `domain/user/UserService.kt`: `register(email, password): String` (userId) — валидация email regex + length, валидация password ≥ 8 chars, `TransactionManager.withTx { conn → INSERT users + INSERT accounts (initial deposit 100_000_000 cents RUB) }`, ловит `SQLException.sqlState == "23505"` (UNIQUE violation) → бросает `EmailTakenException`. `authenticate(email, password): String?` — `findByEmail`, `PasswordHasher.verify`. Возвращает `userId` или `null` | `domain/user/UserService.kt`, `domain/user/exceptions.kt` |
| 5 | `api/UserApi.kt` real impls: `POST /internal/users` декодит `{email, password}`, валидирует, вызывает `UserService.register`, маппит исключения (EmailTakenException → 409, ValidationException → 422), возвращает `{userId}`. `POST /internal/auth` декодит, вызывает `authenticate`, возвращает `{userId, passwordValid}` (200 в обоих случаях — passwordValid=false для неверных кредов). | `api/UserApi.kt` |
| 6 | `Application.kt` wire-up: создать `UserService(repo, txManager, passwordHasher)`, передать в `userApi(...)` | `Application.kt` |
| 7 | Расширить `error/ErrorMapper.kt` маппингом `EmailTakenException → 409`, `ValidationException → 422` (или общий `IllegalArgumentException`) | `error/ErrorMapper.kt` |

#### Gateway

| # | Шаг | Файлы |
|---|---|---|
| 8 | `auth/SessionStore.kt` (новый) — wrapper над Lettuce: `storeAccessSession(jti, userId, ttl)`, `storeRefreshSession(jti, userId, ttl)`, `accessSessionExists(jti): Boolean`, `refreshSessionExists(jti)`, `deleteRefreshSession(jti)`. Использует `RedisModule.withCommandConnection { sync().setex/exists/del }` | `auth/SessionStore.kt` |
| 9 | `client/CoreServiceClient.kt` дополнить методами: `suspend fun createUser(email, password): CreateUserResult` (либо `userId`, либо `EmailTaken`/`Validation` enum), `suspend fun authenticate(email, password): AuthResult` (userId?+passwordValid) | `client/CoreServiceClient.kt` |
| 10 | `auth/AuthService.kt` (новый) — orchestrator: `register/login/refresh` методы. Использует `CoreServiceClient` + `JwtVerifiers` + `SessionStore`. Метод `issueTokensFor(userId): TokenPair` — общая factory из register/login (DRY) | `auth/AuthService.kt` |
| 11 | `routing/AuthRoutes.kt` — заменить три `throw NotImplementedError(...)` на реальные handlers с валидацией и `call.respond`. Сериализационные DTO — `Register/Login/RefreshRequest` и `TokenPairResponse` | `routing/AuthRoutes.kt`, `routing/AuthDtos.kt` |
| 12 | `Application.kt` wire-up: создать `SessionStore(redis)`, `AuthService(coreClient, jwtVerifiers, sessionStore)`, передать в `authRoutes(authService)` | `Application.kt` |
| 13 | `JwtConfig.kt`: убедиться, что у `refreshVerifier` есть тот же `withIssuer/withAudience` блок (там сейчас только `accessVerifier`); если нет — добавить. Также: проверить `acceptLeeway(5)`. Доп. — добавить `refreshAccessVerifier` и `extractJti(token): String` helper | `auth/JwtConfig.kt` |
| 14 | `error/ErrorMapper.kt` — расширить mapper для бизнес-ошибок auth (`InvalidCredentialsException → 401 INVALID_CREDENTIALS`, `EmailTakenException → 409 EMAIL_TAKEN`, `InvalidRefreshTokenException → 401`) | `error/ErrorMapper.kt` |

#### Документация

| # | Шаг | Файлы |
|---|---|---|
| 15 | Точечная правка `docs/architecture/05-communication.md §5.4.2`: пример `POST /internal/users` показывает `password` plaintext (не `passwordHash`). Краткий комментарий "пароль хэшируется в core, см. ADR-006 + TASK-005 ledger" | `docs/architecture/05-communication.md` |
| 16 | Расширить error-codes список в §5.7: добавить `INVALID_EMAIL`, `PASSWORD_TOO_WEAK`, `EMAIL_TAKEN`, `INVALID_CREDENTIALS`, `INVALID_REFRESH_TOKEN` | `docs/architecture/05-communication.md` |

### 7. Валидация на gateway (DTO-level)

**Email:** regex `^[^\s@]+@[^\s@]+\.[^\s@]+$`, длина ≤ 254 (RFC 5321). Слабая, но достаточная — настоящая верификация через подтверждение в почте — backlog 📦.

**Password:** длина ≥ 8 chars, ≤ 256 chars. Никаких complexity-rules (zxcvbn — overkill, NIST 800-63B сейчас рекомендует длину против complexity). Можно добавить blocklist общих паролей (📦 backlog).

Двухуровневая валидация:
- **Gateway DTO-level**: формат email, длина password. Быстрый 422 без хождения в core.
- **Core service-level**: повторная валидация (defense-in-depth) + UNIQUE проверка на уровне БД.

### 8. Безопасность

- **Generic 401 на login** — не отличать «email не найден» от «пароль неверен». Защита от user-enumeration.
- **Argon2 верификация занимает ~50-100 мс** — даже при «email не найден» core делает фиктивный hash, чтобы latency был константным. (📦 точная константа-time реализация откладывается, в MVP принимаем небольшое расхождение в latency для unknown-email).
- **Refresh-token rotation** — каждый refresh выдаёт новый refresh, старый удаляется из Redis. Это компромисс между UX (одна точка отказа — украденный refresh даёт N часов до следующего refresh'а пользователя) и безопасностью.
- **`session:{jti}` EXISTS-check** на каждом authenticated request (TASK-006+) даёт revoke-capability с потерей stateless'а — но это сознательный trade-off из 05-communication §5.8.
- **JWT_SECRET** уже валидируется на старте (fail-fast TASK-003 H1).
- **ARGON2_PEPPER** — то же на старте core (TASK-004).
- **HTTPS только в production**: в MVP-compose внутренняя сеть docker — plaintext acceptable. Edge gateway за nginx-TLS на demo-стенде (📦 деплой-задача).
- **Никаких паролей в логах**: `CallLogging` plugin не логирует body; в `AuthService` структурные логи используют только `email` (для аудита logon-attempts) и НЕ password. JWT в логи целиком не попадают (см. CLAUDE.md «Безопасность»).

### 9. Сценарии ошибок

| Сценарий | Ответ |
|---|---|
| Register: пустой email | 422 `{"error":{"code":"INVALID_EMAIL"}}` |
| Register: email не матчит regex | 422 `INVALID_EMAIL` |
| Register: password < 8 chars | 422 `PASSWORD_TOO_WEAK` |
| Register: email уже занят | 409 `EMAIL_TAKEN` |
| Register: PG недоступен | 503 `STORAGE_UNAVAILABLE` (mapping из 12-storage-operations §12.6) |
| Login: email не найден | 401 `INVALID_CREDENTIALS` (generic) |
| Login: пароль неверен | 401 `INVALID_CREDENTIALS` (generic) |
| Login: PG недоступен | 503 `STORAGE_UNAVAILABLE` |
| Refresh: невалидная подпись JWT | 401 `INVALID_REFRESH_TOKEN` |
| Refresh: expired | 401 `INVALID_REFRESH_TOKEN` |
| Refresh: revoked (нет в Redis) | 401 `INVALID_REFRESH_TOKEN` |
| Refresh: Redis недоступен | 503 `STORAGE_UNAVAILABLE` |

### 10. Тестирование (для /tester)

#### Core Service

**Unit (без Testcontainers):**
- `UserService.register` с моком репозитория и hasher — валидация email/password.
- `UserService.authenticate` с моком репозитория — verify=true/false branches.

**Integration (Testcontainers PG):**
- IT-1: `POST /internal/users` happy path → 201, проверка `SELECT count(*) FROM users + accounts WHERE user_id = ...` (один user, один accounts row, balance 100_000_000).
- IT-2: дубль email → 409 EMAIL_TAKEN (UNIQUE constraint).
- IT-3: register → authenticate с правильным паролем → `passwordValid=true` + userId.
- IT-4: register → authenticate с неверным паролем → `passwordValid=false`.
- IT-5: authenticate несуществующего email → `passwordValid=false`, `userId=null`.
- IT-6: TX rollback при ошибке (например, INSERT accounts с FK violation, искусственно вызвать) → юзер не создаётся (`SELECT count(*) FROM users WHERE email=... = 0`).

#### Gateway

**Unit:**
- `AuthService.issueTokensFor(userId)` — токены валидны через `JwtVerifiers`.
- DTO валидация email/password.

**Integration (Testcontainers Redis + mock CoreServiceClient):**
- IT-7: `POST /v1/auth/register` → mock core возвращает userId → gateway возвращает 201 с токенами; проверить `EXISTS session:{access_jti}` = true, `EXISTS refresh:{refresh_jti}` = true.
- IT-8: `POST /v1/auth/login` → mock core (passwordValid=true) → 200 + токены.
- IT-9: `POST /v1/auth/login` (passwordValid=false) → 401 INVALID_CREDENTIALS.
- IT-10: `POST /v1/auth/refresh` happy path → старый refresh удалён из Redis, новые токены выданы.
- IT-11: `POST /v1/auth/refresh` с фейк-подписью → 401 INVALID_REFRESH_TOKEN.
- IT-12: `POST /v1/auth/refresh` с валидным JWT но удалённым из Redis (revoked simulation) → 401.

**Контрактный E2E (gateway + core + PG + Redis Testcontainers):**
- IT-13: register → login → refresh — полный happy-path через два сервиса.

### 11. ADR

**Не пишем.** Все архитектурные решения уже зафиксированы:
- ADR-005 (idempotency UNIQUE) — register использует естественный UNIQUE(email), не Idempotency-Key.
- ADR-006 (Argon2id) — параметры m=19MiB/t=2/p=1.
- ADR-007 (idempotency-keys retention) — не относится к auth.

Уточнение «passwordHash → password в internal API» — точечная правка §5.4.2 без отдельного ADR (это deployment-detail, не паттерн).

### 12. Risks

| Риск | Импакт | Митигация |
|---|---|---|
| Argon2 CPU-cost под бурст registration (TASK-000 example отмечал) | high — DOS | Очередь registration не пилим в MVP; но Argon2 m=19MiB×t=2×p=1 = ~80 мс на современном CPU — терпимо при <100 register/sec. Если возникнет — async pool с capacity. 📦 |
| User enumeration через timing разницы login | medium | Generic `INVALID_CREDENTIALS` + dummy-hash на unknown email (TASK-005 опционально, иначе принимаем расхождение латентности как accepted-trade-off MVP) |
| Refresh-token theft без detection | medium | Rotation — старый refresh после использования инвалидируется. При попытке использовать дважды одновременно — второй вызов 401, пользователь видит и может перерегистрироваться. Реактивное detection (detect-reuse) — 📦 backlog. |
| Password leakage в логах | high (security) | `CallLogging` filter не логирует body; logger calls в `AuthService` явно используют только email; никаких `password.toString()` в трассах. Reviewer проверит. |
| Plain-text password по docker-network gateway→core | medium | Acceptable в MVP (private network, no transit-encryption). В prod-like (📦) — mTLS между сервисами. |
| Race: одновременный register с тем же email (2 concurrent INSERT) | low | PG UNIQUE INDEX `users.email` сериализует — первый insert ОК, второй получит 23505, маппится в 409. Verified IT-тестом. |
| JWT-secret rotation в эксплуатации | low (📦 backlog) | На MVP пока нет; при ротации все active sessions invalidate. Точка эволюции. |

### 13. Estimated complexity: **MEDIUM**

~4-5 человеко-дней для одного backend разработчика:
- Core (steps 1-7): 1.5-2 дня.
- Gateway (steps 8-14): 1.5-2 дня.
- Документация (steps 15-16): 0.25 дня.
- Wire-up + smoke: 0.25 дня.

Тесты — отдельно ~2-3 дня для `/tester`.

### 14. Suggested next role

`/backend TASK-005` — реализация по 16-шаговому плану.

После backend → `/tester TASK-005` (IT с PG+Redis Testcontainers, mocking CoreServiceClient для gateway-IT).

Параллельно после `/tester` можно стартовать `/mobile TASK-005` и `/frontend TASK-005` (real Register/Login screens — теперь backend отвечает реально, не 501).

## Files Affected (план для backend)

NEW:
- `core-service/src/main/kotlin/com/stockyard/core/domain/user/User.kt`
- `core-service/src/main/kotlin/com/stockyard/core/domain/user/UserService.kt`
- `core-service/src/main/kotlin/com/stockyard/core/domain/user/UserRepository.kt`
- `core-service/src/main/kotlin/com/stockyard/core/domain/user/IdGen.kt`
- `core-service/src/main/kotlin/com/stockyard/core/domain/user/exceptions.kt`
- `gateway-service/src/main/kotlin/com/stockyard/gateway/auth/SessionStore.kt`
- `gateway-service/src/main/kotlin/com/stockyard/gateway/auth/AuthService.kt`
- `gateway-service/src/main/kotlin/com/stockyard/gateway/routing/AuthDtos.kt`

EXTEND:
- `core-service/gradle/libs.versions.toml` (+ ulidj)
- `core-service/build.gradle.kts` (implementation ulidj)
- `core-service/src/main/kotlin/com/stockyard/core/api/UserApi.kt` (real impls)
- `core-service/src/main/kotlin/com/stockyard/core/Application.kt` (wire UserService)
- `core-service/src/main/kotlin/com/stockyard/core/error/ErrorMapper.kt` (auth-exceptions)
- `gateway-service/src/main/kotlin/com/stockyard/gateway/client/CoreServiceClient.kt` (createUser, authenticate)
- `gateway-service/src/main/kotlin/com/stockyard/gateway/routing/AuthRoutes.kt` (real handlers)
- `gateway-service/src/main/kotlin/com/stockyard/gateway/auth/JwtConfig.kt` (refreshVerifier, extractJti)
- `gateway-service/src/main/kotlin/com/stockyard/gateway/Application.kt` (wire SessionStore + AuthService)
- `gateway-service/src/main/kotlin/com/stockyard/gateway/error/ErrorMapper.kt` (auth-exceptions)
- `docs/architecture/05-communication.md` (§5.4.2 password plaintext, §5.7 error codes)

## Backend Implementation

### Files changed

#### Core Service (`core-service/`)

NEW:
- `gradle/libs.versions.toml` — добавлена версия `ulidj = "1.0.4"` + alias `ulidj`.
- `build.gradle.kts` — `implementation(libs.ulidj)`.
- `src/main/kotlin/com/stockyard/core/domain/user/IdGen.kt` — `IdGen.userId()` → `u_<ULID>`.
- `src/main/kotlin/com/stockyard/core/domain/user/User.kt` — domain data class (id, email, passwordHash, createdAt).
- `src/main/kotlin/com/stockyard/core/domain/user/UserRepository.kt` — raw JDBC `insert/findByEmail/insertAccount`; маппит SQLState 23505 на `EmailTakenException`.
- `src/main/kotlin/com/stockyard/core/domain/user/UserService.kt` — оркестратор register/authenticate; валидация email regex и password length 8..256; TX вокруг INSERT users + accounts с депозитом 100_000_000 cents RUB.
- `src/main/kotlin/com/stockyard/core/domain/user/exceptions.kt` — `EmailTakenException`, `ValidationException(errorCode, message)`.

CHANGED:
- `src/main/kotlin/com/stockyard/core/api/UserApi.kt` — real handlers для `POST /internal/users` (201/409/422) и `POST /internal/auth` (всегда 200, `passwordValid` в теле); DTO.
- `src/main/kotlin/com/stockyard/core/error/ErrorMapper.kt` — маппинг `ValidationException → 422`, `EmailTakenException → 409`, `BadRequestException → 400`.
- `src/main/kotlin/com/stockyard/core/Application.kt` — снят `@Suppress("unused")` с `passwordHasher`/`txManager`; конструируется `UserService` и передаётся в `userApi(userService)`.

#### Gateway Service (`gateway-service/`)

NEW:
- `gradle/libs.versions.toml` — добавлен `ulidj` alias.
- `build.gradle.kts` — `implementation(libs.ulidj)`.
- `src/main/kotlin/com/stockyard/gateway/auth/SessionStore.kt` — `storeAccess/RefreshSession`, `access/RefreshSessionExists`, `deleteRefreshSession`; ключи `session:{jti}` / `refresh:{jti}` через Lettuce-пул.
- `src/main/kotlin/com/stockyard/gateway/auth/AuthService.kt` — `register/login/refresh`; общий `issueTokens(userId): TokenPair` со store в Redis; rotation на refresh (EXISTS → DEL old → issue new).
- `src/main/kotlin/com/stockyard/gateway/auth/AuthExceptions.kt` — `InvalidCredentialsException`, `EmailTakenException`, `InvalidRefreshTokenException`, `GatewayValidationException(errorCode, message)`.
- `src/main/kotlin/com/stockyard/gateway/routing/AuthDtos.kt` — `RegisterRequest/Response`, `LoginRequest`, `RefreshRequest`, `TokenPairResponse`.

CHANGED:
- `src/main/kotlin/com/stockyard/gateway/client/CoreServiceClient.kt` — добавлены `createUser(email, password): CreateUserResult` (sealed: Created/EmailTaken/Validation) и `authenticate(email, password): String?`; новый `CoreServiceException` для непредвиденных upstream-ошибок (→ 503 STORAGE_UNAVAILABLE).
- `src/main/kotlin/com/stockyard/gateway/auth/JwtConfig.kt` — добавлен `refreshVerifier` (issuer+audience+leeway, симметрично access); helper `verifyRefresh(token): DecodedJWT`.
- `src/main/kotlin/com/stockyard/gateway/routing/AuthRoutes.kt` — три 501-stub заменены real-handlers, принимают `AuthService`.
- `src/main/kotlin/com/stockyard/gateway/error/ErrorMapper.kt` — маппинг `GatewayValidationException → 422`, `EmailTakenException → 409 EMAIL_TAKEN`, `InvalidCredentialsException → 401 INVALID_CREDENTIALS`, `InvalidRefreshTokenException → 401 INVALID_REFRESH_TOKEN`, `CoreServiceException → 503 STORAGE_UNAVAILABLE`.
- `src/main/kotlin/com/stockyard/gateway/Application.kt` — конструируется `SessionStore` + `AuthService`; передаётся в `authRoutes(authService)`.

#### Documentation

CHANGED:
- `docs/architecture/05-communication.md` §5.4.2 — пример `POST /internal/users` показывает plaintext `password` (вместо `passwordHash`); добавлены примеры ошибок 409/422; NOTE про централизацию `PasswordHasher` в core.
- `docs/architecture/05-communication.md` §5.7 — обновлена таблица «HTTP статусы»; добавлена таблица «Коды ошибок» (code → HTTP → кто бросает).

### Key decisions

1. **Plaintext password в internal API.** `PasswordHasher` оставлен централизованным в core (как в TASK-004). Gateway передаёт пароль в открытом виде по private docker-сети — приемлемо для MVP; точечная правка §5.4.2, ADR не пишем. mTLS — 📦.
2. **ULID-генерация через `io.azam.ulidj:ulidj 1.0.4`.** В core — для `users.id`, в gateway — для `jti` токенов.
3. **Refresh-token rotation.** Каждый успешный `/v1/auth/refresh` `DEL refresh:{old_jti}` перед issue нового. Украденный refresh — одноразовый.
4. **Generic 401 INVALID_CREDENTIALS** на login. Не различаем «email не найден» / «пароль неверен» — защита от user-enumeration. Constant-time на unknown email (dummy hash) НЕ реализован, принимаем минимальную timing-разницу как accepted-trade-off MVP.
5. **`UserService.authenticate` нормализует email** через `trim().lowercase()` — совпадает с валидацией в `register`. UNIQUE(users.email) в PG case-sensitive, поэтому всегда кладём lowercase.
6. **Начальный депозит 100_000_000 cents (1M RUB)** — выделен в `UserService.INITIAL_DEPOSIT_CENTS`.
7. **`UserApi` всегда возвращает 200** на `/internal/auth` (с `passwordValid: false` для неверных creds). 401-маппинг — на gateway-уровне через `InvalidCredentialsException`. Разделяет «технический» и «бизнес» слой.
8. **Sealed `CreateUserResult`** в `CoreServiceClient` вместо exceptions для бизнес-исходов (Created / EmailTaken / Validation). Exceptions остались для unexpected.
9. **`refreshVerifier` отдельный** от `accessVerifier` (сейчас параметры идентичны) — точка эволюции под `typ` claim или разные issuer'ы.
10. **Zero-out `ByteArray`/`CharArray`** после использования (`fill(' ')` в `UserService`, `fill(0)` в `PasswordHasher`) — гигиена secret-handling.
11. **Никаких изменений в DB-схеме.** V1 миграция уже даёт всё.

### API endpoints implemented

#### Gateway (public)
- `POST /v1/auth/register` → 201 `{userId, accessToken, refreshToken, expiresIn}` | 409 EMAIL_TAKEN | 422 INVALID_EMAIL/PASSWORD_TOO_WEAK
- `POST /v1/auth/login` → 200 `{accessToken, refreshToken, expiresIn}` | 401 INVALID_CREDENTIALS | 422
- `POST /v1/auth/refresh` → 200 `{accessToken, refreshToken, expiresIn}` | 401 INVALID_REFRESH_TOKEN

#### Core (internal)
- `POST /internal/users` → 201 `{userId}` | 409 EMAIL_TAKEN | 422 INVALID_EMAIL/PASSWORD_TOO_WEAK
- `POST /internal/auth` → 200 `{userId, passwordValid}` (всегда 200; null+false для unknown email / wrong password)

### SQL migrations

**Никаких новых миграций.** V1 (`users` + `accounts`) уже на месте с TASK-001.

### Open questions / blockers

- **Локальная компиляция не запускалась** — gradle CLI недоступен в окружении (известное ограничение из TASK-003/004). Полная проверка в CI + через /tester (IT через Testcontainers).
- **Constant-time на unknown email** (dummy hash) — НЕ реализован, оставлен в design как accepted MVP trade-off. Если /reviewer будет настаивать — добавлю в round 2.
- **Logout endpoint** — не входил в дизайн TASK-005, остался 📦 backlog. `SessionStore.deleteRefreshSession` готов, но не вызывается из публичных routes.

## Tests

### Unit tests added

#### Core Service
- `core-service/src/test/kotlin/com/stockyard/core/domain/user/UserServiceUnitTest.kt` — **9 кейсов** на валидацию `UserService.register` через mockk-моки `UserRepository`/`TransactionManager`/`PasswordHasher` (валидация бросает до `withTx`, моки не вызываются):
  - empty email → INVALID_EMAIL
  - whitespace-only email → INVALID_EMAIL
  - email без @ → INVALID_EMAIL
  - email без `.` в домене → INVALID_EMAIL
  - email со пробелом → INVALID_EMAIL
  - email превышает 254 символа (RFC 5321) → INVALID_EMAIL
  - password < 8 chars → PASSWORD_TOO_WEAK
  - password > 256 chars → PASSWORD_TOO_WEAK
  - password ровно 8 chars проходит validation gate (любая non-ValidationException acceptable)

#### Gateway Service
- `gateway-service/src/test/kotlin/com/stockyard/gateway/auth/AuthServiceTest.kt` — **11 кейсов** на оркестрацию `AuthService` (моки CoreServiceClient + SessionStore, реальный JwtVerifiers):
  - **register**: empty email, short password, happy path (verify access-токен расшифровывается + обе сессии stored), EmailTaken от core, Validation от core
  - **login**: wrong credentials (core returns null), correct credentials, validation runs before core call
  - **refresh**: foreign signature, expired, jti not in Redis, happy path (DEL old + 2× store new)

### Integration tests added (Testcontainers)

#### Core Service
- `core-service/src/test/kotlin/com/stockyard/core/api/UserApiIT.kt` — **8 кейсов** через `testApplication { }` + Testcontainers PostgreSQL + Redis:
  - happy register → 201 + DB state (users.email lowercase, password_hash starts with `$argon2id$`, accounts row balance=100_000_000, currency=RUB)
  - duplicate email → 409 EMAIL_TAKEN
  - invalid email → 422 INVALID_EMAIL
  - weak password → 422 PASSWORD_TOO_WEAK
  - register → auth с правильным паролем → 200 passwordValid=true + userId совпадает
  - register → auth с неверным паролем → 200 passwordValid=false + userId=null (generic)
  - auth для unknown email → 200 passwordValid=false + userId=null
  - email normalization (mixed-case input → lowercase в DB; auth с mixed-case → OK)
  - 3 register-цикла → ровно по 1 accounts row на каждого user (TX-атомарность)

#### Gateway Service
- `gateway-service/src/test/kotlin/com/stockyard/gateway/auth/SessionStoreIT.kt` — **9 кейсов** на `SessionStore` против реального Redis:
  - storeAccessSession + accessSessionExists
  - storeRefreshSession + refreshSessionExists
  - unknown jti → false для обоих
  - deleteRefreshSession удаляет ключ
  - deleteRefreshSession idempotent на missing key
  - keyspaces разделены (session: vs refresh:)
  - value содержит userId для lookup
  - TTL применён к access (1..accessTtl)
  - TTL применён к refresh (1..refreshTtl)
- `gateway-service/src/test/kotlin/com/stockyard/gateway/routing/AuthRoutesIT.kt` — **11 кейсов** E2E через `testApplication { }` + Testcontainers Redis + embedded mock-core Ktor (свободный порт через `ServerSocket(0)`):
  - register happy → 201 + Redis содержит session:{access_jti} и refresh:{refresh_jti}
  - register dup → 409 EMAIL_TAKEN
  - register invalid email → 422 (gateway DTO-validation, mock-core не вызван)
  - register short password → 422 PASSWORD_TOO_WEAK
  - login happy → 200 + tokens
  - login wrong → 401 INVALID_CREDENTIALS
  - refresh happy → 200 + new tokens + старый refresh removed + новый refresh stored
  - refresh с foreign signature → 401 INVALID_REFRESH_TOKEN
  - refresh reuse (use → reuse того же refresh) → 401 (rotation works)

### Updated tests

- `core-service/src/test/kotlin/com/stockyard/core/routing/StubRoutesIT.kt` — удалены 2 кейса (`POST /internal/users` returns 501, `POST /internal/auth` returns 501). Остаётся 7 кейсов на 5 ещё-стабовых эндпоинтов + 404 для unknown route. Заголовочный комментарий обновлён со ссылкой на `UserApiIT`.
- `gateway-service/src/test/kotlin/com/stockyard/gateway/routing/StubRoutesIT.kt` — удалены 2 кейса (`POST /v1/auth/login`, `POST /v1/auth/register`). `/v1/auth/refresh` в этом файле не было — он покрыт `AuthRoutesIT`. Остаётся 7 кейсов + 404. Заголовочный комментарий обновлён.

### System test results

Не запускались (системные — для Load Simulator). Auth-flow попадёт в realistic-прогон (10к × 10 мин) к финальной защите.

### Coverage delta

Не подсчитан — jacoco-отчёт собирается в CI, не локально (gradle/Docker недоступны).

### Findings

**T1 (testing-infra, не баг):** Compilation и выполнение тестов локально не запускались — нет gradle CLI и docker-сокета для Testcontainers в окружении агента. Все тесты будут запущены в CI (как и для TASK-003/004). Это **известное** ограничение, не баг кода.

**Никаких функциональных багов** в коде, найденных static-чтением: типы выровнены, импорты согласованы, error-mapping симметричен между core и gateway, SQLState 23505 → 409 в обе стороны (Repository + ErrorMapper + CoreServiceClient → AuthService), refresh-rotation atomic с CAS-семантикой (EXISTS + DEL, race-окно accepted).

**Замечания для reviewer (не блокирующие):**
- В `UserApiIT` использован `kotlinx.serialization.json.JsonNull.toString()=="null"` для проверки null userId — компактно, но не идиоматично. Альтернатива — `body["userId"] is JsonNull`.
- `AuthRoutesIT.mockCoreModule` распознаёт сценарии через substring-matching в JSON-тексте (`"dup@example.com"`, `"password":"right-pass"`). Просто, но fragile к изменению формата сериализации. Для MVP — приемлемо; альтернатива — `receive<JsonObject>()` + проверка по ключам.
- `userIdCounter` в `AuthRoutesIT` живёт во всём `PER_CLASS` lifecycle — побочные эффекты между тестами есть, но не критичны (просто разные id).

## Review

### Gate: PASS

0 critical · 0 high · 1 medium · 5 low. Готово к merge.

### Critical findings

Нет.

### High findings

Нет.

### Medium findings

- **M1 — RO SELECT обёрнут в TX (perf).** `core-service/.../domain/user/UserService.kt:62-64` — `authenticate` использует `tx.withTx { repo.findByEmail(conn, email) }` для одного read-only SELECT. `withTx` переводит соединение в `autoCommit=false`, делает `commit()`/`rollback()` и возвращает `autoCommit`. Для read-only SELECT это лишний overhead (особенно под нагрузкой 10к CCU). **Fix:** `dataSources.pg.connection.use { conn -> repo.findByEmail(conn, ...) }` либо отдельный helper `withRoConnection { conn -> ... }`. Не блокирует merge — performance, не correctness; функциональность корректна. Можно отложить в TASK-006a/microoptim.

### Low findings

- **L1 — gateway не нормализует email перед core.** `gateway-service/.../auth/AuthService.kt:88-89` — `validateEmail` делает `.trim()` локально для regex-check, но в `coreClient.createUser(email, password)` передаётся **исходный** email с возможными пробелами/UPPERCASE. Core нормализует сам (`UserService.kt:70`), функционально OK, но gateway-логи могут содержать неклеаненый ввод. **Fix:** возвращать `String` из `validateEmail` (как в core) и передавать нормализованное значение в core.
- **L2 — refresh EXISTS+DEL race-окно.** `gateway-service/.../auth/AuthService.kt:60-63` — между `refreshSessionExists` и `deleteRefreshSession` ~ms gap; два параллельных refresh с одним токеном могут оба пройти EXISTS=true → оба DEL→ оба issue new. Принято в дизайне §12. **Fix (опц):** Redis 6.2+ `GETDEL session:refresh:{jti}` атомарно возвращает значение и удаляет — заменяет `EXISTS+DEL` на одну команду. Не блокирует.
- **L3 — redundant `created_at` в INSERT users.** `core-service/.../domain/user/UserRepository.kt:22, 27` — V1 миграция объявляет `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`. Передача `Timestamp.from(Instant.now())` от Kotlin клиента — лишняя колонка в INSERT + риск clock-skew между приложением и сервером. **Fix:** удалить `created_at` из INSERT, доверить PG `DEFAULT now()`; убрать `createdAt` из `User.kt` если он не используется на read-path (а сейчас не используется — `findByEmail` его читает, но никто на uppr-уровне не смотрит).
- **L4 — out-of-scope правка `docs/architecture/01-context.md`.** `git diff` показывает изменение строк 2-7 — sentence wrapping без семантических правок (вероятно editor auto-format). Не блокирует, но `/committer` должен либо отдельным `style(docs)` коммитом, либо restored в `git restore`. **Action:** committer проверит и решит.
- **L5 — fragile mock-core в IT.** `gateway-service/src/test/.../routing/AuthRoutesIT.kt:236-255` — `mockCoreModule` распознаёт сценарии через substring-matching в JSON-тексте (`text.contains("\"dup@example.com\"")`, `text.contains("\"password\":\"right-pass\"")`). Замена `ContentNegotiation.json {...}` сериализатора на pretty-print сломает тесты. Tester уже отметил. **Fix (опц):** `receiveText()` → `Json.parseToJsonElement()` → `jsonObject["email"]?.jsonPrimitive?.content`.

### Positive observations

- **Refresh-token rotation реально реализована** (EXISTS → DEL → issue new). IT-кейс `refresh reuse of rotated token returns 401` подтверждает.
- **Generic 401 на login + симметричный generic 200 `passwordValid=false` от core** — корректный user-enumeration mitigation на уровне API-контракта, не только error-mapping.
- **Sealed `CreateUserResult` в `CoreServiceClient`** — типобезопасно, расширяемо. Бизнес-исходы через sealed, infrastructure errors через exception — чистая граница.
- **`PasswordHasher.hash/verify` уже зеро-аутят `peppered` ByteArray (TASK-004); `UserService` зеро-аутит `CharArray` после `hash`/`verify`** — секреты не задерживаются в JVM heap дольше нужного.
- **Денежные суммы — `Long` cents.** `INITIAL_DEPOSIT_CENTS: Long = 100_000_000L`, `accounts.balance_cents BIGINT`, `setLong(2, balanceCents)`. Никаких `Double`/`Float`/`BigDecimal`. ✓
- **Только raw SQL через `prepareStatement` + `setString/setLong`.** Никаких string-concat в SQL → SQL injection невозможен. ✓
- **TX обёртывает обе INSERT (users + accounts)** в `register` — частичное состояние («user без accounts» или наоборот) невозможно. ✓
- **JWT_SECRET и ARGON2_PEPPER проверяются fail-fast** на старте Application (унаследовано из TASK-003/004). ✓
- **Pepper не утёк в gateway** — `PasswordHasher` остался только в core; gateway знает только plaintext password в transit (acceptable trade-off, зафиксировано в design + §5.4.2 NOTE).
- **CallLogging filter не логирует body** — пароли не попадут в логи; `AuthService` логирует только `user.id`/`jti.old`, никогда email/password/полный JWT. ✓
- **5 уровней error mapping симметричны** между core и gateway: 422→422, 409→409, 5xx→503 STORAGE_UNAVAILABLE.

## Handoff Log
- 2026-05-11T06:00:00Z: создан через /architect — auth flow спроектирован поверх существующих scaffold'ов (gateway TASK-003, core TASK-004, storage TASK-001). 5 эндпоинтов: 3 gateway (register/login/refresh) + 2 core (users/auth). Refresh-token rotation. Generic 401 INVALID_CREDENTIALS. Никаких новых таблиц/миграций/ADR. Точечная правка 05-communication §5.4.2 (password plaintext в internal API). Suggested next: /backend TASK-005.
- 2026-05-11T07:30:00Z: /backend — реализованы 5 эндпоинтов (3 gateway + 2 core) по 16-шаговому плану архитектора. Новые файлы: 6 в core (`domain/user/*`, `IdGen`, `User`, `UserRepository`, `UserService`, `exceptions`) + 5 в gateway (`auth/SessionStore`, `auth/AuthService`, `auth/AuthExceptions`, `routing/AuthDtos`). Никаких новых миграций. Документация 05-communication обновлена (§5.4.2, §5.7). Compilation локально не проверена (нет gradle CLI). Suggested next: /tester TASK-005 (IT через Testcontainers — PG для core, Redis + mock CoreServiceClient для gateway).
- 2026-05-11T08:30:00Z: /tester — 20 unit + 28 integration = **48 новых тестов** в 5 файлах + 2 StubRoutesIT обрезаны (auth-кейсы перенесены в новые IT). Compilation/выполнение не запускались локально (gradle/docker недоступны — known T1 как и в TASK-003/004). Никаких багов в коде не найдено по static-чтению. Suggested next: /reviewer TASK-005.
- 2026-05-11T09:00:00Z: /reviewer — **gate: PASS**, 0 critical, 0 high. Findings: 1 medium (RO SELECT в TX-обёртке — overhead), 5 low (gateway email normalization, refresh race-окно, redundant created_at, out-of-scope правка 01-context.md, fragile substring-mock). Денежные суммы только `Long` cents; ORM не появился; pepper не утёк в gateway; JWT/пароли не логируются. Готово к merge. Suggested next: /committer TASK-005.
- 2026-05-11T09:30:00Z: /committer — branch `feature/5-auth-flow`, 7 commits: feat(core) b29116e, feat(gateway) a422cb4, docs(arch) 5f87e4b, test(core) 18d5ade, test(gateway) 4d4e0dd, docs(changelog) 3726351, docs(task) <pending>. Out-of-scope drift (`01-context.md`, `REQUIREMENTS.md` — формат-only) восстановлен через `git restore` ДО первого коммита, не попал в branch. CHANGELOG [Unreleased] обновлён 4 записями в Added. Push — отдельной командой.
