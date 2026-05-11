#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0
#
# Load the stockyard kernel module and seed the full 50-ticker config.
# Idempotent: skip insmod if already loaded.  Must run as root or via sudo.

set -euo pipefail

MODULE="stockyard_driver"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DRIVER_DIR="$(cd "$HERE/.." && pwd)"
KO_PATH="$DRIVER_DIR/$MODULE.ko"
SEED_PATH="$DRIVER_DIR/seed/tickers.txt"
DEV_PATH="/dev/stockyard"
GROUP_NAME="stockyard"

if [[ $EUID -ne 0 ]]; then
	echo "must run as root (try: sudo $0)" >&2
	exit 1
fi

if [[ ! -f "$KO_PATH" ]]; then
	echo "build first: (cd $DRIVER_DIR && make)" >&2
	exit 1
fi

# Create the unprivileged group if absent.
if ! getent group "$GROUP_NAME" >/dev/null; then
	groupadd --system "$GROUP_NAME"
	echo "created group: $GROUP_NAME"
fi

# Insert the module (idempotent).
if lsmod | awk '{print $1}' | grep -qx "$MODULE"; then
	echo "$MODULE already loaded"
else
	insmod "$KO_PATH"
	echo "$MODULE loaded"
fi

# misc_register created /dev/stockyard already.  Fix the perms.
if [[ -c "$DEV_PATH" ]]; then
	chgrp "$GROUP_NAME" "$DEV_PATH"
	chmod 0660 "$DEV_PATH"
	echo "$DEV_PATH: $(stat -c '%a %U:%G' "$DEV_PATH")"
else
	echo "warn: $DEV_PATH not present — check dmesg" >&2
fi

# Push the full seed if test_ioctl is built.
TOOL="$DRIVER_DIR/test/test_ioctl"
if [[ -x "$TOOL" && -f "$SEED_PATH" ]]; then
	"$TOOL" load "$SEED_PATH"
else
	echo "skip seed: build test/test_ioctl to load tickers.txt"
fi

echo "done.  to read ticks: $DRIVER_DIR/test/test_read 5"
