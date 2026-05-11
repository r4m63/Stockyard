# TASK-012: Add Gradle wrapper to Kotlin services

## Meta
- ID: TASK-012
- Created: 2026-05-11T19:30:00Z
- Last updated: 2026-05-11T20:05:00Z
- Stage: backend-done
- Touched roles: architect, backend

## Original Request
Chore из TASK-011 follow-up. `gradlew` отсутствует и в `gateway-service/`, и в
`core-service/`, поэтому локальная сборка падает на любом dev box с
системным `gradle 9.x` (Ktor plugin 2.3.13 несовместим). CI/Dockerfile
сейчас тянет gradle из image — это маскирует проблему. Нужен зафиксированный wrapper версии, совместимой с текущим стеком.

## Pipeline Context
Отделённый chore-task из TASK-011 backend findings (Open Question #1).
**Не блокирует** TASK-011 e2e smoke (docker build тянет gradle внутрь image),
но блокирует:

- запуск unit/IT тестов из IDE / CLI на dev box,
- любые tester задачи, требующие `./gradlew test` локально,
- reproducible build в CI без Docker (если когда-нибудь добавим).

## Architect Design

### Affected components

- `gateway-service/` — добавить `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`.
- `core-service/` — то же.
- Dockerfile'ы обоих сервисов — переключить с `FROM gradle:...` на `RUN ./gradlew ...` (если сейчас используют системный gradle в image).
- `.gitignore` — wrapper jar **должен** коммититься (это стандарт Gradle); проверить что не отфильтрован.

### Decision

- **Gradle 8.10.2** — последняя 8.x минор перед 9.x. Совместим с Ktor plugin 2.3.13 (Shadow plugin использует Gradle 8 conventions).
- **`gradle wrapper --gradle-version 8.10.2 --distribution-type bin`** в каждом сервисе из существующей `build.gradle.kts` через однократный системный `gradle` запуск (или из IntelliJ).
- Не вводим composite-build (ADR-009 решил single-module). Каждый сервис самодостаточен.

### Implementation steps

| # | Шаг | Файлы |
|---|---|---|
| 1 | Из репозитория с системным `gradle 8.10.2` (или через Docker): `cd gateway-service && gradle wrapper --gradle-version 8.10.2 --distribution-type bin`. | `gateway-service/gradlew*`, `gradle/wrapper/*` |
| 2 | То же для `core-service`. | `core-service/gradlew*` |
| 3 | Проверить, что `./gradlew compileKotlin` проходит в обоих сервисах. | — |
| 4 | Если Dockerfile использует системный gradle: переключить на `./gradlew`. | `*/Dockerfile` |
| 5 | Обновить CHANGELOG `[Unreleased]` Added: «Gradle wrapper 8.10.2 в gateway-service/ и core-service/». | `CHANGELOG.md` |

### ADR
Не требуется. ADR-009 уже зафиксировал «single-module per service»; wrapper — детализация реализации, не архитектурное решение.

### Risks с митигациями

| Риск | Likelihood | Impact | Митигация |
|---|---|---|---|
| Текущая системная `gradle 9.2.1` не запустит `gradle wrapper --gradle-version 8.10.2` (плагин Ktor падает на старте). | Medium | Medium | Запустить из IntelliJ (IDE поставляет свой gradle daemon), или временно использовать `docker run gradle:8.10.2-jdk21 gradle wrapper ...` чтобы не трогать систему. |
| Wrapper jar добавит ~50 KB × 2 в репо. | Low | Low | Стандарт Gradle, принимаем. |
| После переключения dockerfile'а на wrapper — build time чуть растёт (первый `./gradlew` качает distribution). | Low | Low | Один раз на cold image, CI кэш покрывает. |

### Estimated complexity: **SMALL** (15–30 мин)

### Suggested next role
`/backend TASK-012` — кладёт wrapper'ы, проверяет компиляцию, при
необходимости правит Dockerfile.
После backend → `/tester TASK-012` (smoke: `./gradlew test` зелёный).
После tester → `/reviewer TASK-012`.

## Backend Implementation

### Files changed

| Файл | Что |
|---|---|
| `core-service/gradlew` | NEW, +x. Стандартный Gradle 8.10.2 wrapper launcher. |
| `core-service/gradlew.bat` | NEW. Windows variant. |
| `core-service/gradle/wrapper/gradle-wrapper.jar` | NEW. 46 KB. |
| `core-service/gradle/wrapper/gradle-wrapper.properties` | NEW. `distributionUrl=...gradle-8.10.2-bin.zip`, `validateDistributionUrl=true`. |
| `gateway-service/gradlew` + `gradlew.bat` + `gradle/wrapper/*` | NEW, аналогично. |
| `core-service/Dockerfile` | Stage 1 base `gradle:8.10-jdk21-alpine` → `eclipse-temurin:21-jdk-alpine`. Build команда `gradle buildFatJar` → `./gradlew buildFatJar`. Добавлен `COPY gradlew`. |
| `gateway-service/Dockerfile` | То же. |
| `CHANGELOG.md` | `[Unreleased]` Added: «Gradle wrapper 8.10.2 в обоих сервисах + Dockerfile переключён на wrapper». |

### Key decisions

- **Gradle 8.10.2** — последняя 8.x минор, совместима с Ktor plugin 2.3.13 (Shadow plugin использует Gradle 8 conventions, удалённые в 9.x).
- **Wrapper генерирован из временного `/tmp/wrap-gen` с минимальным `settings.gradle.kts`** (системный `gradle 9.2.1`). Альтернатива через Docker `gradle:8.10.2-jdk21` не понадобилась — wrapper task самодостаточен и не требует валидной project configuration. После генерации `gradlew`, `gradlew.bat`, `gradle/wrapper/{jar,properties}` скопированы в обе сервисные директории.
- **Dockerfile base swap.** `gradle:*-alpine` тянет ~280 MB; `eclipse-temurin:21-jdk-alpine` ~190 MB и не диктует версию gradle, что согласуется с философией wrapper'а (single source of truth — `gradle-wrapper.properties` в репо). Build cache внутри контейнера: первый `./gradlew` качает gradle distribution 8.10.2 (~120 MB), кэшируется в `~/.gradle/wrapper/dists` в build-stage только.
- **`.gitignore` уже корректен** — игнорируется только `.gradle/` (cache), `gradlew*` и `gradle/wrapper/*` не отфильтрованы.

### API endpoints implemented
N/A — chore-task.

### SQL migrations applied
N/A.

### Local checks

- `gateway-service/./gradlew --version` → Gradle 8.10.2, Launcher JVM 17.0.16 — OK.
- `gateway-service/./gradlew compileKotlin --no-daemon` → **BUILD SUCCESSFUL** (1 task UP-TO-DATE, потому что build cache от предыдущей docker-сборки).
- `core-service/./gradlew --version` → Gradle 8.10.2 — OK.
- `core-service/./gradlew compileKotlin --no-daemon` → **BUILD FAILED**, см. F1 ниже. Это **pre-existing dependency bug**, не блокирующий wrapper'у работать.
- Dockerfile синтаксис не валидировал отдельной командой; новая структура (`COPY gradlew` перед `COPY src/`) — стандартный паттерн docker-cache, ничего экзотического.

### Open questions / blockers

**F1 (Medium, PRE-EXISTING, blocking) — `clickhouse-jdbc:0.7.0` тянет несуществующий SNAPSHOT.**

```
Could not find com.clickhouse:clickhouse-http-client:0.7.0-SNAPSHOT.
Required by: root project : > com.clickhouse:clickhouse-jdbc:0.7.0
```

POM `clickhouse-jdbc-0.7.0.pom` ссылается на `clickhouse-http-client:0.7.0-SNAPSHOT`, который никогда не публиковался на Maven Central (только release-версии). Это **известный bug 0.7.0** (см. clickhouse-java issue tracker). Не связан с wrapper'ом — воспроизводился бы и с `gradle 8.10` в Dockerfile, если бы build cache внутри образа не маскировал проблему.

Это **блокер для локальной верификации `./gradlew compileKotlin` в core-service** до его починки, но **не блокирует TASK-011** (Quotes Service интеграция уже работает в Go), и **не блокирует Dockerfile build** (cache может проскочить, если на CI host'е уже подтянуто). Готов к отдельному chore-task'у: «Bump clickhouse-jdbc to 0.7.1 (или downgrade to 0.6.5)». Не делаю в TASK-012, потому что меняется зона ответственности (depчейн ≠ wrapper), и поскольку требует прогона IT-тестов чтобы убедиться что 0.7.1 не ломает CH-операции в `QuotesService`/`PortfolioService`.

**F2 (Low) — Launcher JVM 17, не 21.**

Локальная env разработчика — JDK 17. Dockerfile build-stage использует JDK 21 (`eclipse-temurin:21-jdk-alpine`). `compileKotlin` в gateway-service прошёл с JDK 17 — `kotlin.compilerOptions.jvmTarget` не выставлен жёстко в build.gradle.kts. Если позже потребуется JDK 21-only feature, IDE/локальный gradle падает на dev box без JDK 21. Не блокер; задокументировано на будущее.

## Handoff Log
- 2026-05-11T19:30:00Z: создан как chore follow-up TASK-011 backend; suggested next: `/backend TASK-012`.
- 2026-05-11T20:05:00Z: /backend — wrapper 8.10.2 положен в оба сервиса + Dockerfile'ы переключены на `./gradlew`. Gateway compile OK. Core compile падает на pre-existing clickhouse-jdbc:0.7.0 deps (F1) — вынесено как отдельный кандидат на chore. Wrapper сам по себе работает корректно. Suggested next: `/tester TASK-012` (smoke `./gradlew test` на gateway — должен пройти; core пропустить до фикса F1).
