// SPDX-License-Identifier: GPL-2.0
/*
 * test_read — opens /dev/stockyard, reads N ticks, prints both raw hex
 * and parsed fields.  No ioctl; just verifies the wire format.
 *
 * Usage:
 *   ./test_read [N]            (default N=10)
 *   ./test_read --nonblock 5
 *   ./test_read --golden 1     (single tick, hex only — for golden fixture)
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdint.h>
#include <inttypes.h>

#include "../stockyard_driver.h"

#define DEVICE_PATH "/dev/stockyard"

static void hexdump_tick(const struct stockyard_tick *t)
{
	const unsigned char *p = (const unsigned char *)t;
	size_t i;
	for (i = 0; i < STOCKYARD_TICK_SIZE; i++) {
		printf("%02x%s", p[i],
		       ((i + 1) % 8 == 0) ? "  " : " ");
	}
	printf("\n");
}

static void print_tick(const struct stockyard_tick *t)
{
	char tk[STOCKYARD_TICKER_LEN + 1] = {0};
	memcpy(tk, t->ticker, STOCKYARD_TICKER_LEN);
	printf("ticker=%-8s ts_ns=%" PRIu64 "  bid=%" PRId64
	       " ask=%" PRId64 " last=%" PRId64 " vol=%" PRIu32 "\n",
	       tk, t->ts_ns, t->bid_cents, t->ask_cents,
	       t->last_cents, t->volume);
}

int main(int argc, char **argv)
{
	int flags = O_RDONLY;
	int n = 10;
	int golden = 0;
	int i, arg = 1;

	while (arg < argc) {
		if (strcmp(argv[arg], "--nonblock") == 0) {
			flags |= O_NONBLOCK;
			arg++;
		} else if (strcmp(argv[arg], "--golden") == 0) {
			golden = 1;
			arg++;
		} else if (argv[arg][0] == '-') {
			fprintf(stderr, "unknown option: %s\n", argv[arg]);
			return 1;
		} else {
			n = atoi(argv[arg]);
			arg++;
		}
	}
	if (n <= 0)
		n = 10;

	int fd = open(DEVICE_PATH, flags);
	if (fd < 0) {
		fprintf(stderr, "open %s: %s\n", DEVICE_PATH, strerror(errno));
		return 1;
	}
	if (sizeof(struct stockyard_tick) != STOCKYARD_TICK_SIZE) {
		fprintf(stderr, "wire-format drift: sizeof=%zu, expected=%d\n",
			sizeof(struct stockyard_tick), STOCKYARD_TICK_SIZE);
		close(fd);
		return 1;
	}

	struct stockyard_tick *batch =
		calloc((size_t)n, sizeof(struct stockyard_tick));
	if (!batch) {
		perror("calloc");
		close(fd);
		return 1;
	}

	size_t want = (size_t)n * STOCKYARD_TICK_SIZE;
	size_t got = 0;
	while (got < want) {
		ssize_t r = read(fd, (char *)batch + got, want - got);
		if (r < 0) {
			if (errno == EINTR)
				continue;
			fprintf(stderr, "read: %s\n", strerror(errno));
			free(batch);
			close(fd);
			return 1;
		}
		if (r == 0) {
			fprintf(stderr, "unexpected EOF\n");
			break;
		}
		got += (size_t)r;
	}

	size_t ticks_got = got / STOCKYARD_TICK_SIZE;
	for (i = 0; i < (int)ticks_got; i++) {
		if (golden) {
			printf("# tick %d hex (%d bytes packed LE):\n",
			       i, STOCKYARD_TICK_SIZE);
			hexdump_tick(&batch[i]);
			printf("# decoded: ");
			print_tick(&batch[i]);
		} else {
			print_tick(&batch[i]);
		}
	}

	free(batch);
	close(fd);
	return 0;
}
