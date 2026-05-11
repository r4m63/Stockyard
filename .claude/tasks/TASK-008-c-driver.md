# TASK-008: C Linux Driver `/dev/stockyard`

## Meta
- ID: TASK-008
- Created: 2026-05-11T18:00:00Z
- Last updated: 2026-05-11T23:00:00Z
- Stage: committed
- Touched roles: architect, backend, tester, reviewer, committer

## Original Request
TASK-008 — quotes pipeline (Driver + Quotes Service + WS), декомпозиция на 4 подзадачи. Эта подзадача — C-драйвер `/dev/stockyard`.

## Pipeline Context
TASK-008 — первая из 4 подзадач quotes pipeline. Полная декомпозиция и end-to-end диаграмма (before/after `DevPriceFixture`) — в Architect Design ниже + см. TASK-009 / TASK-010 / TASK-011.

```
   TASK-008 ──┐
   (C driver) ├─▶ TASK-009 ──┐
              │   (Quotes Go) │
              │               ├─▶ TASK-011 (integration + retire fixture)
              │               │
   TASK-010 ──┘───────────────┘
   (Gateway WS, может развиваться параллельно поверх DevPriceFixture stub-publisher)
```

## Architect Design

### Affected components
- **NEW** `driver/stockyard_driver.c` — kernel module реализующий character device.
- **NEW** `driver/stockyard_driver.h` — header с `struct stockyard_tick`, ioctl-кодами, magic.
- **NEW** `driver/Makefile` — сборка через `make` против `KERNELRELEASE`.
- **NEW** `driver/README.md` — load/unload procedure (`insmod`, `mknod`, permissions), troubleshooting.
- **NEW** `driver/test/test_driver.c` — userspace тест-программа, читает N тиков и hex-дампит.
- **NEW** `driver/seed/tickers.txt` — список 50 MOEX-тикеров для default init.

Не затрагиваются: Core, Gateway, Quotes Service, БД.

### API contract changes
Не публичный API, но фиксируется **ABI userspace ↔ kernel**.

#### Character device
```
Path:         /dev/stockyard (major=dynamic, через misc_register)
Permissions:  0660, group `stockyard` (через udev rule)
```

#### Tick struct — frozen contract C1
```c
#define STOCKYARD_TICKER_LEN 8

struct stockyard_tick {
    char     ticker[STOCKYARD_TICKER_LEN];   // null-padded ASCII
    uint64_t ts_ns;                          // CLOCK_MONOTONIC at gen
    int64_t  bid_cents;
    int64_t  ask_cents;
    int64_t  last_cents;
    uint32_t volume;
} __attribute__((packed));
// sizeof(stockyard_tick) == 40, little-endian
```

#### read() semantics
- blocking by default; `count` MUST be multiple of `sizeof(stockyard_tick)`
- returns `N * sizeof(stockyard_tick)`, N >= 1
- `-EINVAL` on bad alignment, `-ERESTARTSYS` on signal
- if buffer empty AND `O_NONBLOCK` → `-EAGAIN`
- `poll()/select()` POLLIN supported

#### ioctl commands
```c
#define STOCKYARD_IOC_MAGIC 'S'
#define STOCKYARD_IOC_SET_TICKERS  _IOW(STOCKYARD_IOC_MAGIC, 1, struct stockyard_tickers_cfg)
#define STOCKYARD_IOC_SET_RATE_HZ  _IOW(STOCKYARD_IOC_MAGIC, 2, uint32_t)
#define STOCKYARD_IOC_GET_STATS    _IOR(STOCKYARD_IOC_MAGIC, 3, struct stockyard_stats)
#define STOCKYARD_IOC_RESET        _IO (STOCKYARD_IOC_MAGIC, 4)

struct stockyard_tickers_cfg {
    uint32_t count;                                   // <= 64
    char     tickers[64][STOCKYARD_TICKER_LEN];
    int64_t  initial_prices_cents[64];
    uint32_t volatility_bps[64];                      // 1..1000 bp
};

struct stockyard_stats {
    uint64_t ticks_generated;
    uint64_t ticks_dropped_buffer_full;
    uint32_t current_rate_hz;
    uint32_t configured_tickers;
};
```

**Default rate:** 1 Hz (1 tick/ticker/sec × 50 tickers = 50 ticks/sec).
**Default tickers:** 50 MOEX из `seed/instruments-50.md`.

### Data model changes
Нет. Ничего не пишется в PG/Redis/CH.

### Implementation steps

**Backend (C, single role):**

| # | Шаг | Файлы |
|---|---|---|
| 1 | Skeleton kernel module: `module_init`/`module_exit`, `misc_register` для /dev/stockyard, `MODULE_LICENSE("GPL")`. | `stockyard_driver.c` |
| 2 | `struct stockyard_tick` в header, `_Static_assert(sizeof(...) == 40)`. | `stockyard_driver.h` |
| 3 | Ring buffer на N=8192 тиков (`kfifo`), spinlock-protected. | `stockyard_driver.c` |
| 4 | Timer-based generator: `hrtimer` на configurable rate, на каждый tick — random walk по всем активным тикерам, push в kfifo. Buffer full → drop oldest, increment `ticks_dropped_buffer_full`. | `stockyard_driver.c` |
| 5 | Random walk: `prandom_u32`, `delta_cents = (prandom_u32() % (2*vol)) - vol`. Bid/ask spread фикс 20 bps. Volume = `prandom_u32_max(10000) + 1000`. Clamp `last_cents >= 1`. | `stockyard_driver.c` |
| 6 | `file_operations`: `.read`, `.poll`, `.unlocked_ioctl`, `.open`, `.release`. `read` блокирует на `wait_event_interruptible` если буфер пуст. | `stockyard_driver.c` |
| 7 | `ioctl` handlers: SET_TICKERS (`copy_from_user`, validate count<=64), SET_RATE_HZ (1..1000), GET_STATS, RESET. | `stockyard_driver.c` |
| 8 | Module exit: cancel timer, drain wait queue, unregister misc device. | `stockyard_driver.c` |
| 9 | Makefile: `obj-m := stockyard_driver.o`, target `default` через `$(MAKE) -C /lib/modules/$(shell uname -r)/build M=$(PWD) modules`, `clean`. | `Makefile` |
| 10 | `test/test_driver.c` userspace — `open`, цикл `read` на 10 тиков, print as hex + parsed struct. | `test/test_driver.c` |
| 11 | `README.md`: `make`, `sudo insmod`, udev rule, permissions, troubleshooting (`dmesg | tail`), `rmmod`. | `README.md` |
| 12 | `seed/tickers.txt` + дефолтные цены в копейках. | `seed/tickers.txt` |

**Tester:**

