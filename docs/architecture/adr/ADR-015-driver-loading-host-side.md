# ADR-015: Stockyard driver грузится на хосте, а не из docker-init-контейнера

## Status

Accepted (2026-05-11)

## Context

`quotes-service` читает биржу из character device `/dev/stockyard`,
который создаёт kernel module `stockyard_driver` (TASK-008). Чтобы
device существовал на момент `docker compose up`, кто-то должен:

1. собрать `.ko` под текущий kernel,
2. `insmod` модуль,
3. создать `/dev/stockyard` (через `misc_register` или `mknod`) и
   выставить права, чтобы distroless UID 65532 в `quotes-service`-контейнере мог открыть устройство read-write.

В Stockyard это происходит между booted host и `docker compose up`,
поэтому нужно выбрать ответственного.

Альтернативы:

1. **Init-container внутри `docker-compose.yml` с `privileged: true` + `volumes: /lib/modules:ro`** — контейнер сам делает `make` + `insmod`. Требует совпадения kernel headers контейнера и host kernel, иначе `insmod` падает с `version magic mismatch`. На Apple Silicon Docker Desktop хост — Linux VM с собственным kernel; матчить headers нетривиально. Privileged + module-loading даёт root-эскейп из контейнера: security smell даже для учебного проекта.
2. **`udev`-правило**, ставящее модуль автозагрузкой при boot — требует root-installation на каждой dev-машине, ломается при kernel upgrade без пересборки, прячет состояние «загружен/не загружен» от разработчика. Учебный сценарий: «склонировал репо и поднял compose» — udev-инсталляция превращает первый запуск в системное вмешательство.
3. **Хост-скрипт перед `docker compose up`** — `deploy/scripts/load_driver.sh` (root, идемпотентный) собирает `.ko`, грузит модуль, `chmod 0666 /dev/stockyard`. Compose монтирует уже существующий device через `devices: /dev/stockyard:/dev/stockyard`. Логика загрузки явная, версионируется в git вместе с модулем, легко проверить (`ls /dev/stockyard`).

## Decision

**Operator грузит kernel module на хосте перед `docker compose up`** —
через `deploy/scripts/load_driver.sh` (root, идемпотентный). Compose
не пытается грузить модуль, не использует privileged init-container,
не зависит от udev. `quotes-service` монтирует `/dev/stockyard` через
`devices:` block; если device отсутствует, compose честно падает с
понятной ошибкой («no such file or directory: /dev/stockyard»), и
оператор знает, что забыл шаг load.

## Consequences

**Положительные:**
- Никаких privileged-контейнеров в compose stack.
- Загрузка модуля изолирована в одном скрипте, диагностируется отдельно
  от приложения.
- Учебно прозрачно: разработчик видит, что биржа — это kernel module,
  и не воспринимает её как «магию compose-демона».

**Отрицательные:**
- Cold-start процедура — два шага вместо одного (`sudo load_driver.sh`,
  потом `docker compose up`). README/HOWTO должны это явно объяснять.
- На Apple Silicon Docker Desktop хост — это Linux VM, недоступная для
  shell-доступа без Lima/colima. На macOS этот стек запускается только
  через явный Linux ARM VM (например `lima` с `driver/lima.yaml`); docs
  отмечают это как known limitation.
- `chmod 0666 /dev/stockyard` — широкий доступ. Acceptable для
  single-tenant dev-stand'а; для prod вынесём в group_add + udev.

**Нейтральные:**
- `driver/scripts/load.sh` остаётся как «driver developer» entry point
  (insmod + group + chmod 0660). `deploy/scripts/load_driver.sh` —
  «infra operator» обёртка (build → load.sh → wider chmod). Дублирование
  тонкое, но семантика разная.

## Alternatives considered

- **Privileged init-container** — отвергнуто: kernel-header mismatch
  между Docker Desktop VM и host install неустраним без custom-images
  per kernel version; security smell.
- **udev auto-load на boot** — отвергнуто: скрытое состояние, ломает
  «clean clone → run» сценарий, требует root-инсталляции вне репо.
- **Bake driver в Linux VM image** (Lima/colima preset) — оставлено в
  📦 backlog: даёт single-step start на macOS, но сильно усложняет
  CI/CD и onboarding.
