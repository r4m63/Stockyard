/* SPDX-License-Identifier: GPL-2.0 */
/*
 * stockyard_driver.h — public ABI between the stockyard kernel module
 * and userspace consumers (Quotes Service, test programs).
 *
 * Shared between kernel and userspace.  Wire format is packed,
 * little-endian.  Every consumer (Go parser, C test tool) keys on the
 * field offsets below — do not reorder or resize without bumping a
 * version field.
 */
#ifndef STOCKYARD_DRIVER_H
#define STOCKYARD_DRIVER_H

#ifdef __KERNEL__
#  include <linux/types.h>
#  include <linux/ioctl.h>
#else
#  include <stdint.h>
#  include <sys/ioctl.h>
   typedef uint32_t __u32;
   typedef uint64_t __u64;
   typedef int64_t  __s64;
#endif

#define STOCKYARD_DEVICE_NAME  "stockyard"
#define STOCKYARD_TICKER_LEN   8
#define STOCKYARD_MAX_TICKERS  64
#define STOCKYARD_RING_SIZE    8192   /* power of two — required by kfifo  */

/*
 * Tick layout (44 bytes packed):
 *   offset  size  field
 *      0     8    ticker[8]      null-padded ASCII
 *      8     8    ts_ns          uint64, CLOCK_MONOTONIC
 *     16     8    bid_cents      int64
 *     24     8    ask_cents      int64
 *     32     8    last_cents     int64
 *     40     4    volume         uint32
 *
 * NOTE: the C1 contract in TASK-008/009 ledgers states "40 bytes".
 * That was an arithmetic slip — the field list adds up to 44 bytes,
 * not 40.  We pick 44 here so volume can be carried natively; the
 * ledgers and 05-communication.md §5.5.1 are updated by reviewer/
 * TASK-011 to match.
 */
struct stockyard_tick {
	char  ticker[STOCKYARD_TICKER_LEN];
	__u64 ts_ns;
	__s64 bid_cents;
	__s64 ask_cents;
	__s64 last_cents;
	__u32 volume;
} __attribute__((packed));

#define STOCKYARD_TICK_SIZE  44

/* ioctl payloads ---------------------------------------------------- */

struct stockyard_tickers_cfg {
	__u32 count;                                          /* <= 64       */
	char  tickers[STOCKYARD_MAX_TICKERS][STOCKYARD_TICKER_LEN];
	__s64 initial_prices_cents[STOCKYARD_MAX_TICKERS];
	__u32 volatility_bps[STOCKYARD_MAX_TICKERS];          /* 1..1000 bp  */
};

struct stockyard_stats {
	__u64 ticks_generated;
	__u64 ticks_dropped_buffer_full;
	__u32 current_rate_hz;
	__u32 configured_tickers;
};

#define STOCKYARD_IOC_MAGIC        'S'
#define STOCKYARD_IOC_SET_TICKERS  _IOW(STOCKYARD_IOC_MAGIC, 1, struct stockyard_tickers_cfg)
#define STOCKYARD_IOC_SET_RATE_HZ  _IOW(STOCKYARD_IOC_MAGIC, 2, __u32)
#define STOCKYARD_IOC_GET_STATS    _IOR(STOCKYARD_IOC_MAGIC, 3, struct stockyard_stats)
#define STOCKYARD_IOC_RESET        _IO (STOCKYARD_IOC_MAGIC, 4)

#endif /* STOCKYARD_DRIVER_H */
