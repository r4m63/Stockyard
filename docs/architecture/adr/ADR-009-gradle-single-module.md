# ADR-009: Gradle — single-module per backend service

## Status

Proposed (2026-05-10)

## Context

Stockyard содержит два Kotlin-сервиса: **gateway-service** и **core-service**. Возможные структуры сборки:

1. **Composite build** — root-level `settings.gradle.kts` с `include(":gateway-service", ":core-service", ":common")`. Один корневой `./gradlew` собирает оба сервиса, общий version catalog, общие plugins.
2. **Monorepo с includeBuild** — каждый сервис — самостоятельный Gradle проект, но root-level composite через `includeBuild`. Подмодули можно публиковать как библиотеки.
3. **Single-module per service** — каждый сервис самодостаточен: свой `settings.gradle.kts`, свой `gradle/libs.versions.toml`, свой `./gradlew`. Корневого Gradle нет.

В пользу composite build: общие версии в одном месте, единая команда сборки, проще для CI «собрать всё».

В пользу single-module: каждая команда работает только со своим сервисом, не нужно знать соседний. Сборка быстрее. Composite-build добавляет cognitive load, особенно для junior'ов в команде.

ТЗ не диктует, ТЗ говорит «Gradle (Kotlin)» — конкретная топология за нами.

Команда: **до 10 человек**, MVP за один семестр, разделение на роли (`/backend` Kotlin, `/backend` Go и т.д.). Учебный контекст — приоритет понятности.

## Decision

Каждый Kotlin-сервис — **независимый Gradle проект**:

- `gateway-service/settings.gradle.kts`, `gateway-service/build.gradle.kts`, `gateway-service/gradle/libs.versions.toml`, `gateway-service/gradlew`.
- `core-service/settings.gradle.kts`, `core-service/build.gradle.kts`, `core-service/gradle/libs.versions.toml`, `core-service/gradlew`.

**Никакого root-level `settings.gradle.kts` или `build.gradle.kts`.** `docker-compose.yml` строит каждый сервис из его `context: ./gateway-service` (или `./core-service`).

Версии библиотек дублируются в обоих `libs.versions.toml`. Это сознательный trade-off: дублирование (~30 строк на сервис) против сложности shared catalog.

## Consequences

**Положительные:**

- **Когнитивная нагрузка минимальна.** Открыл `gateway-service/`, `./gradlew build` — собрал. Не нужно знать про `core-service`.
- **Изолированные сборки.** Сломанный gateway-service не блокирует разработку core-service.
- **Скорость.** `./gradlew :gateway-service:build` в composite требует Gradle прочитать все `build.gradle.kts` всех модулей. Single-module — только свой.
- **Простой Dockerfile.** `COPY . .` относительно `context: ./gateway-service` копирует только один сервис, не всё репо.
- **Простое CI.** Каждый сервис — независимый pipeline. Изменение в `gateway-service/` не триггерит rebuild core-service.
- **Учебный проект.** Студенту проще объяснить «зайди в gateway-service, запусти gradlew», чем «гляди в root settings, найди свой подпроект».

**Отрицательные:**

- **Дублирование версий.** Kotlin/Ktor/coroutines версии прописаны в обоих `libs.versions.toml`. При апгрейде надо помнить про оба места. Митигация: при апгрейде делается одна задача с правкой обоих.
- **Нет shared library** для общего кода (DTO, error formats, config helpers). Если такая нужна — вернёмся к этому ADR и переключимся на composite build.
- **Несколько `gradle-wrapper.jar`** в репозитории. Несколько MB лишнего веса. Acceptable.

**Нейтральные:**

- Quotes Service на Go — и так отдельный проект (`go.mod`), не зависит от Gradle-выбора.
- Тесты Kotlin-сервисов используют те же зависимости (Testcontainers, JUnit) — дублируются в обоих `libs.versions.toml`. Не критично.

## Alternatives considered

### Composite build (root settings.gradle.kts)

Отвергнуто:

- Усложняет mental model для команды из 10 человек.
- При работе над gateway не нужно ничего знать про core-service.
- Shared `libs.versions.toml` — мелкий выигрыш, не оправдывает overhead.
- В случае нужды в shared-коде — рассмотреть **отдельную published library** (Maven Central / GitHub Packages), не subproject.

### Maven вместо Gradle

Отвергнуто. ТЗ §3 явно требует Gradle для Kotlin-проектов.

### Bazel / Buck

Отвергнуто. Overkill для MVP. Не в стеке ТЗ.

## Точка эволюции

Если в проекте появится **существенный объём общего кода** между gateway и core (например, ULID generator, общие DTO для internal API, shared exceptions) — переходим на одно из:

- **Published shared library** в локальный Maven repo (предпочтительно — не меняет single-module philosophy).
- **Composite build с includeBuild** для shared-модуля (если перевыпускать lib неудобно).

Триггер: дублирование `>200 строк` идентичного Kotlin-кода между сервисами.

## References

- [TASK-003](../../.claude/tasks/TASK-003-gateway-scaffold.md) — где это применяется впервые.
- [docs/architecture/03-components.md §3.1](../03-components.md#31-api-gateway-ktor) — структура исходников Gateway.
- ТЗ §3 — обязательность Gradle.
