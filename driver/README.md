# Stockyard Driver — `/dev/stockyard`

Linux kernel module, имитирует биржу: генерирует поток packed-tick'ов
по таймеру, отдаёт через character device, конфигурируется ioctl'ами.
Consumer — Quotes Service (Go, TASK-009).

```
   userspace        kernel
   ──────────  ▶   ────────
                   hrtimer ──┐
                             ▼
   read(fd) ◀── kfifo ◀── tick generator (random walk)
   ioctl()  ───────────▶ ticker config + rate
```

## Файлы

| Файл | Что в нём |
|---|---|
| `stockyard_driver.h` | Public ABI: `struct stockyard_tick` (44 байта packed), ioctl-номера, payloads. Включается и ядром, и userspace. |
| `stockyard_driver.c` | Сам модуль ~450 строк. misc_register, kfifo, hrtimer, spinlock, ioctl. |
| `Makefile` | kbuild out-of-tree, цель `modules` против `/lib/modules/$(uname -r)/build`. |
| `test/test_read.c` | Открывает `/dev/stockyard`, читает N тиков, печатает hex + decoded. |
| `test/test_ioctl.c` | Тренирует все ioctl: `stats`, `rate <hz>`, `reset`, `load <file>`. |
| `seed/tickers.txt` | 50 MOEX-тикеров + стартовые цены в копейках + волатильность в bps. |
| `scripts/load.sh` | Идемпотентная host-side загрузка: `insmod` + chgrp + seed. Запускать как root. |
| `scripts/unload.sh` | `rmmod`. |
| `lima.yaml` | Lima VM config для macOS Apple Silicon (или Intel) разработчиков. |

## Поток данных

Один tick = 44 байта, packed, little-endian:

```
offset  size  field           type
  0      8    ticker[8]       null-padded ASCII
  8      8    ts_ns           uint64, CLOCK_MONOTONIC
 16      8    bid_cents       int64
 24      8    ask_cents       int64
 32      8    last_cents      int64
 40      4    volume          uint32
```

`read()` отдаёт только целые тики (count кратен 44). Блокирующий по
умолчанию, неблокирующий через `O_NONBLOCK` (вернёт `-EAGAIN` при
пустом буфере). `poll`/`select` через POLLIN поддерживаются.

## Сборка и загрузка

### Linux x86-64 / arm64 (нативно)

```bash
# build
make                                # → stockyard_driver.ko
( cd test && make )                 # → test/test_read, test/test_ioctl

# load + seed full 50 tickers + chgrp
sudo ./scripts/load.sh

# smoke
./test/test_read 5                  # читает 5 тиков, печатает decoded
./test/test_ioctl stats             # текущая статистика
./test/test_ioctl rate 10           # 10 Hz/ticker
./test/test_ioctl reset             # default 5 тикеров, 1 Hz, stats=0

# unload
sudo ./scripts/unload.sh
```

`/dev/stockyard` создаётся автоматически через `misc_register` —
`mknod` руками не нужен.

### macOS Apple Silicon (через Lima)

Kernel module собирается и тестируется внутри Linux VM. На Apple
Silicon Lima запускает **ARM64 Ubuntu 22.04 LTS** (нативный для хоста,
без QEMU-эмуляции — быстро). Драйвер написан arch-neutral, поэтому
тот же `.c` собирается и грузится одинаково.

```bash
# хост: установка lima
brew install lima

# создать и стартовать VM (первый раз — установит build-essential,
# linux-headers, склонирует репозиторий через bind-mount)
limactl start --name=stockyard-dev driver/lima.yaml

# войти в VM
limactl shell stockyard-dev

# внутри VM (репозиторий замонтирован по тому же пути, что на хосте)
cd /Users/$(whoami)/Projects/Stockyard/driver
make
( cd test && make )
sudo ./scripts/load.sh
./test/test_read 5

# выход + остановка / удаление VM
exit
limactl stop  stockyard-dev
limactl delete stockyard-dev    # при необходимости
```

Альтернативы Lima:
- **UTM** (GUI, удобно), та же ARM64 Ubuntu cloud image.
- **OrbStack** — быстрее Lima, но коммерческий.
- **Vagrant + libvirt/Parallels** — если уже привык.

### Linux под Intel или WSL2

Всё то же что и в нативном Linux разделе. WSL2 имеет свой kernel —
устанавливай `linux-headers-$(uname -r)` пакет, доступный в дистрибутиве.

## ioctl reference

| Команда | Direction | Payload | Эффект |
|---|---|---|---|
| `STOCKYARD_IOC_SET_TICKERS` | W | `struct stockyard_tickers_cfg` | Замещает текущий набор тикеров. count ≤ 64, vol_bps 1..1000, price ≥ 1. |
| `STOCKYARD_IOC_SET_RATE_HZ` | W | `uint32_t` | Устанавливает частоту генерации (1..1000 Hz/ticker). Применяется к следующему callback'у. |
| `STOCKYARD_IOC_GET_STATS` | R | `struct stockyard_stats` | Возвращает счётчики + текущую конфигурацию. |
| `STOCKYARD_IOC_RESET` | — | — | Сброс: дефолтные 5 тикеров, 1 Hz, очистка очереди и счётчиков. |

## Troubleshooting

| Симптом | Причина / фикс |
|---|---|
| `insmod: ERROR: could not insert module: Operation not permitted` | Нужны root-привилегии: `sudo insmod ...` |
| `insmod: ERROR: ... Invalid module format` | Kernel headers не совпадают с running kernel. `apt install linux-headers-$(uname -r)` и пересобрать `make`. |
| `/dev/stockyard: Permission denied` для не-root | Не запущен `scripts/load.sh` — он создаёт группу `stockyard` и chgrp'ит устройство. Либо добавь пользователя в группу и перелогинься: `sudo usermod -aG stockyard $USER`. |
| `read(): Invalid argument` | `count` не кратен 44. Используй `STOCKYARD_TICK_SIZE` из header'а. |
| `lsmod` показывает модуль, но `cat /dev/stockyard` ничего не отдаёт | Проверь `dmesg | tail` — модуль логирует init, ошибки, exit. |
| `rmmod: Module ... is in use` | Закрой все открытые fd на `/dev/stockyard` (test_read, Quotes Service). |
| После `rmmod` в `dmesg` warning о leak | Bug в драйвере — открой issue. Не должен случаться при чистом `wait_event_interruptible` flow. |

## Известные ограничения (TASK-008 scope)

- Single-instance: один процесс на чтение реально-смотрит на один и тот
  же FIFO. Multi-consumer не реализован — для MVP не нужен (Quotes
  Service ровно один).
- Random walk детерминирован только в рамках одного uptime; после
  `rmmod`/`insmod` цены сбрасываются на дефолт.
- ts_ns — `CLOCK_MONOTONIC` (от boot), не wall-clock. Quotes Service
  переводит в ISO-8601 на основе current wallclock + dt_ns.
- 5 тикеров по умолчанию; полный набор 50 загружается ioctl'ом из
  `seed/tickers.txt`. Это упрощает unit-тестирование на маленьком
  наборе.

## Связанные документы

- [Task ledger TASK-008](../.claude/tasks/TASK-008-c-driver.md)
- [docs/architecture/05-communication.md §5.5.1](../docs/architecture/05-communication.md) — wire format (на момент TASK-008 фиксируем 44 байта, см. open questions)
- [docs/architecture/adr/ADR-010-character-device.md](../docs/architecture/adr/) — выбор character device + ioctl (создаётся reviewer'ом)
