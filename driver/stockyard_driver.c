// SPDX-License-Identifier: GPL-2.0
/*
 * Stockyard exchange tick simulator — Linux kernel module.
 *
 * Provides /dev/stockyard, a misc character device that yields a stream
 * of packed `struct stockyard_tick` records at a configurable rate via
 * a kernel-side random walk.  Userspace consumes via blocking read();
 * configuration runs through ioctl.
 *
 * Concurrency model
 * -----------------
 *   - Producer:  hrtimer callback (softirq context, cannot sleep).
 *                Generates one tick per configured ticker per period,
 *                pushes into kfifo, wakes any reader.
 *   - Consumer:  read() in process context.  Drains kfifo under spinlock
 *                into a kernel buffer, then copies to user without lock.
 *   - Config:    ioctl in process context.  Updates ticker state and rate
 *                under the same spinlock.
 *   - Stats:     atomic64_t counters, no lock needed.
 *
 * The single spinlock `sy.lock` covers both the kfifo and the in-kernel
 * ticker state because the timer touches them together.  Held briefly
 * (no copy_to_user inside).  Acquired with _irqsave because the timer
 * runs in softirq context.
 */
#include <linux/module.h>
#include <linux/init.h>
#include <linux/kernel.h>
#include <linux/fs.h>
#include <linux/miscdevice.h>
#include <linux/uaccess.h>
#include <linux/kfifo.h>
#include <linux/spinlock.h>
#include <linux/wait.h>
#include <linux/poll.h>
#include <linux/hrtimer.h>
#include <linux/ktime.h>
#include <linux/random.h>
#include <linux/sched.h>
#include <linux/string.h>
#include <linux/slab.h>
#include <linux/atomic.h>
#include <linux/version.h>

#include "stockyard_driver.h"

#define DRV_NAME             "stockyard"
#define DEFAULT_RATE_HZ      1U
#define MIN_RATE_HZ          1U
#define MAX_RATE_HZ          1000U
#define MIN_PRICE_CENTS      1LL
#define DEFAULT_SPREAD_BPS   20    /* bid/ask spread around last (0.20 %) */
#define READ_BATCH_MAX       128   /* cap a single read() ~ 5.5 KiB       */

/* Compile-time sanity on the wire format. */
_Static_assert(sizeof(struct stockyard_tick) == STOCKYARD_TICK_SIZE,
	       "stockyard_tick layout drift");

struct ticker_state {
	char  ticker[STOCKYARD_TICKER_LEN];
	__s64 last_cents;
	__u32 volatility_bps;
};

struct stockyard_dev {
	DECLARE_KFIFO(fifo, struct stockyard_tick, STOCKYARD_RING_SIZE);
	spinlock_t          lock;
	wait_queue_head_t   readq;

	struct hrtimer      timer;
	__u32               rate_hz;

	struct ticker_state tickers[STOCKYARD_MAX_TICKERS];
	__u32               ticker_count;

	atomic64_t          ticks_generated;
	atomic64_t          ticks_dropped;
	atomic_t            initialized;
};

static struct stockyard_dev sy;

/* Default seed.  Enough to smoke-test without an ioctl; userspace pushes
 * the full 50-ticker MOEX set via SET_TICKERS from seed/tickers.txt.  */
static const struct ticker_state default_tickers[] = {
	{ "SBER", 28500,  100 },
	{ "GAZP", 16500,  100 },
	{ "LKOH", 670000, 100 },
	{ "ROSN", 45000,  100 },
	{ "YNDX", 280000, 100 },
};
#define DEFAULT_TICKER_COUNT  ARRAY_SIZE(default_tickers)

/* ===== Random walk ================================================== *
 * Δ ~ uniform[-vol_cents .. +vol_cents],
 * vol_cents = old_price * vol_bps / 10000, lower-clamped to 1.
 */
static __s64 walk_price(__s64 old, __u32 vol_bps)
{
	__s64 vol_cents = div_s64(old * (__s64)vol_bps, 10000);
	__u64 range;
	__u64 r;
	__s64 delta;

	if (vol_cents < 1)
		vol_cents = 1;

	/*
	 * Keep the modulus in u64 so prices > UINT32_MAX kopecks
	 * (~21M ₽ if vol_bps is at the cap) still produce a usable
	 * range without integer wrap.  get_random_u64 is softirq-safe
	 * and available on every kernel we target.
	 */
	range = (__u64)vol_cents * 2 + 1;
	r = get_random_u64() % range;
	delta = (__s64)r - vol_cents;
	return max_t(__s64, MIN_PRICE_CENTS, old + delta);
}

