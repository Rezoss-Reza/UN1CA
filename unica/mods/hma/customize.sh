#!/usr/bin/env bash
# Copyright (c) 2026 UN1CA contributors
# SPDX-License-Identifier: GPL-3.0-or-later

# The initial MediaProvider baseline is specific to dm3q One UI 9.
if [[ "$TARGET_CODENAME" == "dm3q" && "$TARGET_PLATFORM_SDK_VERSION" == "37" ]]; then
    HMA_MEDIA_INPUT="$WORK_DIR/system/system/apex/com.google.android.mediaprovider_compressed.apex"
    if [[ ! -f "$HMA_MEDIA_INPUT" ]]; then
        ABORT "HMA storage protection requires the stock MediaProvider compressed APEX" || return 1
    fi
    HMA_MEDIA_WORK=$(mktemp -d /tmp/unica-hma-storage.XXXXXX)
    LOG "- Hardening MediaProvider directory checks against Unicode path probes"
    python3 "$MODPATH/tools/build_storage_apex.py" "$HMA_MEDIA_INPUT" "$HMA_MEDIA_WORK" || return 1
    # Install only after patch, DEX, APK and AVB validation have succeeded.
    ADD_TO_WORK_DIR "$HMA_MEDIA_WORK/install" "system" \
        "system/apex/com.google.android.mediaprovider.apex" 0 0 644 "u:object_r:system_file:s0" || return 1
    DELETE_FROM_WORK_DIR "system" "system/apex/com.google.android.mediaprovider_compressed.apex" || return 1
    LOG "- HMA storage build evidence: $HMA_MEDIA_WORK"
    unset HMA_MEDIA_INPUT HMA_MEDIA_WORK
fi
