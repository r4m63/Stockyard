# SPDX-License-Identifier: GPL-2.0
# Userspace tests for the stockyard driver.

CC      ?= gcc
CFLAGS  ?= -O2 -Wall -Wextra -Werror -std=c11 -D_FILE_OFFSET_BITS=64

BINS := test_read test_ioctl test_layout test_errors

.PHONY: all clean

all: $(BINS)

test_read: test_read.c ../stockyard_driver.h
	$(CC) $(CFLAGS) -o $@ test_read.c

test_ioctl: test_ioctl.c ../stockyard_driver.h
	$(CC) $(CFLAGS) -o $@ test_ioctl.c

test_layout: test_layout.c ../stockyard_driver.h
	$(CC) $(CFLAGS) -o $@ test_layout.c

test_errors: test_errors.c ../stockyard_driver.h
	$(CC) $(CFLAGS) -o $@ test_errors.c

clean:
	$(RM) $(BINS)