static __u32 random_volume(void)
{
	return (get_random_u32() % 9001) + 1000; /* 1000..10000 */
}

static inline ktime_t period_from_rate(__u32 rate_hz)
{
	if (rate_hz == 0)
		rate_hz = DEFAULT_RATE_HZ;
	return ns_to_ktime((__u64)NSEC_PER_SEC / rate_hz);
}

/* ===== hrtimer producer ============================================= *
 * Runs in softirq context: cannot sleep, cannot block-take mutexes, must
 * use spin_lock_irqsave.  Walks every configured ticker once per call.
 */
static enum hrtimer_restart sy_timer_cb(struct hrtimer *t)
{
	unsigned long flags;
	__u64 now_ns = ktime_get_ns();
	__u32 i, count;
	__u32 rate;

	spin_lock_irqsave(&sy.lock, flags);
	count = sy.ticker_count;
	for (i = 0; i < count; i++) {
		struct ticker_state *ts = &sy.tickers[i];
		__s64 new_last, spread, half;
		struct stockyard_tick tick;

		new_last = walk_price(ts->last_cents, ts->volatility_bps);
		spread   = div_s64(new_last * DEFAULT_SPREAD_BPS, 10000);
		if (spread < 2)
			spread = 2;
		half = spread / 2;

		memcpy(tick.ticker, ts->ticker, STOCKYARD_TICKER_LEN);
		tick.ts_ns      = now_ns;
		tick.bid_cents  = max_t(__s64, MIN_PRICE_CENTS, new_last - half);
		tick.ask_cents  = new_last + half;
		tick.last_cents = new_last;
		tick.volume     = random_volume();

		/* Drop oldest on overflow.  ADR-001 — at-most-once.
		 * Check kfifo_get's return so the __must_check attribute
		 * is satisfied and we only account a drop when something
		 * was actually removed. */
		if (kfifo_is_full(&sy.fifo)) {
			struct stockyard_tick discard;
			if (kfifo_get(&sy.fifo, &discard))
				atomic64_inc(&sy.ticks_dropped);
		}
		if (kfifo_put(&sy.fifo, tick)) {
			atomic64_inc(&sy.ticks_generated);
			ts->last_cents = new_last;
		}
	}
	rate = sy.rate_hz;
	spin_unlock_irqrestore(&sy.lock, flags);

	if (count > 0)
		wake_up_interruptible(&sy.readq);

	hrtimer_forward_now(t, period_from_rate(rate));
	return HRTIMER_RESTART;
}

/* ===== file_operations ============================================== */

static int sy_open(struct inode *inode, struct file *file)
{
	return atomic_read(&sy.initialized) ? 0 : -ENODEV;
}

static int sy_release(struct inode *inode, struct file *file)
{
	return 0;
}

static ssize_t sy_read(struct file *file, char __user *buf,
		       size_t count, loff_t *ppos)
{
	struct stockyard_tick *kbuf;
	unsigned long flags;
	size_t max_ticks;
	unsigned int copied;
	size_t bytes;
	int ret;

	if (count == 0)
		return 0;
	if (count % STOCKYARD_TICK_SIZE)
		return -EINVAL;

	max_ticks = count / STOCKYARD_TICK_SIZE;
	if (max_ticks > READ_BATCH_MAX)
		max_ticks = READ_BATCH_MAX;

	kbuf = kmalloc_array(max_ticks, sizeof(*kbuf), GFP_KERNEL);
	if (!kbuf)
		return -ENOMEM;

	for (;;) {
		spin_lock_irqsave(&sy.lock, flags);
		copied = kfifo_out(&sy.fifo, kbuf, max_ticks);
		spin_unlock_irqrestore(&sy.lock, flags);

		if (copied > 0)
			break;

		if (file->f_flags & O_NONBLOCK) {
			ret = -EAGAIN;
			goto out;
		}

		/*
		 * Wake on data OR on module unload.  The second predicate
		 * lets sy_exit unblock readers whose fifo never fills again,
		 * so rmmod can drop the THIS_MODULE refcount.  Without it,
		 * a consumer that holds /dev/stockyard open with an empty
		 * fifo would wedge rmmod with -EBUSY (reviewer H1).
		 */
		ret = wait_event_interruptible(sy.readq,
			!kfifo_is_empty(&sy.fifo) ||
			!atomic_read(&sy.initialized));
		if (ret) {
			ret = -ERESTARTSYS;
			goto out;
		}
		if (!atomic_read(&sy.initialized)) {
			ret = -ENODEV;
			goto out;
		}
	}

	bytes = (size_t)copied * STOCKYARD_TICK_SIZE;
	if (copy_to_user(buf, kbuf, bytes)) {
		ret = -EFAULT;
		goto out;
	}
	ret = (ssize_t)bytes;
out:
	kfree(kbuf);
	return ret;
}

