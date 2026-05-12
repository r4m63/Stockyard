#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-2.0
#
# Top-level loader for the stockyard kernel module — run on the host
# BEFORE `docker compose up` so /dev/stockyard exists for the
# quotes-service bind-mount.  Idempotent: re-running is safe.
#
# Steps:
#   1) build driver/stockyard_driver.ko if missing
#   2) delegate to driver/scripts/load.sh (insmod + group + chmod 0660)
#   3) widen perms to 0666 so the distroless quotes-service container
#      (UID 65532) can open /dev/stockyard read-write without joining
#      the stockyard host group
#
# See ADR-015: driver loading is the operator's responsibility, not a
# docker init container (kernel modules can't be inserted from within a
# rootless container without --privileged + matching kernel headers).
#
# Usage:
#   sudo deploy/scripts/load_driver.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DRIVER_DIR="$REPO_ROOT/driver"
LOAD_SH="$DRIVER_DIR/scripts/load.sh"
DEV_PATH="/dev/stockyard"

if [[ $EUID -ne 0 ]]; then
    echo "load_driver: must run as root (try: sudo $0)" >&2
    exit 1
fi

if [[ ! -x "$LOAD_SH" ]]; then
    echo "load_driver: $LOAD_SH not found or not executable" >&2
    exit 1
fi

# 1) Build the module if .ko is missing.  driver/scripts/load.sh aborts
#    instead of building, so we do it here.
if [[ ! -f "$DRIVER_DIR/stockyard_driver.ko" ]]; then
    echo "[load_driver] building stockyard_driver.ko"
    make -C "$DRIVER_DIR" >/dev/null
fi

# 2) Delegate insmod + seed + group/chmod 0660.
"$LOAD_SH"

# 3) The quotes-service container runs as UID 65532 (distroless nonroot)
#    and is not in the host's stockyard group, so 0660 is not enough.
#    0666 is acceptable for the MVP dev box; production would manage
#    this with a matching group_add in compose + udev rules.
if [[ -c "$DEV_PATH" ]]; then
    chmod 0666 "$DEV_PATH"
    echo "[load_driver] $DEV_PATH: $(stat -c '%a %U:%G' "$DEV_PATH")"
else
    echo "load_driver: $DEV_PATH missing after load.sh — check dmesg" >&2
    exit 1
fi
