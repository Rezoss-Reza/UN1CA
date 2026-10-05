#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Sourced by rezoss/customize.sh; use the project's copy/metadata helpers.
REZOSS_INSTALL_HORIZON_LOCK()
{
    [[ "$TARGET_CODENAME" == "dm3q" ]] || return 0
    local hl_root="$MODPATH/horizontal-lock"
    if [[ -f "$hl_root/disable" ]]; then
        LOG "- Horizontal Lock ROM experiment disabled"
        return 0
    fi
    local hl_payload="$hl_root/rom/payload"
    local hl_node="lib64/camera/components/com.qti.node.eisv3.so"
    local hl_original="7e4f72bc10082e074424fe6f5a9d411b1740d2b9b3a9df3cec8ae4f1d4ec26a7"
    local hl_patched="67cf4235ab0da4b4972435b07bdd6aaec943107bc76d88693b0bf3ddca13c04a"
    local hl_algo="26dce4357773abe512592f4400e45e35d863ac53c1d78708c873ade1d4792abc"
    local hl_actual
    [[ -f "$WORK_DIR/vendor/$hl_node" ]] || { ABORT "Horizontal Lock: S23U EIS node missing"; return 1; }
    hl_actual="$(sha256sum "$WORK_DIR/vendor/$hl_node" | cut -d ' ' -f 1)"
    [[ "$hl_actual" == "$hl_original" || "$hl_actual" == "$hl_patched" ]] || {
        ABORT "Horizontal Lock: unsupported EIS node; requires S918BXXUAZZI8"; return 1;
    }
    hl_actual="$(sha256sum "$WORK_DIR/vendor/lib64/camera/components/com.qti.eisv3.so" | cut -d ' ' -f 1)"
    [[ "$hl_actual" == "$hl_algo" ]] || { ABORT "Horizontal Lock: unsupported EIS algorithm"; return 1; }
    (cd "$hl_payload" && sha256sum --check --status SHA256SUMS) || {
        ABORT "Horizontal Lock: payload checksum failure"; return 1;
    }
    LOG "- Installing experimental S23U mode-2 horizon adapter (45-degree target)"
    local hl_file hl_mode
    for hl_file in \
        "etc/camera/unica_horizon_lock" \
        "etc/camera/unica_horizon_lock.conf" \
        "lib64/libunica_horizon_adapter.so" \
        "$hl_node"; do
        hl_mode=644
        [[ -d "$hl_payload/vendor/$hl_file" ]] && hl_mode=755
        ADD_TO_WORK_DIR "$hl_payload" "vendor" "$hl_file" 0 0 "$hl_mode" "u:object_r:vendor_file:s0" || return 1
    done
}
REZOSS_INSTALL_HORIZON_LOCK
