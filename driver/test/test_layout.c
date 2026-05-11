// SPDX-License-Identifier: GPL-2.0
/*
 * test_layout — compile-time layout asserts for stockyard_tick.
 *
 * Catches struct drift before it reaches the kernel.  This test
 * doesn't need /dev/stockyard or root.  Build:
 *     cc -Wall -Wextra -Werror -std=c11 test_layout.c -o test_layout
 * Run:
 *     ./test_layout    (prints offsets if all asserts pass)
 */
#include <stdio.h>
#include <stddef.h>
#include <assert.h>

#include "../stockyard_driver.h"

_Static_assert(sizeof(struct stockyard_tick) == 44, "wire size != 44");
_Static_assert(sizeof(struct stockyard_tick) == STOCKYARD_TICK_SIZE,
	       "STOCKYARD_TICK_SIZE drift");

_Static_assert(offsetof(struct stockyard_tick, ticker)     ==  0, "ticker offset");
_Static_assert(offsetof(struct stockyard_tick, ts_ns)      ==  8, "ts_ns offset");
_Static_assert(offsetof(struct stockyard_tick, bid_cents)  == 16, "bid offset");
_Static_assert(offsetof(struct stockyard_tick, ask_cents)  == 24, "ask offset");
_Static_assert(offsetof(struct stockyard_tick, last_cents) == 32, "last offset");
_Static_assert(offsetof(struct stockyard_tick, volume)     == 40, "volume offset");

_Static_assert(sizeof(((struct stockyard_tick *)0)->ticker)     == 8, "ticker size");
_Static_assert(sizeof(((struct stockyard_tick *)0)->ts_ns)      == 8, "ts_ns size");
_Static_assert(sizeof(((struct stockyard_tick *)0)->bid_cents)  == 8, "bid size");
_Static_assert(sizeof(((struct stockyard_tick *)0)->ask_cents)  == 8, "ask size");
_Static_assert(sizeof(((struct stockyard_tick *)0)->last_cents) == 8, "last size");
_Static_assert(sizeof(((struct stockyard_tick *)0)->volume)     == 4, "volume size");

_Static_assert(STOCKYARD_MAX_TICKERS == 64,    "max_tickers drift");
_Static_assert(STOCKYARD_TICKER_LEN  ==  8,    "ticker_len drift");
_Static_assert(STOCKYARD_RING_SIZE   == 8192,  "ring_size drift");

/* Validate that the same little-endian byte sequence the Go parser
 * (TASK-009) will see is what we'd construct here.  ts_ns is set to
 * a known sentinel so the fixture is reproducible.
 */
int main(void)
{
	struct stockyard_tick t = {
		.ticker     = { 'S', 'B', 'E', 'R', 0, 0, 0, 0 },
		.ts_ns      = 0,
		.bid_cents  = 28550,
		.ask_cents  = 28570,
		.last_cents = 28560,
		.volume     = 12345,
	};

	const unsigned char *p = (const unsigned char *)&t;
	size_t i;

	printf("size: %zu\n", sizeof(t));
	printf("offsets: ticker=%zu ts_ns=%zu bid=%zu ask=%zu last=%zu vol=%zu\n",
	       offsetof(struct stockyard_tick, ticker),
	       offsetof(struct stockyard_tick, ts_ns),
	       offsetof(struct stockyard_tick, bid_cents),
	       offsetof(struct stockyard_tick, ask_cents),
	       offsetof(struct stockyard_tick, last_cents),
	       offsetof(struct stockyard_tick, volume));
	printf("synthetic fixture (ts_ns=0, SBER, bid=28550, ask=28570, last=28560, vol=12345):\n");
	for (i = 0; i < sizeof(t); i++) {
		printf("%02x%s", p[i],
		       ((i + 1) % 8 == 0) ? "  " : " ");
	}
	printf("\n");
	return 0;
}