static __poll_t sy_poll(struct file *file, struct poll_table_struct *wait)
{
	__poll_t mask = 0;

	poll_wait(file, &sy.readq, wait);
	if (!kfifo_is_empty(&sy.fifo))
		mask |= EPOLLIN | EPOLLRDNORM;
	return mask;
}

/* ----- ioctl handlers ----- */

static long ioctl_set_tickers(unsigned long arg)
{
	struct stockyard_tickers_cfg *cfg;
	unsigned long flags;
	long ret = 0;
	__u32 i;

	cfg = kmalloc(sizeof(*cfg), GFP_KERNEL);
	if (!cfg)
		return -ENOMEM;

	if (copy_from_user(cfg, (void __user *)arg, sizeof(*cfg))) {
		ret = -EFAULT;
		goto out;
	}
	if (cfg->count == 0 || cfg->count > STOCKYARD_MAX_TICKERS) {
		ret = -EINVAL;
		goto out;
	}
	for (i = 0; i < cfg->count; i++) {
		if (cfg->initial_prices_cents[i] < MIN_PRICE_CENTS) {
			ret = -EINVAL;
			goto out;
		}
		if (cfg->volatility_bps[i] < 1 ||
		    cfg->volatility_bps[i] > 1000) {
			ret = -EINVAL;
			goto out;
		}
	}

	spin_lock_irqsave(&sy.lock, flags);
	for (i = 0; i < cfg->count; i++) {
		memcpy(sy.tickers[i].ticker, cfg->tickers[i],
		       STOCKYARD_TICKER_LEN);
		sy.tickers[i].last_cents     = cfg->initial_prices_cents[i];
		sy.tickers[i].volatility_bps = cfg->volatility_bps[i];
	}
	sy.ticker_count = cfg->count;
	spin_unlock_irqrestore(&sy.lock, flags);

out:
	kfree(cfg);
	return ret;
}

static long ioctl_set_rate(unsigned long arg)
{
	unsigned long flags;
	__u32 rate;

	if (copy_from_user(&rate, (void __user *)arg, sizeof(rate)))
		return -EFAULT;
	if (rate < MIN_RATE_HZ || rate > MAX_RATE_HZ)
		return -EINVAL;

	spin_lock_irqsave(&sy.lock, flags);
	sy.rate_hz = rate;
	spin_unlock_irqrestore(&sy.lock, flags);
	return 0;
}

static long ioctl_get_stats(unsigned long arg)
{
	struct stockyard_stats stats;
	unsigned long flags;

	stats.ticks_generated          = atomic64_read(&sy.ticks_generated);
	stats.ticks_dropped_buffer_full = atomic64_read(&sy.ticks_dropped);

	spin_lock_irqsave(&sy.lock, flags);
	stats.current_rate_hz     = sy.rate_hz;
	stats.configured_tickers  = sy.ticker_count;
	spin_unlock_irqrestore(&sy.lock, flags);

	if (copy_to_user((void __user *)arg, &stats, sizeof(stats)))
		return -EFAULT;
	return 0;
}