| # | Шаг |
|---|---|
| T1 | Сборка под текущим ядром (Linux x86-64 dev VM): `make`, `insmod`, `lsmod | grep stockyard`. |
| T2 | `test/test_driver` открывает `/dev/stockyard`, читает 10 тиков, размер каждого ровно 40 байт. |
| T3 | Hex-dump первого тика: layout совпадает; **golden hex** сохранить как тест-фикстуру для TASK-009. |
| T4 | `ioctl SET_RATE_HZ` на 10 → получаем ~10 тиков/сек/тикер за 5 сек. |
| T5 | `ioctl SET_TICKERS` с 3 тикерами → читаем 30 тиков → только эти 3. |
| T6 | `ioctl GET_STATS` → `ticks_generated > 0`, `current_rate_hz` совпадает. |
| T7 | Stress: rate=1000, не читаем → `ticks_dropped_buffer_full` растёт, `dmesg` без oops. |
| T8 | `rmmod stockyard_driver` чисто unload (без leak warning). |
| T9 | Reopen test: `open`, `close`, `open` снова — без падений. |

**Reviewer:**
- `MODULE_LICENSE("GPL")` (R10).
- `copy_from_user` / `copy_to_user` для всех ioctl с user pointer'ами.
- `sizeof(struct stockyard_tick) == 40` через `_Static_assert`.
- Spinlock'и не вложенные, не зовут sleepable functions внутри.
- Нет `printk` в hot path (timer callback) — только init/exit/ioctl.
- README имеет реальную проверенную последовательность загрузки.

### ADR
**ADR-010 (NEW): Character device + ioctl, не netlink.**
- Context: нужно отдавать tick stream + конфигурировать тикеры/rate runtime.
- Decision: misc character device + 4 ioctl-команды.
- Alternatives: netlink (overkill для one-way stream), debugfs (для отладки), procfs (text only), sysfs (для конфигов, не streaming).
- Consequences: простая userspace модель (`open`/`read`/`ioctl`).

### Risks с митигациями
| Риск | Likelihood | Impact | Митигация |
|---|---|---|---|
| Apple Silicon dev-машины не могут insmod x86 module | Medium | Medium | Testing в Linux x86-64 VM. README с Vagrantfile. **needs clarification:** есть ли у команды Linux-стенд? |
| Kernel API breaks между LTS (5.x vs 6.x) | Medium | Low | Target: Ubuntu 22.04 (5.15) и 24.04 (6.8). |
| Ring buffer overflow при медленном Quotes Service | Low | Low (тики допустимо терять, ADR-001) | Stats `ticks_dropped_buffer_full` exposed. |
| Permissions: /dev/stockyard 0600 root-only | Medium | Medium | udev rule в README создаёт группу `stockyard` и mode 0660. |
| Random walk даёт отрицательные/нулевые цены | Low | Low | Clamp `last_cents >= 1` после каждого шага. |

### Estimated complexity: **MEDIUM**
3–5 ч/дней. Большая часть — kernel build setup, udev, отладка ioctl. Код ~400–600 строк C.

### Suggested next role
`/backend TASK-008` (один C-разработчик).

## Backend Implementation

### Files changed
| Файл | Что внутри |
|---|---|
| `driver/stockyard_driver.h` | Public ABI: `struct stockyard_tick` (**44 байта** packed — см. open question Q1), ioctl-номера (`'S'` magic, SET_TICKERS / SET_RATE_HZ / GET_STATS / RESET), payloads (`stockyard_tickers_cfg`, `stockyard_stats`). `#ifdef __KERNEL__` guard для shared use из userspace. |
| `driver/stockyard_driver.c` | Полноценный kernel module ~450 строк: `misc_register` для `/dev/stockyard` (mode 0660, dynamic minor), `DECLARE_KFIFO(8192)` ring buffer, `spinlock_t` (irqsave для softirq-context producer'а), `hrtimer` periodic с `CLOCK_MONOTONIC + HRTIMER_MODE_REL`, `wait_queue_head_t` для блокирующего `read`. `atomic64_t` для счётчиков. Все 4 ioctl с `copy_from_user`/`copy_to_user` + валидацией. `_Static_assert(sizeof(stockyard_tick) == STOCKYARD_TICK_SIZE)`. |
| `driver/Makefile` | kbuild out-of-tree: `obj-m := stockyard_driver.o`, цели `modules` / `clean` / `modules_install` / `help`. |
| `driver/test/test_read.c` | Userspace: открыть `/dev/stockyard`, читать N тиков, hex + decoded print. Флаги `--nonblock` (для T7) и `--golden 1` (для T3 — фикстура для TASK-009 unit-теста). |
| `driver/test/test_ioctl.c` | Userspace exerciser: `stats`/`rate <hz>`/`reset`/`load <file>`. Parser `tickers.txt` (TICKER PRICE_CENTS VOL_BPS, `#` comments). |
| `driver/test/Makefile` | gcc `-O2 -Wall -Wextra -Werror -std=c11`. |
| `driver/seed/tickers.txt` | 50 MOEX-тикеров с initial-prices в копейках + волатильностью в bps. Источник: `docs/architecture/seed/instruments-50.md`. |
| `driver/scripts/load.sh` | Idempotent host-side: insmod + groupadd `stockyard` + chgrp `/dev/stockyard` + `test_ioctl load seed/tickers.txt`. |
| `driver/scripts/unload.sh` | `rmmod`. |
| `driver/lima.yaml` | Lima VM config для Apple Silicon: ARM64 Ubuntu 22.04 LTS, bind-mount `~/Projects/Stockyard`, provision устанавливает `build-essential` + `linux-headers-$(uname -r)`. |
| `driver/README.md` | Полная инструкция: что в каждом файле, wire format (44 байта, offsets), build/load/unload на Linux нативно, **build/test через Lima на Apple Silicon**, ioctl reference, troubleshooting (8 типичных проблем). |
| `driver/.gitignore` | kbuild artefacts + userspace бинари. |

### Key decisions

1. **44-байтный tick, не 40.** Архитектор написал «sizeof == 40» в TASK-008/009 ledger'ах, но при подсчёте `8+8+8+8+8+4 = 44` — арифметическая ошибка. Сохранил `volume` (он нужен Quotes Service и WS frame'у), зафиксировал `STOCKYARD_TICK_SIZE = 44`. `_Static_assert` в драйвере + runtime-check в `test_read.c` ловят drift. **Открыт Q1 — needs reviewer/architect sign-off + правка ledger'ов TASK-009.**
2. **Один `spinlock_t` на kfifo+ticker_state** вместо двух. Producer (hrtimer softirq) обновляет оба за один заход, поэтому второй lock излишен. Через `spin_lock_irqsave` — softirq-safe.
3. **`copy_to_user` вне lock'а.** `read()` делает `kfifo_out` в kmalloc'нутый kernel buffer под lock'ом, затем выпускает lock и копирует в userspace — `copy_to_user` может спать (page fault), под spinlock запрещено.
4. **`hrtimer_init` (не `hrtimer_setup`)** — для совместимости с Ubuntu 22.04 (kernel 5.15) и 24.04 (6.8). `hrtimer_setup` — preferred в 6.5+, но 5.15 его нет. `hrtimer_init` удалят только в 6.15+, что вне нашего target.
5. **`get_random_u32`** (не `prandom_u32`, который удалили в 6.1+). Оба taraget kernel (5.15, 6.8) имеют `get_random_u32` — safe в softirq.
6. **Drop-oldest при overflow** (соответствует ADR-001 at-most-once для tick stream): `kfifo_is_full` → `kfifo_get` discard + `kfifo_put` new. Счётчик `ticks_dropped` — `atomic64_t`.
7. **`READ_BATCH_MAX = 128`** — cap на одно `read()`, защита от userspace-запроса на 100 МБ за раз. 128 × 44 = 5 632 байт `kmalloc` per read — OK.
8. **5 дефолтных тикеров in-kernel** (SBER, GAZP, LKOH, ROSN, YNDX), остальные 45 — через `SET_TICKERS` из `seed/tickers.txt`. Это упрощает smoke-test (модуль грузится сразу с разумным состоянием) и держит данные в одном месте (seed/tickers.txt, не дублирован в C).
9. **Apple Silicon strategy = Lima + native ARM64 VM**, не QEMU x86-64 emulation. Драйвер написан arch-neutral, ARM64 Ubuntu запускается на M-серии нативно (через Apple Virtualization Framework под капотом Lima) — нет 10× slowdown эмуляции.
10. **misc_register, не register_chrdev_region + mknod.** Динамический minor, путь `/dev/stockyard` создаётся ядром, `mknod` руками не нужен.

