// SPDX-License-Identifier: GPL-2.0
/*
 * test_ioctl — exercises every ioctl on /dev/stockyard.
 *
 * Subcommands:
 *   stats                 — GET_STATS
 *   rate <hz>             — SET_RATE_HZ
 *   reset                 — RESET (back to default 5 tickers, 1 Hz)
 *   load <tickers.txt>    — SET_TICKERS from file
 *                              file format: one entry per line:
 *                              <TICKER> <PRICE_CENTS> <VOL_BPS>
 *                              up to 64 lines, '#' starts comment.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdint.h>
#include <inttypes.h>
#include <sys/ioctl.h>

#include "../stockyard_driver.h"

#define DEVICE_PATH "/dev/stockyard"

static int do_stats(int fd)
{
	struct stockyard_stats s = {0};
	if (ioctl(fd, STOCKYARD_IOC_GET_STATS, &s) < 0) {
		perror("ioctl GET_STATS");
		return 1;
	}
	printf("ticks_generated         = %" PRIu64 "\n", s.ticks_generated);
	printf("ticks_dropped_buffer_full = %" PRIu64 "\n",
	       s.ticks_dropped_buffer_full);
	printf("current_rate_hz         = %" PRIu32 "\n", s.current_rate_hz);
	printf("configured_tickers      = %" PRIu32 "\n", s.configured_tickers);
	return 0;
}

static int do_rate(int fd, const char *arg)
{
	uint32_t rate = (uint32_t)strtoul(arg, NULL, 10);
	if (ioctl(fd, STOCKYARD_IOC_SET_RATE_HZ, &rate) < 0) {
		perror("ioctl SET_RATE_HZ");
		return 1;
	}
	printf("rate set to %u Hz\n", rate);
	return 0;
}

static int do_reset(int fd)
{
	if (ioctl(fd, STOCKYARD_IOC_RESET) < 0) {
		perror("ioctl RESET");
		return 1;
	}
	printf("reset done (default 5 tickers, 1 Hz, stats zeroed)\n");
	return 0;
}

static int parse_line(char *line, char *ticker, int64_t *price, uint32_t *vol)
{
	/* Strip comments and trailing newline. */
	char *hash = strchr(line, '#');
	if (hash)
		*hash = '\0';

	char tk[64];
	long long p;
	unsigned int v;
	int n = sscanf(line, "%63s %lld %u", tk, &p, &v);
	if (n != 3)
		return 0;
	if (strlen(tk) == 0 || strlen(tk) > STOCKYARD_TICKER_LEN)
		return 0;
	memset(ticker, 0, STOCKYARD_TICKER_LEN);
	memcpy(ticker, tk, strlen(tk));
	*price = (int64_t)p;
	*vol   = (uint32_t)v;
	return 1;
}

static int do_load(int fd, const char *path)
{
	FILE *f = fopen(path, "r");
	if (!f) {
		fprintf(stderr, "open %s: %s\n", path, strerror(errno));
		return 1;
	}

	struct stockyard_tickers_cfg *cfg = calloc(1, sizeof(*cfg));
	if (!cfg) {
		fclose(f);
		perror("calloc");
		return 1;
	}

	char line[256];
	uint32_t count = 0;
	while (fgets(line, sizeof(line), f)) {
		if (count >= STOCKYARD_MAX_TICKERS) {
			fprintf(stderr, "warn: more than %u entries, truncated\n",
				STOCKYARD_MAX_TICKERS);
			break;
		}
		char ticker[STOCKYARD_TICKER_LEN];
		int64_t price;
		uint32_t vol;
		if (parse_line(line, ticker, &price, &vol)) {
			memcpy(cfg->tickers[count], ticker, STOCKYARD_TICKER_LEN);
			cfg->initial_prices_cents[count] = price;
			cfg->volatility_bps[count]       = vol;
			count++;
		}
	}
	fclose(f);

	if (count == 0) {
		fprintf(stderr, "no valid entries in %s\n", path);
		free(cfg);
		return 1;
	}
	cfg->count = count;

	if (ioctl(fd, STOCKYARD_IOC_SET_TICKERS, cfg) < 0) {
		perror("ioctl SET_TICKERS");
		free(cfg);
		return 1;
	}
	printf("loaded %u tickers from %s\n", count, path);
	free(cfg);
	return 0;
}

static void usage(const char *prog)
{
	fprintf(stderr,
		"usage:\n"
		"  %s stats\n"
		"  %s rate <hz>\n"
		"  %s reset\n"
		"  %s load <tickers.txt>\n",
		prog, prog, prog, prog);
}

int main(int argc, char **argv)
{
	if (argc < 2) {
		usage(argv[0]);
		return 1;
	}

	int fd = open(DEVICE_PATH, O_RDWR);
	if (fd < 0) {
		fprintf(stderr, "open %s: %s\n", DEVICE_PATH, strerror(errno));
		return 1;
	}

	int rc;
	if (strcmp(argv[1], "stats") == 0) {
		rc = do_stats(fd);
	} else if (strcmp(argv[1], "rate") == 0 && argc == 3) {
		rc = do_rate(fd, argv[2]);
	} else if (strcmp(argv[1], "reset") == 0) {
		rc = do_reset(fd);
	} else if (strcmp(argv[1], "load") == 0 && argc == 3) {
		rc = do_load(fd, argv[2]);
	} else {
		usage(argv[0]);
		rc = 1;
	}

	close(fd);
	return rc;
}
