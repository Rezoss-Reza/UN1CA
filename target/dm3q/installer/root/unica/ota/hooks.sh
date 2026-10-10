#!/sbin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Sourced by the recovery wrapper, before partition writes and after DFE.
ota_result() {
    mkdir -p /data/misc/unica_ota/staged 2>/dev/null
    echo "$1" > /data/misc/unica_ota/staged/result
    chmod 0640 /data/misc/unica_ota/staged/result
    chown 0:1000 /data/misc/unica_ota/staged/result
    cp /tmp/recovery.log /data/misc/unica_ota/staged/recovery.log 2>/dev/null
    chmod 0640 /data/misc/unica_ota/staged/recovery.log 2>/dev/null
    chown 0:1000 /data/misc/unica_ota/staged/recovery.log 2>/dev/null
    rm -f /cache/unica-ota/auto
    sync
}
ota_preflight() {
    unzip -oq "$ZIPFILE" 'unica/ota/update.properties' 'unica/ota/cancel' -d "$TMPDIR" || return 1
    PROPS="$TMPDIR/unica/ota/update.properties"
    [ -f "$PROPS" ] || return 1
    BASE="$(sed -n 's/^base_timestamp=//p' "$PROPS")"
    TARGET="$(sed -n 's/^target_timestamp=//p' "$PROPS")"
    case "$BASE:$TARGET" in *[!0-9:]*|:*) ui_print 'E: Invalid OTA identity'; return 1;; esac
    [ -n "$BASE" ] && [ -n "$TARGET" ] || return 1
    # OrangeFox dm3q uses /system_root; /system is its recovery runtime tree.
    mount /system_root 2>/dev/null || mount /system 2>/dev/null || true
    CURRENT=""
    for BUILDPROP in /system_root/system/build.prop /system/system/build.prop /system/build.prop; do
        [ -f "$BUILDPROP" ] || continue
        CURRENT="$(sed -n 's/^ro.unica.timestamp=//p' "$BUILDPROP")"
        [ -n "$CURRENT" ] && break
    done
    umount /system 2>/dev/null || true
    umount /system_root 2>/dev/null || true
    if [ -z "$CURRENT" ]; then
        ui_print 'E: Cannot read installed ROM identity. Check the recovery system mount.'
        ota_result 'Rejected: installed ROM identity unavailable'
        return 1
    fi
    if [ "$CURRENT" != "$BASE" ] && [ "$CURRENT" != "$TARGET" ]; then
        ui_print "Installed release: $CURRENT; required base: $BASE; target: $TARGET"
        ui_print 'E: OTA base does not match. Install the full ROM instead.'
        ota_result 'Rejected: wrong base ROM'
        return 1
    fi
    # Target identity is allowed here for recovery retries after a partial flash;
    # normal Android UI rejects an already-installed target before scheduling.
    chmod 0755 "$TMPDIR/unica/ota/cancel" || return 1
    ui_print 'OTA starts in 5 seconds. Press Volume Down to cancel.'
    "$TMPDIR/unica/ota/cancel"
    CANCEL="$?"
    if [ "$CANCEL" != 0 ]; then
        if [ "$CANCEL" = 2 ]; then
            ui_print 'OTA installation cancelled. No partition writes performed.'
            ota_result 'Cancelled'
        else
            ui_print 'E: Cannot monitor Volume Down. Installation stopped.'
            ota_result 'Failed: recovery input unavailable'
        fi
        return 1
    fi
    return 0
}
ota_complete() {
    AUTO=false
    if [ "$ZIPFILE" = /data/misc/unica_ota/staged/update.zip ] && [ -f /cache/unica-ota/auto ]; then AUTO=true; fi
    if [ "$STATUS" != 0 ]; then ota_result 'Failed: installation or DFE error'; return; fi
    ota_result 'Successful: OTA and DFE completed'
    if $AUTO; then
        ui_print 'OTA complete. Rebooting into Android.'
        sync
        reboot system
    fi
}
