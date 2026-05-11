#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0
# Unload the stockyard kernel module.  Idempotent.

set -euo pipefail

MODULE="stockyard_driver"

if [[ $EUID -ne 0 ]]; then
	echo "must run as root (try: sudo $0)" >&2
	exit 1
fi

if lsmod | awk '{print $1}' | grep -qx "$MODULE"; then
	rmmod "$MODULE"
	echo "$MODULE unloaded"
else
	echo "$MODULE not loaded"
fi