static long ioctl_reset(void)
{
	unsigned long flags;
	__u32 i;

	spin_lock_irqsave(&sy.lock, flags);
	kfifo_reset(&sy.fifo);
	for (i = 0; i < DEFAULT_TICKER_COUNT; i++)
		sy.tickers[i] = default_tickers[i];
	sy.ticker_count = DEFAULT_TICKER_COUNT;
	sy.rate_hz      = DEFAULT_RATE_HZ;
	spin_unlock_irqrestore(&sy.lock, flags);

	/*
	 * Counters are atomic, intentionally cleared outside the lock.
	 * A tick or two generated between unlock and clear is acceptable
	 * (RESET is a telemetry-grade primitive, not a strict barrier).
	 */
	atomic64_set(&sy.ticks_generated, 0);
	atomic64_set(&sy.ticks_dropped, 0);
	return 0;
}

static long sy_ioctl(struct file *file, unsigned int cmd, unsigned long arg)
{
	if (_IOC_TYPE(cmd) != STOCKYARD_IOC_MAGIC)
		return -ENOTTY;

	switch (cmd) {
	case STOCKYARD_IOC_SET_TICKERS:
		return ioctl_set_tickers(arg);
	case STOCKYARD_IOC_SET_RATE_HZ:
		return ioctl_set_rate(arg);
	case STOCKYARD_IOC_GET_STATS:
		return ioctl_get_stats(arg);
	case STOCKYARD_IOC_RESET:
		return ioctl_reset();
	default:
		return -ENOTTY;
	}
}

static const struct file_operations sy_fops = {
	.owner          = THIS_MODULE,
	.open           = sy_open,
	.release        = sy_release,
	.read           = sy_read,
	.poll           = sy_poll,
	.unlocked_ioctl = sy_ioctl,
	.compat_ioctl   = sy_ioctl,
	.llseek         = no_llseek,
};

static struct miscdevice sy_miscdev = {
	.minor = MISC_DYNAMIC_MINOR,
	.name  = STOCKYARD_DEVICE_NAME,
	.fops  = &sy_fops,
	.mode  = 0660,
};

/* ===== Module lifecycle ============================================= */

static int __init sy_init(void)
{
	int ret;
	__u32 i;

	INIT_KFIFO(sy.fifo);
	spin_lock_init(&sy.lock);
	init_waitqueue_head(&sy.readq);

	for (i = 0; i < DEFAULT_TICKER_COUNT; i++)
		sy.tickers[i] = default_tickers[i];
	sy.ticker_count = DEFAULT_TICKER_COUNT;
	sy.rate_hz      = DEFAULT_RATE_HZ;
	atomic64_set(&sy.ticks_generated, 0);
	atomic64_set(&sy.ticks_dropped, 0);

	ret = misc_register(&sy_miscdev);
	if (ret) {
		pr_err("stockyard: misc_register failed: %d\n", ret);
		return ret;
	}

	/*
	 * Flip the readiness flag only after the device node is live.
	 * sy_open() rejects with -ENODEV until this is set, so a racing
	 * opener after a failed misc_register cannot read garbage.
	 */
	atomic_set(&sy.initialized, 1);

	hrtimer_init(&sy.timer, CLOCK_MONOTONIC, HRTIMER_MODE_REL);
	sy.timer.function = sy_timer_cb;
	hrtimer_start(&sy.timer, period_from_rate(sy.rate_hz),
		      HRTIMER_MODE_REL);

	pr_info("stockyard: loaded — /dev/%s, %u tickers, %u Hz\n",
		STOCKYARD_DEVICE_NAME, sy.ticker_count, sy.rate_hz);
	return 0;
}

static void __exit sy_exit(void)
{
	atomic_set(&sy.initialized, 0);
	hrtimer_cancel(&sy.timer);
	wake_up_all(&sy.readq);
	misc_deregister(&sy_miscdev);
	pr_info("stockyard: unloaded — generated=%llu dropped=%llu\n",
		(unsigned long long)atomic64_read(&sy.ticks_generated),
		(unsigned long long)atomic64_read(&sy.ticks_dropped));
}

module_init(sy_init);
module_exit(sy_exit);

MODULE_LICENSE("GPL");
MODULE_AUTHOR("Stockyard");
MODULE_DESCRIPTION("Exchange tick simulator (/dev/stockyard)");
MODULE_VERSION("0.1.0");
