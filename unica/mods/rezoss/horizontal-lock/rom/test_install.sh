#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Real UN1CA copy/metadata helpers, exclusively in a fresh /tmp fixture.
set -eo pipefail
src_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
repo_dir="$(cd -- "$src_dir/../../../../.." && pwd)"
cd "$repo_dir"
source buildenv.sh dm3q > /tmp/unica-hl-buildenv-test.txt
source "$SRC_DIR/scripts/utils/common_utils.sh"
source "$SRC_DIR/scripts/utils/module_utils.sh"
fixture="$(mktemp -d /tmp/unica-hl-install.XXXXXX)"
export WORK_DIR="$fixture/work"
export MODPATH="$fixture/mod"
mkdir -p "$WORK_DIR/vendor/lib64/camera/components" "$WORK_DIR/configs" "$MODPATH/horizontal-lock/rom"
cp -a "$src_dir/payload" "$MODPATH/horizontal-lock/rom/payload"
cp -a "$src_dir/install.sh" "$MODPATH/horizontal-lock/rom/install.sh"
cp -a "$SRC_DIR/out/fw/SM-S918B_EUX/vendor/lib64/camera/components/com.qti.node.eisv3.so" "$WORK_DIR/vendor/lib64/camera/components/"
cp -a "$SRC_DIR/out/fw/SM-S918B_EUX/vendor/lib64/camera/components/com.qti.eisv3.so" "$WORK_DIR/vendor/lib64/camera/components/"
cp -a "$SRC_DIR/out/fw/SM-S918B_EUX/fs_config-vendor" "$WORK_DIR/configs/fs_config-vendor"
cp -a "$SRC_DIR/out/fw/SM-S918B_EUX/file_context-vendor" "$WORK_DIR/configs/file_context-vendor"
mkdir -p "$WORK_DIR/vendor/etc/selinux"
cp -a "$SRC_DIR/out/fw/SM-S918B_EUX/vendor/etc/selinux/vendor_file_contexts" "$WORK_DIR/vendor/etc/selinux/vendor_file_contexts"
source "$MODPATH/horizontal-lock/rom/install.sh"
cmp "$WORK_DIR/vendor/lib64/camera/components/com.qti.node.eisv3.so" "$src_dir/payload/vendor/lib64/camera/components/com.qti.node.eisv3.so"
cmp "$WORK_DIR/vendor/etc/camera/unica_horizon_lock.conf" "$src_dir/payload/vendor/etc/camera/unica_horizon_lock.conf"
grep -Fx 'fixed_crop=1' "$WORK_DIR/vendor/etc/camera/unica_horizon_lock.conf"
cmp "$WORK_DIR/vendor/etc/camera/unica_horizon_lock/stock-eisv3.so" "$SRC_DIR/out/fw/SM-S918B_EUX/vendor/lib64/camera/components/com.qti.node.eisv3.so"
grep -F 'vendor/lib64/libunica_horizon_adapter.so 0 0 644' "$WORK_DIR/configs/fs_config-vendor"
grep -F 'vendor/etc/camera/unica_horizon_lock 0 0 755' "$WORK_DIR/configs/fs_config-vendor"
grep -F '/vendor/etc/camera/unica_horizon_lock/stock-eisv3\.so u:object_r:vendor_file:s0' "$WORK_DIR/configs/file_context-vendor"
awk 'NF && $1 !~ /^#/ && NF<2 {exit 1}' "$WORK_DIR/configs/file_context-vendor"
# Reapplication to the exact patched node is supported.
REZOSS_INSTALL_HORIZON_LOCK
TARGET_CODENAME=other
REZOSS_INSTALL_HORIZON_LOCK
TARGET_CODENAME=dm3q
# An unsupported node must be rejected before copying payloads.
printf bad > "$WORK_DIR/vendor/lib64/camera/components/com.qti.node.eisv3.so"
if REZOSS_INSTALL_HORIZON_LOCK; then echo 'FAIL unsupported firmware accepted' >&2; exit 1; fi
printf '' > "$MODPATH/horizontal-lock/disable"
REZOSS_INSTALL_HORIZON_LOCK
printf 'PASS install, metadata, stock backup, reapply, firmware rejection, target/disable guards: %s\n' "$fixture"