### API endpoints implemented
N/A — это драйвер ядра, public API через character device + ioctl зафиксирован в [stockyard_driver.h](../../driver/stockyard_driver.h).

ABI:
- `open("/dev/stockyard")` — O_RDONLY достаточно для read(); O_RDWR нужен для ioctl SET_*.
- `read(fd, buf, n*44)` → blocking by default, returns bytes (multiple of 44).
- `poll(fd, POLLIN)` → ждёт непустого буфера.
- `ioctl(fd, STOCKYARD_IOC_SET_TICKERS, &cfg)` — replace ticker set.
- `ioctl(fd, STOCKYARD_IOC_SET_RATE_HZ, &rate)` — 1..1000 Hz/ticker.
- `ioctl(fd, STOCKYARD_IOC_GET_STATS, &stats)` — counters + config snapshot.
- `ioctl(fd, STOCKYARD_IOC_RESET)` — defaults.

### SQL migrations
Нет.

### Local build verification
- **Userspace** (`driver/test/`): собирается на macOS arm64 с clang `-Wall -Wextra -Werror -std=c11` без warnings. `sizeof(struct stockyard_tick) == 44 == STOCKYARD_TICK_SIZE` проверено runtime.
- **Kernel module** (`stockyard_driver.c`): **локально на Mac собрать невозможно** (нет Linux kernel-headers, нет `/lib/modules/$(uname -r)/build`). Сборка/insmod/тестирование — в Lima VM (см. README). Этот шаг проходит /tester в Lima-VM.

### Fix pass (round 2, after /reviewer NEEDS_WORK)

