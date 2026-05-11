// SPDX-License-Identifier: GPL-2.0
/*
 * test_errors — verifies that the driver returns the right errno for
 * out-of-range and malformed ioctl/read arguments.  Smoke-level: each
 * call prints its return + errno.  Pass criteria (read manually):
 *
 *   SET_RATE_HZ: 0 EINVAL, 1 OK, 1000 OK, 1001 EINVAL, 65535 EINVAL
 *   SET_TICKERS: count=0 EINVAL, count=65 EINVAL, price=0 EINVAL,
 *                vol=0 EINVAL, vol=1001 EINVAL
 *   bad-magic ioctl: ENOTTY
 *   read(43) (not multiple of 44): EINVAL
 *   read(0): 0 (legal short read)
 */
#define _POSIX_C_SOURCE 200809L
#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <sys/ioctl.h>

#include "../stockyard_driver.h"

static void check_rate(int fd, uint32_t r)
{
	int ret = ioctl(fd, STOCKYARD_IOC_SET_RATE_HZ, &r);
	printf("  rate=%-5u  → ret=%d  errno=%s\n",
	       r, ret, ret == 0 ? "OK" : strerror(errno));
}

int main(void)
{
	int fd = open("/dev/stockyard", O_RDWR);
	if (fd < 0) {
		perror("open /dev/stockyard");
		return 1;
	}

	printf("SET_RATE_HZ\n");
	check_rate(fd, 0);
	check_rate(fd, 1);
	check_rate(fd, 1000);
	check_rate(fd, 1001);
	check_rate(fd, 65535);

	printf("SET_TICKERS\n");
	struct stockyard_tickers_cfg cfg;
	memset(&cfg, 0, sizeof(cfg));

	cfg.count = 0;
	int r = ioctl(fd, STOCKYARD_IOC_SET_TICKERS, &cfg);
	printf("  count=0     → ret=%d  errno=%s\n",
	       r, r == 0 ? "OK" : strerror(errno));

	cfg.count = 65;
	r = ioctl(fd, STOCKYARD_IOC_SET_TICKERS, &cfg);
	printf("  count=65    → ret=%d  errno=%s\n",
	       r, r == 0 ? "OK" : strerror(errno));

	cfg.count = 1;
	memcpy(cfg.tickers[0], "BAD\0\0\0\0\0", STOCKYARD_TICKER_LEN);
	cfg.initial_prices_cents[0] = 0;       /* < MIN_PRICE_CENTS */
	cfg.volatility_bps[0]       = 100;
	r = ioctl(fd, STOCKYARD_IOC_SET_TICKERS, &cfg);
	printf("  price=0     → ret=%d  errno=%s\n",
	       r, r == 0 ? "OK" : strerror(errno));

	cfg.initial_prices_cents[0] = 10000;
	cfg.volatility_bps[0]       = 0;
	r = ioctl(fd, STOCKYARD_IOC_SET_TICKERS, &cfg);
	printf("  vol=0       → ret=%d  errno=%s\n",
	       r, r == 0 ? "OK" : strerror(errno));

	cfg.volatility_bps[0] = 1001;
	r = ioctl(fd, STOCKYARD_IOC_SET_TICKERS, &cfg);
	printf("  vol=1001    → ret=%d  errno=%s\n",
	       r, r == 0 ? "OK" : strerror(errno));

	printf("UNKNOWN IOCTL\n");
	unsigned int bad = _IO(0xAB, 1);
	r = ioctl(fd, bad, 0);
	printf("  bad magic   → ret=%d  errno=%s\n",
	       r, r == 0 ? "OK" : strerror(errno));

	printf("READ\n");
	char buf[100];
	r = read(fd, buf, 43);            /* not multiple of 44 */
	printf("  read(43)    → ret=%d  errno=%s\n",
	       r, r < 0 ? strerror(errno) : "OK");
	r = read(fd, buf, 0);             /* zero-length */
	printf("  read(0)     → ret=%d  errno=%s\n",
	       r, r < 0 ? strerror(errno) : "OK");

	close(fd);
	return 0;
}
