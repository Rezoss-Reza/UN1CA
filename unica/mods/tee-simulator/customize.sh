#!/usr/bin/env bash
# Copyright (c) 2026 Rezoss
# SPDX-License-Identifier: GPL-3.0-or-later

SKIPUNZIP=1

if [[ "$TARGET_CODENAME" != "dm3q" || "$SOURCE_PLATFORM_SDK_VERSION" != "37" ]]; then
    LOG "- Skipping TEE Simulator: requires dm3q with SDK 37"
    return 0
fi
if [ ! -f "$SRC_DIR/unica/mods/keybox/disable" ]; then
    ABORT "Disable the old keybox mod before enabling TEE Simulator" || return 1
fi
if [ ! -f "$MODPATH/payload.sha256" ] || \
    ! (cd "$MODPATH/payload" && sha256sum --check --status "$MODPATH/payload.sha256"); then
    ABORT "Missing or modified TEE Simulator payload; run $MODPATH/build_backend.py" || return 1
fi
if [ ! -f "$MODPATH/source.sha256" ] || \
    ! (cd "$MODPATH" && sha256sum --check --status source.sha256); then
    ABORT "TEE Simulator sources changed; rebuild the backend payload" || return 1
fi
DECODE_APK "system" "system/framework/framework.jar" || return 1
if [ -f "$APKTOOL_DIR/system/framework/framework.jar/smali_classes6/io/mesalabs/unica/KeyboxDbHelper.smali" ]; then
    ABORT "Old Java keybox hooks remain in the decoded framework; start a clean ROM build" || return 1
fi

LOG "- Installing native TEE Simulator with the existing Settings keybox controls"
ADD_TO_WORK_DIR "$MODPATH/payload" "system" "system/bin/unica-teesim-inject" 0 0 755 "u:object_r:system_file:s0"
ADD_TO_WORK_DIR "$MODPATH/payload" "system" "system/lib64/libunica_teesim.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "$MODPATH/payload" "system" "system/framework/unica-teesim.jar" 0 0 644 "u:object_r:system_file:s0"
ADD_TO_WORK_DIR "$MODPATH" "system" "system/etc/init/unica-teesim.rc" 0 0 644 "u:object_r:system_file:s0"
ADD_TO_WORK_DIR "$MODPATH" "system" "system/etc/unica/tee-simulator/daemon.sh" 0 0 755 "u:object_r:system_file:s0"
python3 "$MODPATH/install_policy.py" "$WORK_DIR" "$MODPATH" || return 1
APPLY_PATCH "system" "system/framework/framework.jar" \
    "$MODPATH/patches/framework.jar/0001-Keep-keybox-Settings-and-PIF-compatibility.patch"