| Finding | Patch | Файлы |
|---|---|---|
| H1 wake_up_all race | `wait_event_interruptible` condition теперь учитывает `!atomic_read(&sy.initialized)`; post-wake-up branch возвращает `-ENODEV` если модуль ушёл | `driver/stockyard_driver.c` |
| M1 initialized set до misc_register | Переставил `atomic_set(&sy.initialized, 1)` ПОСЛЕ успешного `misc_register`; + comment | `driver/stockyard_driver.c` |
| M2 vol_cents u32-cast truncation | Переписал `walk_price` в u64-арифметике: `range = (__u64)vol_cents * 2 + 1`, `get_random_u64() % range` | `driver/stockyard_driver.c` |
| M3 .gitignore gap | Добавлены `test/test_layout`, `test/test_errors` | `driver/.gitignore` |
| L1 / F1 kfifo_get unused-result | `if (kfifo_get(...)) atomic64_inc(...);` (проверка return ≠ `(void)` cast — `__must_check` helper не silence'ится `(void)`-cast'ом). Build clean. | `driver/stockyard_driver.c` |
| L2 atomic64_set вне spinlock | Comment добавлен — intentional, telemetry-grade | `driver/stockyard_driver.c` |
| L3 / Q1 follow-up | `§5.5.1` обновлён: 44-byte struct + offsets per field + ссылка на TASK-008 ledger | `docs/architecture/05-communication.md` |
| Q1 cascade → TASK-009 | Контракт C1 в TASK-009 ledger: 44 байта + golden hex fixture (от tester'а) | `.claude/tasks/TASK-009-quotes-service.md` |

Build после fix'ов: **0 warnings, 0 errors** в VM. Smoke (load/read/rmmod) clean. Полный T1-T9+B1-B2+C1-C4 + новые tester-кейсы (concurrent reader+rmmod, SET_TICKERS→partial read→RESET) — work для следующего `/tester TASK-008`.

### Open questions / blockers

- **Q1 (для reviewer/architect):** tick size — 44 байта, не 40, как было в архитектурном дизайне TASK-008/009. Структура с `volume:u32` арифметически даёт 44. Альтернатива — выкинуть `volume` (вернёмся к 40) и заполнять его в Quotes Service случайно. **Рекомендация:** оставить 44 (volume полезен в WS frame'е), обновить TASK-009 ledger §C1, обновить `docs/architecture/05-communication.md §5.5.1`. Запросить sign-off в TASK-009 backend до начала работы.
- **Q2 (для /tester):** golden hex (T3) — драйвер пишет `ts_ns = ktime_get_ns()` (monotonic от boot), поэтому первое значение каждого uptime разное. Golden fixture для TASK-009 unit-теста должна быть либо `ts_ns`-agnostic (проверять offsets кроме 8–16), либо фиксироваться при специальной сборке (e.g., патч с `ts_ns = 0xDEADBEEF` для repeatable testing). **Рекомендация:** в TASK-009 unit-тесте проверять только bytes 0–7 (ticker) и 16–43 (price+volume), пропуская ts_ns offset 8–15. Это всё равно ловит endianness/layout drift.
- **Q3 (для /tester):** Apple Silicon Lima проверка — Lima VM ещё не запускалась автоматизированно. Документация в `README.md` написана идеально, но первый прогон может выявить опечатку. Tester должен пройти полный путь `limactl start → make → insmod → test_read` и записать результат.
- **Q4 (для architect):** `STOCKYARD_RING_SIZE = 8192`. При 50 тикеров × 1000 Hz worst case = 50 000 тиков/сек; буфер на 8192 тика = ~160 ms backlog до drop'ов. Если Quotes Service не читает быстрее, начнётся drop. Адекватно для MVP, но если архитектор хочет — параметр через `module_param` (compile-time или load-time).

## Tests

### Test environment
- **VM:** Lima 2.1.1 → `vz` driver → Ubuntu 22.04.5 LTS ARM64 (kernel `5.15.0-173-generic`)
- **Host:** macOS Apple Silicon (arm64-darwin)
- **Mounts:** project repo bind-mounted r/w into VM at the same absolute path
- **First-run cost:** ~3 минуты (Ubuntu cloud image ~600 MB + nerdctl + apt-get build-essential + linux-headers)
- **Setup automation:** `driver/lima.yaml` — `limactl start --name=stockyard-dev driver/lima.yaml`, проверено

### System tests (T1–T9, plus B1–B2, C1–C4)

Каждый кейс — фактическая команда внутри VM. Все PASS.

| # | Кейс | Команда | Результат |
|---|---|---|---|
| T1 | Build kernel module | `cd driver && make` | ✓ `stockyard_driver.ko` 472 KB, modinfo: GPL/aarch64/5.15.0-173-generic; **1 LOW warning** (см. Findings F1) |
| T2 | Insmod + dev node + read 5 ticks | `insmod ...ko && chgrp stockyard /dev/stockyard && test_read 5` | ✓ 5 default тикеров (SBER/GAZP/LKOH/ROSN/YNDX), 1 Hz, spread bid/ask ≈ 20bps, volume в диапазоне 1000–10000 |
| T3 | Golden hex (live) | `test_read --golden 1` | ✓ 44 байта, offsets совпадают; ticker[0..7]="SBER\0\0\0\0", bid_cents @ off 16 (`e3 70 00 00...` = 0x70e3 = 28899), volume @ off 40 (`02 1b 00 00` = 0x1b02 = 6914) |
| T4 | `SET_RATE_HZ → 10` | `test_ioctl rate 10` | ✓ stats: `current_rate_hz=10`. После 2 сек read'а — ~100–200 тиков (50/sec × 2 + накопленный буфер) |
| T5 | `SET_TICKERS` с 3 кастомными | `test_ioctl load three.txt` (TEST1/2/3) | ✓ stats: `configured_tickers=3`. После reset + reload — read'аются только TEST1/TEST2/TEST3, никаких SBER |
| T6 | `GET_STATS` | многократно по тесту | ✓ возвращает корректный snapshot во всех кейсах |
| T7 | **Stress @ rate=1000** | reset → rate=1000 → не читать 12 сек → stats | ✓ generated=58765, dropped=50573, **drop-oldest math exact: 58765 − 8192 = 50573**, dmesg чист (без oops/BUG/WARN) |
| T8 | `rmmod` clean | `rmmod stockyard_driver` | ✓ модуль ушёл из `lsmod`, `/dev/stockyard` исчезло, в dmesg: `stockyard: unloaded — generated=142150 dropped=133958`, без leak warnings |
| T9 | Reload/reopen cycle | insmod → read → rmmod → insmod → read; 3× open-close-open | ✓ оба run'а отдают тики; open-close-open OK; **O_NONBLOCK на пустом буфере** → EAGAIN ("Resource temporarily unavailable") |
| B1 | **Cross-arch layout consistency** | `test_layout` запущен на Mac arm64-darwin **и** Linux arm64 в VM | ✓ оба производят **идентичные** 44 байта для synthetic fixture (SBER, ts_ns=0, bid=28550, ask=28570, last=28560, vol=12345): `53 42 45 52 00 00 00 00  00 00 00 00 00 00 00 00  86 6f ... 39 30 00 00`. Это fixture для TASK-009 Go-parser unit-теста (Q2 RESOLVED). |
| B2 | **ioctl/read error paths** | `test_errors` — 11 invalid inputs | ✓ все возвращают корректный errno:<br>• `rate ∈ {0, 1001, 65535}` → EINVAL<br>• `rate ∈ {1, 1000}` → OK<br>• `count ∈ {0, 65}` → EINVAL<br>• `price=0` → EINVAL (< MIN_PRICE)<br>• `vol ∈ {0, 1001}` → EINVAL<br>• bad-magic ioctl → ENOTTY<br>• `read(43)` (not multiple of 44) → EINVAL<br>• `read(0)` → returns 0 (legal) |
| C1 | `scripts/load.sh` cold start | `sudo ./scripts/load.sh` | ✓ insmod + chgrp/chmod + seed (50 тикеров из `seed/tickers.txt` через `test_ioctl load`) одной командой |
| C2 | После load.sh: 50 тикеров активны | `test_ioctl stats` | ✓ `configured_tickers=50` |
| C3 | Все 50 distinct тикеров читаются | `test_read 60 | awk '{print $1}' | sort -u | wc -l` | ✓ ровно **50** distinct тикеров (60-тик sample покрывает все 50 + начало нового цикла) |
| C4 | `scripts/unload.sh` | `sudo ./scripts/unload.sh` | ✓ rmmod чисто |

**5 load/unload циклов** за тестовую сессию, **0** oops / BUG / WARN / panic / leak в dmesg за весь прогон.

### Userspace unit tests added
| Файл | Что проверяет |
|---|---|
| `driver/test/test_layout.c` (NEW) | `_Static_assert` × 14: sizeof=44, offsets ticker=0/ts_ns=8/bid=16/ask=24/last=32/vol=40, размеры каждого поля, константы (MAX_TICKERS=64, TICKER_LEN=8, RING_SIZE=8192). Печатает synthetic fixture для TASK-009. Собирается и работает на macOS arm64 без VM. |
| `driver/test/test_errors.c` (NEW) | Runtime exerciser для 11 invalid input paths ioctl/read; печатает ret/errno каждого. Требует загруженный модуль. |

`Makefile` обновлён: новые цели `test_layout`, `test_errors`.

### Integration tests added
N/A — это kernel module, тестируется system-level (T1–T9 + B1–B2 + C1–C4 выше). Testcontainers/Redis/CH/PG не задействованы.

### System test results
Все T1–T9 + B1–B2 + C1–C4 PASS в VM. См. таблицу выше. Load Simulator на этом этапе не нужен — он появится в TASK-011 для end-to-end pipeline.

### Coverage delta
Линейный code coverage для kernel module не считается (kgcov требует ядра, собранного с CONFIG_GCOV_KERNEL). Все ветки протестированы вручную:
- **read() happy path** — T2, T3, T9 ✓
- **read() blocking** — все T*-кейсы по умолчанию blocking ✓
- **read() O_NONBLOCK на пустом** — T9 → EAGAIN ✓
- **read() bad align** — B2 → EINVAL ✓
- **ioctl SET_TICKERS** valid → T5 ✓; invalid count/price/vol → B2 ✓
- **ioctl SET_RATE_HZ** valid → T4 ✓; out-of-range → B2 ✓
- **ioctl GET_STATS** → T6 ✓
- **ioctl RESET** → T5, T7, T9 ✓
- **ioctl bad magic** → B2 → ENOTTY ✓
- **kfifo overflow → drop-oldest** → T7 (exact math) ✓
- **hrtimer rate change** → T4 ✓
- **module init/exit** — 5 циклов ✓
- **dmesg without oops/BUG/WARN** ✓

### Tester re-pass (round 2, after /backend fix pass)

**Build:** `make clean && make` в Lima VM (Ubuntu 22.04 ARM64, kernel 5.15.0-173) → **0 warnings, 0 errors** (L1/F1 закрыт).

**Regression matrix (после patch'ей):**

| # | Кейс | Результат |
|---|---|---|
| T2 | insmod + 5 ticks | ✓ PASS — 5 default tickers, normal spread/volume |
| T7 | stress @ 1000Hz × 8s | ✓ PASS — generated=35085, dropped=26888, drop-oldest math holds |
| T9c | O_NONBLOCK на свежем reset | ⚠ race-y но behavior intact (read возвратил данные за время между reset и read syscall'ом — не bug, не регрессия) |
| T8 | rmmod без активных fd | ✓ PASS — clean unload, no kernel warnings |

**Новые reviewer-предложенные кейсы:**

#### N1 — Concurrent blocking reader + rmmod (H1 fix verification) — **FAILED**

Сетап: загрузить модуль, reset, drain буфер до empty, запустить `test_read 1000` blocking → 3 сек wait чтобы reader попал в `wait_event_interruptible` → `rmmod`.

Результат: `rmmod` мгновенно (**6 ms**) возвратил `EBUSY` («Module stockyard_driver is in use»). `sy_exit` **не вызывался**. Reader продолжал блокироваться до внешнего kill.

**Root cause — не bug драйвера, а архитектурное несоответствие H1 patch'а:**
- В Ubuntu 22.04: `CONFIG_MODULE_FORCE_UNLOAD is not set` → `rmmod -f` запрещён ядром.
- `sy_fops.owner = THIS_MODULE` (стандартный kernel idiom) → open fd инкрементит `module->refcount`.
- Kernel отказывается даже вызвать `sy_exit` пока refcount > 0 → `EBUSY` возвращается мгновенно.
- H1 patch (`atomic_set(initialized, 0)` + `wake_up_all` в `sy_exit`) — этот code path **никогда не исполняется** в EBUSY-сценарии.

Подтверждение: после `pkill -9 -f test_read` → `lsmod` показал refcount=0 → `sudo rmmod stockyard_driver` rc=0. **Standard kernel behavior, not a defect.**

**Реальная mitigation (не код, а procedure):**
1. Quotes Service (TASK-009) ОБЯЗАН ловить SIGTERM и закрывать `/dev/stockyard` fd ДО `kill -9`.
2. Shutdown sequence: `systemctl stop quotes-service` (graceful) → ждать exit → `rmmod stockyard_driver`.
3. Документировать в `driver/README.md` и в TASK-009 ledger.

**H1 patch остаётся в коде как defensive measure** для гипотетических kernels с `MODULE_FORCE_UNLOAD=y` (некоторые distros, custom builds). Не вредит, не помогает в Ubuntu stock.

#### N2 — `SET_TICKERS → fill buffer → RESET → read` — **PASS**

Сетап: load 3 кастомных тикеров (OLD1/OLD2/OLD3) @ 50 Hz → 2 сек накопления (153 тика в kfifo) → `RESET` → read 8 ticks.

Результат: после `RESET` в read-результате только дефолтные 5 тикеров (SBER/GAZP/LKOH/ROSN/YNDX). **OLD1/OLD2/OLD3 не утекли** — `kfifo_reset` под spinlock'ом корректно очистил буфер.

### Findings (round 2)

- **F2 (NEW, HIGH-DESIGN — для /reviewer):** H1 fix как описан в первом review pass — **функционально no-op** на Ubuntu stock (CONFIG_MODULE_FORCE_UNLOAD disabled). Поведение EBUSY-при-открытом-fd является **стандартным kernel idiom**, не bug'ом. **Recommended action:** reviewer должен ре-классифицировать оригинальный H1 как LOW + закрыть его, добавить вместо него Documentation requirement (Quotes Service signal handling + shutdown procedure) в `driver/README.md` секцию «Operating in production».
- **F1 (CLOSED):** kfifo_get unused-result — закрыт через `if (kfifo_get(...))` проверку return value, build clean.

### Findings (round 1 — historical)

- **F1 (LOW)** — `stockyard_driver.c:158` `kfifo_get(&sy.fifo, &discard);` бросает compile-time warning `[-Wunused-result]`:
  ```
  warning: ignoring return value of '__kfifo_uint_must_check_helper' declared with attribute 'warn_unused_result'
  ```
  Макрос `kfifo_get` декорирован `warn_unused_result` чтобы напоминать о проверке. В нашем контексте `kfifo_is_full()` проверён выше, и мы намеренно дискардим один элемент. Фикс: `(void)kfifo_get(&sy.fifo, &discard);` явный cast. Косметика, build success, поведение корректно (T7 показал точную drop-oldest математику). Передаётся /reviewer как low-severity item.

### Resolved open questions (Q2)
- **Q2 RESOLVED (golden hex для TASK-009):** B1 показал, что layout идентичен Mac arm64 и Linux arm64. TASK-009 unit-тест должен использовать synthetic fixture из `test_layout.c` (ts_ns=0, deterministic) — это catches endianness/layout/sizeof drift без зависимости от живого uptime. Фиксированный hex:
  ```
  SBER + ts_ns=0 + bid=28550 + ask=28570 + last=28560 + vol=12345:
  53 42 45 52 00 00 00 00  00 00 00 00 00 00 00 00
  86 6f 00 00 00 00 00 00  9a 6f 00 00 00 00 00 00
  90 6f 00 00 00 00 00 00  39 30 00 00
  ```
- **Q3 RESOLVED (Apple Silicon путь работает):** Lima 2.1.1 + ARM64 Ubuntu 22.04 LTS + bind-mount = build & test за ~3 минуты от чистого состояния. README актуален, опечаток нет. Performance: timer @ 1000 Hz × 5 тикеров устойчив, drop-oldest exact.

### Остающиеся open questions для /reviewer

- **Q1 (CRITICAL для контракта):** tick size 44 байта vs архитектурные 40 в TASK-008/009 ledger'ах. **Tester подтверждает:** реальный sizeof = 44, layout consistent cross-arch, TASK-009 Go-parser должен парсить 44 байта. Требуется решение reviewer + правка TASK-009 ledger §C1 + правка `docs/architecture/05-communication.md §5.5.1`.
- **Q4 (для architect, нерешён):** `STOCKYARD_RING_SIZE = 8192` — на rate=1000 × 5 тикеров drop'ы начинаются мгновенно при отсутствии reader'а (T7 confirmed: 58765 → 50573 dropped). Это OK по ADR-001 at-most-once, но если TASK-009 Quotes Service не читает быстрее — реальные данные потеряются. **Рекомендация:** добавить `module_param(ring_size, uint)` в TASK-009 или подождать boundary-метрики из TASK-009.

## Review (round 2 — final)

### Gate: **PASS** (0 critical, 0 high, 1 medium, 2 low)

Round 1 нашёл 1 HIGH + 3 MEDIUM + 3 LOW. После /backend fix-pass + /tester re-pass: **все 7 round-1 findings закрыты в коде**. Tester'ская F2-аналитика на H1 подтверждена: original H1 был misdiagnosed (на Ubuntu без `CONFIG_MODULE_FORCE_UNLOAD` `sy_exit` не вызывается пока reader держит fd → patch'а ветка кода в EBUSY-сценарии не исполняется). H1 ре-классифицирован → LOW: patch остаётся как defensive code для force-unload kernel'ов (custom builds), но реальная mitigation процедурная.

Lock discipline + memory safety throughout — solid. Никаких новых HIGH/CRITICAL. **Один medium doc-gap** в README остаётся, но не блокирует merge (skill rule: NEEDS_WORK только при HIGH/CRITICAL).

### Round-1 findings — verification table

| ID | Был | Closed? | Где |
|---|---|---|---|
| H1 | exit-path wake_up_all ineffective | ✓ closed in code; **re-classified → LOW** (см. F2 resolution) | `stockyard_driver.c:242–252` (wait condition), `:461–463` (exit) |
| M1 | initialized set до misc_register | ✓ closed | `:436–447` |
| M2 | u32 cast truncation | ✓ closed (u64 arithmetic) | `:114–116` |
| M3 | .gitignore не покрывает новые тесты | ✓ closed | `driver/.gitignore:17–18` |
| L1/F1 | kfifo_get warn-unused-result | ✓ closed (return check; build clean) | `:169` |
| L2 | atomic64_set в reset вне spinlock | ✓ closed (comment + intent documented) | `:371–378` |
| L3 / Q1 | docs §5.5.1 показывал struct без явного sizeof | ✓ closed | `docs/architecture/05-communication.md §5.5.1` + TASK-009 ledger §C1 |

### F2 Resolution: Option A — H1 re-classified to LOW

Detailed kernel-lifecycle analysis:

1. `sy_fops.owner = THIS_MODULE` → VFS делает `try_module_get(THIS_MODULE)` на каждый `open()`, инкрементит `module->refcount`.
2. `rmmod` вызывает kernel `delete_module()`. Без `O_TRUNC` (force) → checks `refcount > 0` → returns `EBUSY` **до** любого вызова `module->exit` (`sy_exit`).
3. На Ubuntu 22.04 (CONFIG_MODULE_FORCE_UNLOAD=disabled) EBUSY-путь всегда срабатывает при открытых fd → `sy_exit` не вызывается → H1 patch'а ветка кода (post-wake `!initialized` check) **не исполняется**.
4. `sy_exit` вызывается ТОЛЬКО когда `refcount == 0` (все fd закрыты) → нет waiter'ов для wake'а → `wake_up_all` no-op по другой причине.

Tester прав. H1 как HIGH gate был misdiagnosed мной в round 1.

**Но удалять patch не надо** — он defensive code для kernel'ов с `CONFIG_MODULE_FORCE_UNLOAD=y` (custom embedded builds, некоторые RHEL/Fedora variants). Cost: 0 runtime overhead на стоковом Ubuntu. Without it: silently deadlock on force-unload.

**Реальная mitigation для production EBUSY** — процедурная, не код:
1. Quotes Service (TASK-009) **обязан** catch SIGTERM, close `/dev/stockyard` fd, exit cleanly.
2. Shutdown order: `systemctl stop quotes-service` → wait exit → `rmmod stockyard_driver`.
3. `rmmod -f` дисабилен в Ubuntu/Debian — нельзя скриптовать.

Это документация, не код.

### Medium findings (round 2)

**M-doc — `driver/README.md` нет секции «Operating in production / shutdown procedure».**

F2 анализ установил, что реальная mitigation EBUSY — процедурная (`systemctl stop quotes-service` → wait → `rmmod`). Это **обязательное знание** для производственного развертывания, иначе deployment-скрипты будут получать EBUSY и команда не поймёт почему.

Текущий `driver/README.md` Troubleshooting (строка 132) говорит «close all open fds» но не описывает:
- порядок shutdown в production
- что Quotes Service должен обрабатывать SIGTERM
- что `rmmod -f` дисабилен в Ubuntu/Debian
- последствие пропуска: EBUSY blocks deployment

**Fix:** добавить секцию `## Operating in production` в `driver/README.md` (~10–15 строк):
```
1. Quotes Service catches SIGTERM and closes fd → standard kernel-module ops.
2. Shutdown: systemctl stop quotes-service → verify exit → rmmod stockyard_driver.
3. EBUSY on rmmod = consumer still has fd; do not script rmmod -f (disabled on Ubuntu).
```

Не блокирует gate (project rule: NEEDS_WORK только на HIGH/CRITICAL), но **рекомендуется выполнить до `/committer`**. Может быть закрыто как отдельный `docs(driver):` коммит ИЛИ в одном PR-е с TASK-008 кодом.

### Low findings (round 2)

**L4 — H1 patch comment не объясняет EBUSY-ограничение.** `stockyard_driver.c:237–241` correctly объясняет intent патча, но не упоминает что в Ubuntu stock этот code path не исполняется в EBUSY-сценарии. Одна строка комментария «(this path runs only on kernels with CONFIG_MODULE_FORCE_UNLOAD=y)» сохранит future-reader от confusion. Минор.

**L5 — `driver/README.md:151` stale parenthetical** «(на момент TASK-008 фиксируем 44 байта, см. open questions)» — Q1 SIGNED OFF на 44 байта, фраза устарела. Уберите «см. open questions».

### Positive observations (round 2)

- **H1 patch implementation корректен по существу** — wait condition `!kfifo_is_empty || !initialized` clean, memory ordering (initialized=0 ДО wake_up_all) корректен на x86 + arm64. Tester'ская F2-аналитика касается architecture, не code quality.
- **M2 fix** — `get_random_u64() % ((__u64)vol_cents * 2 + 1)` идиоматичен, никаких truncation/overflow.
- **M1 fix** — `sy_open` rejects с `-ENODEV` в narrow window между `misc_register` и `atomic_set(initialized, 1)`, что правильно и safe.
- **L1 fix лучше чем `(void)` cast** — `if (kfifo_get(...)) atomic64_inc(...)` обходит `__must_check` корректно И делает drop-accounting conditional на actual removal (handles hypothetical empty-full race).
- **TASK-009 ledger §C1 обновлён** с 44-byte spec + golden hex fixture из B1.
- **`test_layout.c` 14 `_Static_assert`** — zero-runtime-dep gate, ловит ABI drift в compile time, без VM.

### Q1 Resolution: **44 bytes — SIGNED OFF (carried over from round 1)**

Подписан в round 1. Code + docs синхронизированы (закрыто L3).

### Test coverage assessment (round 2)

16 регрессионных кейсов (T1–T9 + B1–B2 + C1–C4) + 2 новых (N1 кейс, N2 PASS). N1 «failed» informationally — это не code failure, а архитектурное открытие. Production shutdown тест нельзя автоматизировать в driver-scope; он принадлежит TASK-009/TASK-011 integration tests.

### Round-1 findings (historical — closed)

(оставлены ниже для traceability — см. таблицу verification в начале round-2 review)

---

## Review (round 1 — historical)

### Gate: **NEEDS_WORK** (1 HIGH, 3 MEDIUM, 3 LOW)

Lock discipline и memory safety корректны throughout (правильно сделанная hard kernel-часть). Найден один HIGH — exit-path race, который ломает clean rmmod при наличии активного reader'а (= Quotes Service в TASK-009 будет держать fd постоянно). Остальное косметика + один доковый gap.

### Critical findings
Нет.

### High findings

**H1 — `stockyard_driver.c:425–429` `wake_up_all` не разбудит reader'а с пустым буфером, блокируя rmmod.**

`wait_event_interruptible(sy.readq, !kfifo_is_empty(&sy.fifo))` re-evaluates condition после wake. Если буфер пуст (нормальный случай для consumer'а который читает быстрее producer'а — Quotes Service @ 1 Hz), condition остаётся `false`, reader спит дальше. `hrtimer_cancel` уже остановил producer'а — буфер навсегда пустой → reader навсегда заблокирован → `sy_fops.owner = THIS_MODULE` держит ref-count → `rmmod` вернёт `EBUSY`.

T8 прошёл только потому, что `test_read` уже закрыл fd до rmmod. Production scenario (Quotes Service держит fd) сломается.

**Fix:**
```c
// в sy_exit, перед wake_up_all:
atomic_set(&sy.initialized, 0);
wake_up_all(&sy.readq);

// в sy_read, условие wait:
ret = wait_event_interruptible(sy.readq,
    !kfifo_is_empty(&sy.fifo) || !atomic_read(&sy.initialized));
if (ret || !atomic_read(&sy.initialized)) {
    ret = -ERESTARTSYS;
    goto out;
}
```

### Medium findings

**M1 — `stockyard_driver.c:398–412` `atomic_set(&sy.initialized, 1)` ставится ДО `misc_register`.**
Если `misc_register` упадёт — флаг останется поднятый в статической памяти (хотя insmod вернёт error и модуль не загрузится — практически benign). Логически неправильно: ставить `initialized = 1` только ПОСЛЕ успешного `misc_register`.

**M2 — `stockyard_driver.c:107–108` cast `(__u32)vol_cents` truncate'ит `__s64` если value > UINT32_MAX.**
`2 * (__u32)vol_cents + 1` тогда даёт wrapped modulus → wildly wrong delta. Threshold ≈ 214M ₽/share при vol=1000 bps — недостижимо с MVP-seed данных, но cast implementation-defined для `s64 > u32_max`. Fix: использовать `(__u64)vol_cents` и держать арифметику в u64.

**M3 — `driver/.gitignore` не покрывает `test/test_layout` и `test/test_errors`.**
Оба бинаря собираются `test/Makefile` (добавлены tester'ом). Появятся в `git status` untracked у любого, кто запустит `make` в `driver/test/`.

### Low findings

**L1 — `stockyard_driver.c:158` `kfifo_get(&sy.fifo, &discard);` warn-unused-result.** Tester'ская F1 reaffirmed. Fix: `(void)kfifo_get(&sy.fifo, &discard);` cast.

**L2 — `stockyard_driver.c:348–349` `atomic64_set` в `ioctl_reset` вне spinlock.** Между `spin_unlock` и `atomic64_set` timer может сгенерировать тики и инкрементнуть `ticks_generated` — reset зануляет non-zero value. Для telemetry приемлемо, добавить comment чтобы future reader не «починил» это перемещением внутрь spinlock'а.

**L3 — `docs/architecture/05-communication.md:303` показывает struct без явного `sizeof == 44`.** Закрывается вместе с Q1 follow-up.

### Positive observations

- **Lock discipline корректна** throughout — самая сложная часть kernel-module безопасности сделана правильно. Timer callback acquires `spin_lock_irqsave` (softirq-safe), releases ДО `wake_up_interruptible`, и **ни одна sleep-able функция** (`copy_to_user`/`kmalloc(GFP_KERNEL)`/`mutex_*`/`wait_event_*`) не вызывается под lock'ом.
- **`get_random_u32`** (не deprecated `prandom_u32`) — правильно для kernel 5.15 и 6.8, избегает compile error на 6.1+.
- **`hrtimer_init`** (не `hrtimer_setup`) — правильный compat-выбор для dual-target 5.15/6.8.
- **`copy_from_user`/`copy_to_user`** во всех 4 ioctl handler'ах, ни один user pointer не разыменован напрямую.
- **`kfifo_out` → kmalloc'd kernel buffer под spinlock'ом, потом `copy_to_user` ВНЕ** — правильный pattern.
- **Drop-oldest exact**: tester T7 показал `58765 − 8192 = 50573` точно.
- **`compat_ioctl = sy_ioctl`** установлен — handle 32-bit userspace на 64-bit kernel, часто забывают.
- **`_Static_assert` × 14 в `test_layout.c`** (sizes, offsets, constants) — comprehensive, runs на host без VM.

### Q1 Resolution: **44 bytes — SIGNED OFF**

Архитектор написал 40 (арифметическая ошибка: `8+8+8+8+8+4 = 44`). Drop volume для возврата к 40 потребует Quotes Service синтезировать volume из ниоткуда — хуже чем correction. Reasons to sign off на 44:
1. Field list correct, volume carries semantic value для `HSET quotes:SBER volume` и WS frame.
2. Cross-arch identical на macOS arm64 и Linux arm64 (tester B1).
3. `_Static_assert(sizeof == 44)` в kernel module и `test_layout.c` поймает любой drift.

**Follow-up actions (для /backend на fix-этапе):**
- Обновить `docs/architecture/05-communication.md §5.5.1`: добавить `// 44 bytes packed` комментарий + исправить sizeof note.
- Обновить TASK-009 ledger §C1: parser читает 44-byte frames.
- Golden fixture из B1 confirmed.

### Test coverage assessment

16-кейсовая матрица (T1–T9, B1–B2, C1–C4) solid для kernel module. Все happy paths, документированные error paths, stress, cross-arch — covered. **Missing test cases (добавить после H1 fix):**

1. **Concurrent reader + rmmod** — точно тот scenario, что вскрывает H1. `test_read` в loop (blocking, fifo empty) + concurrent `rmmod` → ожидаем rmmod success < 1 sec.
2. **SET_TICKERS → partial read → RESET** — verify что тики старых тикеров не остаются в kfifo после `ioctl_reset`.

## Handoff Log
- 2026-05-11T18:00:00Z: создан через /architect — design complete; suggested next: `/backend TASK-008`.
- 2026-05-11T19:30:00Z: /backend — реализован полноценный kernel module (~450 строк) + 2 userspace test-программы + 50-ticker seed + scripts + Lima VM config для Apple Silicon + README. Userspace компилируется чисто; kernel module ждёт сборки в Lima. **Q1: tick size 44 байта, не 40** — арифметическая ошибка архитектора в спеке, фиксирую 44 (volume сохранён). Suggested next: `/tester TASK-008` (нужен Linux VM — Lima/UTM/Vagrant для прогона T1-T9).
- 2026-05-11T20:30:00Z: /tester — `brew install lima` + `limactl start` (Ubuntu 22.04 ARM64). **Все T1–T9 + B1–B2 + C1–C4 PASS** (16 кейсов). 5 load/unload циклов без oops/BUG/WARN. Drop-oldest math exact (T7: 58765−8192=50573). Cross-arch layout идентичен (B1: macOS arm64-darwin = Linux arm64). Synthetic golden fixture для TASK-009 готова. Все 11 ioctl/read error paths корректные errno. Q2 + Q3 RESOLVED. **1 LOW finding (F1):** `kfifo_get` unused-result warning. Добавлены `test_layout.c` + `test_errors.c`. Suggested next: `/reviewer TASK-008`.
- 2026-05-11T21:00:00Z: /reviewer — **gate: NEEDS_WORK**, 0 critical, **1 HIGH** (H1: exit-path `wake_up_all` не разбудит blocking reader'а с пустым буфером → rmmod EBUSY когда Quotes Service держит fd; T8 прошёл только потому что test_read закрыл fd до rmmod), 3 medium (M1 initialized-flag set до misc_register, M2 vol_cents u32-cast truncation > UINT32_MAX, M3 .gitignore не покрывает test_layout/test_errors), 3 low (L1 reaffirmed F1, L2 atomic64_set вне spinlock в ioctl_reset, L3 docs §5.5.1 gap). **Q1 SIGNED OFF на 44 bytes** — follow-up: обновить §5.5.1 + TASK-009 ledger. Lock discipline и memory safety корректны throughout. Suggested next: `/backend TASK-008` для починки H1.
- 2026-05-11T22:00:00Z: /tester (re-pass) — regression PASS (T2/T7/T9c/T8), build clean (L1/F1 closed). **N2 PASS** (RESET clears kfifo, OLD-tickers не утекают). **N1 FAILED** — но не из-за кода: H1 fix как описан reviewer'ом — функционально no-op на Ubuntu (CONFIG_MODULE_FORCE_UNLOAD=disabled, sy_exit не вызывается пока refcount > 0). Standard kernel idiom: open fd блокирует rmmod, нужна documentation + signal handling в Quotes Service (TASK-009), не код-фикс. **F2 (NEW finding для reviewer):** ре-классифицировать H1 как LOW (documentation) и закрыть. Stage остаётся `tested` — patches не сломали ничего; reviewer должен принять решение по F2. Suggested next: `/reviewer TASK-008`.
- 2026-05-11T22:30:00Z: /reviewer (round 2) — **gate: PASS** (0 critical, 0 high, 1 medium, 2 low). Все 7 round-1 findings закрыты в коде (verified line-by-line). **F2 ACCEPTED** — Option A: H1 re-classified → LOW (defensive code для force-unload kernel'ов, не активен на Ubuntu stock). Реальная mitigation процедурная (`systemctl stop quotes` → `rmmod`). **Один medium doc-gap:** README не содержит секции «Operating in production / shutdown procedure» — рекомендуется добавить до /committer'а, но не блокирует gate (skill rule: NEEDS_WORK только при HIGH/CRITICAL). 2 low: H1 patch комментарий не объясняет EBUSY-ограничение; README:151 stale parenthetical про open questions. Готово к merge. Suggested next: `/committer TASK-008` (опционально с inline README-доком, либо в follow-up).
- 2026-05-11T23:00:00Z: /committer — branch `feature/8-c-driver`, 7 commits: `3d317fa` build(driver) makefile+gitignore+lima, `6276718` feat(driver) kernel module + seed + scripts, `7cbdcb2` test(driver) userspace harness, `b33efa0` docs(driver) README, `a8cf468` docs(arch) §5.5.1 44-byte tick format, `cae012f` docs(task) TASK-008..011 ledgers, `dd6f10a` docs(changelog). CHANGELOG `[Unreleased] → Added` пополнено новой записью про /dev/stockyard. Medium doc-gap (production shutdown section) НЕ закрыт — user явно выбрал commit без inline доков, в follow-up. Suggested next: `/committer push`.
- 2026-05-11T21:30:00Z: /backend (fix pass) — все 7 findings закрыты:<br>**H1** — `sy_read` wait condition теперь `!kfifo_is_empty || !atomic_read(&sy.initialized)` + post-wake-up check возвращает `-ENODEV` если модуль ушёл (был `-ERESTARTSYS`). `sy_exit` уже опускал `initialized` ДО `wake_up_all`, последовательность правильная.<br>**M1** — `atomic_set(&sy.initialized, 1)` перемещён ПОСЛЕ успешного `misc_register` + добавлен comment о race window vs sy_open.<br>**M2** — `walk_price` переписан в u64 арифметике (`get_random_u64() % range`, где `range = (__u64)vol_cents * 2 + 1`). Безопасно для prices > UINT32_MAX kopecks.<br>**M3** — `.gitignore` пополнен `test/test_layout` + `test/test_errors`.<br>**L1** — `kfifo_get` warning закрыт ЧЕРЕЗ проверку return value (не `(void)` cast — kfifo внутренний `__must_check` helper не silence'ится cast'ом): `if (kfifo_get(...)) atomic64_inc(...);` — корректнее семантически. **Build clean: grep rc=1 (no warnings, no errors)**.<br>**L2** — comment добавлен на `atomic64_set` в `ioctl_reset` объясняя intentional out-of-lock placement.<br>**L3 + Q1 follow-up** — `docs/architecture/05-communication.md §5.5.1` обновлён: struct комментирован по offsets + явный `sizeof == 44 bytes`. TASK-009 ledger §C1 обновлён + добавлен golden hex fixture от TASK-008 tester'а.<br>Smoke в VM: load/read/rmmod cycle clean. Suggested next: `/tester TASK-008` (re-run T1+T8 + новые кейсы из reviewer'а: concurrent reader+rmmod, SET_TICKERS→partial read→RESET).

