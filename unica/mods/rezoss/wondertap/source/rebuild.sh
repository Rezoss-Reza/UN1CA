#!/usr/bin/env bash
# Copyright (c) 2026 Rezoss
# SPDX-License-Identifier: GPL-3.0-or-later
# Offline external-module build only; does not install or load modules.
set -Eeuo pipefail
PACKAGE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
BASE=/home/rezoss79/dev/sembilan/out/archived/wondertap-port-20261023/build-5.15.220
[[ -s "$BASE/kernel/out/Module.symvers" ]]
[[ -x "$BASE/clang-r584948/bin/clang" ]]
grep -qF '5.15.220-g2b8b46da' "$BASE/kernel/out/include/generated/utsrelease.h"
TASK_WORK=$(mktemp -d /tmp/s23u-wondertap-rebuild.XXXXXX)
printf 'Isolated build directory: %s\n' "$TASK_WORK"
tar -xzf "$PACKAGE/driver-source.tar.gz" -C "$TASK_WORK"
cp -- "$PACKAGE/build-provider.sh" "$PACKAGE/build-backend.sh" \
    "$PACKAGE/stock-vendor-exports.symvers" "$PACKAGE/prepare-compat-headers.py" "$TASK_WORK/"
cp -a -- "$PACKAGE/patches" "$TASK_WORK/patches"
bash "$TASK_WORK/build-provider.sh"
bash "$TASK_WORK/build-backend.sh" modules
printf 'Debug modules built in %s; no ROM payload or device modified.\n' "$TASK_WORK"
