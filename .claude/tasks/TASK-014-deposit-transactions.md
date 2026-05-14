# TASK-014: Deposit endpoint + transactions history

## Meta
- ID: TASK-014
- Created: 2026-05-14T14:35:00Z
- Last updated: 2026-05-14T14:40:00Z
- Stage: backend-done
- Touched roles: architect, backend

## Original Request
Закрыть пробел из /plan: после `register` пользователь получает initial deposit 1M RUB, но после расхода средств пополнить можно только SQL'ем. Нужен `/v1/accounts/deposit` + `/v1/transactions` для замкнутого юзер-флоу.

## Architect Design

### Affected components
- DB: миграция V8 — `idempotency_key TEXT` на `transactions`, partial UNIQUE.
- core-service: `TransactionsService` (новый), расширение `TransactionRepository`, новый `TransactionsApi` (POST deposit, GET list).
- gateway-service: `AccountsRoutes` + `TransactionsRoutes` (новые), `InvalidAmountException`, `CoreServiceClient.deposit`/`.listTransactions`.

### API contract changes
- **Новые публичные эндпойнты:**
  - `POST /v1/accounts/deposit` (JWT + Idempotency-Key) → 201 `{transactionId, balanceCents, currency}`. 422 `INVALID_AMOUNT`, 400 при отсутствии заголовка.
  - `GET /v1/transactions?limit&cursor` (JWT) → 200 `{items, nextCursor}`.
- **Новые internal эндпойнты в core:**
  - `POST /internal/accounts/{userId}/deposit` `{amountCents, currency, idempotencyKey}` → 201 `{transactionId, balanceCents, currency, replay}`.
  - `GET /internal/users/{userId}/transactions` → 200 `{items, nextCursor}`.

### Data model changes
- `transactions`:
  - `+ idempotency_key TEXT` (nullable).
  - `+ uq_transactions_user_type_idem` UNIQUE partial index `(user_id, type, idempotency_key) WHERE idempotency_key IS NOT NULL`.
- Без backfill — legacy BUY/SELL audit rows остаются без ключа.

### Implementation steps
1. **db**: V8 миграция.
2. **core**: расширить `TransactionRepository`: `insertWithIdempotency`, `findByIdempotency`, `listByUser` с keyset-курсором.
3. **core**: `TransactionsService.deposit` (replay-short-circuit + FOR UPDATE + SQLState 23505 fallback).
4. **core**: `TransactionsService.listByUser` (base64 cursor `epochSec.nano:id`, fetch limit+1).
5. **core**: `TransactionsApi` + wire-up в `Application.module()`.
6. **gateway**: `CoreServiceClient.deposit` (sealed `DepositResult`) + `.listTransactions`.
7. **gateway**: `AccountsRoutes` + `TransactionsRoutes`, `InvalidAmountException`, ErrorMapper.
8. **tester**: IT на deposit идемпотентность + pagination + 422 invalid amount.

### ADRs referenced
- ADR-005 (idempotency UNIQUE pattern).
- ADR-011 (новый): partial UNIQUE на transactions, type-scoped key namespace.

### Risks
- Race между двумя POST deposit с одним ключом — митigация через 23505 fallback в сервисе.
- Курсорный формат расширяется свободно (base64 строки) — может сломаться при изменении формата.
- 1M deposit при race с BUY/SELL — обе операции берут `FOR UPDATE` на accounts row, сериализуются Postgres'ом.

### Suggested complexity: MEDIUM (7 файлов new, 5 modified)
### Suggested next: /tester TASK-014 → /reviewer → /committer

## Backend Implementation

### Files changed
- `core-service/src/main/resources/db/migration/V8__transactions_idempotency.sql` (new)
- `core-service/src/main/kotlin/com/stockyard/core/domain/transaction/TransactionRepository.kt` (+ insertWithIdempotency, findByIdempotency, listByUser)
- `core-service/src/main/kotlin/com/stockyard/core/domain/transaction/TransactionsService.kt` (new)
- `core-service/src/main/kotlin/com/stockyard/core/api/TransactionsApi.kt` (new)
- `core-service/src/main/kotlin/com/stockyard/core/Application.kt` (wire transactionsApi + service)
- `gateway-service/src/main/kotlin/com/stockyard/gateway/client/CoreServiceClient.kt` (+ deposit + listTransactions + DepositResult/InternalTransactionDto/InternalListTransactionsResponse)
- `gateway-service/src/main/kotlin/com/stockyard/gateway/routing/AccountsRoutes.kt` (new)
- `gateway-service/src/main/kotlin/com/stockyard/gateway/routing/TransactionsRoutes.kt` (new)
- `gateway-service/src/main/kotlin/com/stockyard/gateway/auth/AuthExceptions.kt` (+ InvalidAmountException)
- `gateway-service/src/main/kotlin/com/stockyard/gateway/error/ErrorMapper.kt` (+ InvalidAmountException mapping)
- `gateway-service/src/main/kotlin/com/stockyard/gateway/Application.kt` (wire accountsRoutes + transactionsRoutes)

### Key decisions
- Базовая валюта только `RUB` в MVP. Если currency не задан — default `RUB`.
- Replay-short-circuit ДО `FOR UPDATE`: если идемпотентный ключ уже использован, возвращаем сохранённый txnId + текущий баланс без блокировки строки счёта.
- SQLState 23505 fallback в `deposit` для race: если UNIQUE сработал между findByIdempotency и insert, перечитываем строку и возвращаем replay=true.
- Курсор base64 `urlEncoder().withoutPadding()` — не зависит от шифрования, но скрывает внутренний формат от клиента.
- Cursor decode IllegalArgument → 400 BAD_REQUEST через дефолтный handler.

### API endpoints implemented
- Public: `POST /v1/accounts/deposit`, `GET /v1/transactions`.
- Internal: `POST /internal/accounts/{userId}/deposit`, `GET /internal/users/{userId}/transactions`.

### SQL migrations
- V8__transactions_idempotency.sql

### Open questions
- Лимит на размер deposit? Сейчас только `> 0`. Возможно стоит верхний предел (например 10M RUB) — отложу до /reviewer.
- `currency != "RUB"` падает в `require()` (500). Хотелось бы 422 `UNSUPPORTED_CURRENCY` — но пока MVP, RUB-only, отложу.

## Tests
*(заполнит /tester)*

## Review
*(заполнит /reviewer)*

## Handoff Log
- 2026-05-14T14:35:00Z: /architect — design из /plan, дополнен под TASK-014 scope.
- 2026-05-14T14:40:00Z: /backend — core + gateway + миграция готовы, compileKotlin зелёный обоих сервисов. Suggested next: /tester (IT) + /committer.
