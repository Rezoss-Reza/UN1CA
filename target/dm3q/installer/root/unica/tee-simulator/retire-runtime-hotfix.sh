#!/sbin/sh
# Copyright (c) 2026 Rezoss
# SPDX-License-Identifier: GPL-3.0-or-later
# Called only after successful installation of a package with a verified ROM backend.

OUTFD="$1"
MANIFEST="$2"
DATA_DIR=/data

ui_print() {
    if [ -n "$OUTFD" ] && [ -e "/proc/self/fd/$OUTFD" ]; then
        echo "ui_print $1" > "/proc/self/fd/$OUTFD"
        echo "ui_print" > "/proc/self/fd/$OUTFD"
    else
        echo "$1"
    fi
}

# Recovery has no KernelSU module overlays. Read the new ROM itself; do not
# disable a working hotfix when an old ROM, missing policy, or mount failure is seen.
MOUNTED=''
mount_readonly() {
    grep -q " $1 " /proc/mounts && return 0
    if mount -o ro "$1" >/dev/null 2>&1; then
        MOUNTED="$1 $MOUNTED"
    fi
}
cleanup_mounts() {
    for point in $MOUNTED; do
        umount "$point" >/dev/null 2>&1 || true
    done
}
trap cleanup_mounts EXIT
mount_readonly /system_root
mount_readonly /system_ext
if [ ! -f /system_root/system/bin/unica-teesim-inject ]; then
    mount_readonly /system
fi
FOUND=false
for SYSTEM_ROOT in /system_root /system ''; do
    if [ -f "$SYSTEM_ROOT/system/bin/unica-teesim-inject" ]; then
        FOUND=true
        break
    fi
done
if ! $FOUND || [ ! -f "$MANIFEST" ] || ! command -v sha256sum >/dev/null 2>&1; then
    ui_print "[TEE] Cannot verify installed ROM; keeping the temporary hotfix enabled."
    exit 0
fi
SEEN=''
COUNT=0
while read -r EXPECTED RELATIVE EXTRA; do
    case "$RELATIVE" in
        system/bin/unica-teesim-inject|system/framework/unica-teesim.jar|system/lib64/libunica_teesim.so) ;;
        *) ui_print "[TEE] Invalid retirement manifest; keeping hotfix enabled."; exit 0 ;;
    esac
    case "$EXPECTED" in *[!0-9a-f]*|'') exit 0;; esac
    [ "${#EXPECTED}" = 64 ] && [ -z "$EXTRA" ] || exit 0
    case " $SEEN " in *" $RELATIVE "*) exit 0;; esac
    ACTUAL="$(sha256sum "$SYSTEM_ROOT/$RELATIVE" 2>/dev/null)" || exit 0
    if [ "${ACTUAL%% *}" != "$EXPECTED" ]; then
        ui_print "[TEE] Installed ROM still needs the hotfix; leaving it enabled."
        exit 0
    fi
    SEEN="$SEEN $RELATIVE"
    COUNT=$((COUNT + 1))
done < "$MANIFEST"
[ "$COUNT" = 3 ] || exit 0
[ -f "$SYSTEM_ROOT/system/etc/init/unica-teesim.rc" ] || exit 0
POLICY=/system_ext/etc/selinux/system_ext_sepolicy.cil
[ -f "$POLICY" ] || POLICY="$SYSTEM_ROOT/system/system_ext/etc/selinux/system_ext_sepolicy.cil"
for RULE in \
    '(allow unica_teesim keystore (unix_dgram_socket (sendto)))' \
    '(allow system_server unica_teesim (fd (use)))' \
    '(allow system_server unica_teesim (fifo_file (read write getattr ioctl)))'; do
    if ! grep -Fqx "$RULE" "$POLICY" 2>/dev/null; then
        ui_print "[TEE] Installed policy still needs the hotfix; leaving it enabled."
        exit 0
    fi
done

if ! grep -q " $DATA_DIR " /proc/mounts; then
    mount "$DATA_DIR" >/dev/null 2>&1 || {
        ui_print "[TEE] Data unavailable; disable unica_teesim_runtime_fix manually after flashing."
        exit 0
    }
fi

[ ! -L "$DATA_DIR" ] && [ ! -L "$DATA_DIR/adb" ] || exit 0
for parent in "$DATA_DIR/adb/modules" "$DATA_DIR/adb/modules_update"; do
    module="$parent/unica_teesim_runtime_fix"
    [ -d "$module" ] || continue
    if [ -L "$parent" ] || [ -L "$module" ] || [ -L "$module/module.prop" ] ||
        [ -L "$module/disable" ] || [ ! -f "$module/module.prop" ] ||
        ! grep -qx 'id=unica_teesim_runtime_fix' "$module/module.prop"; then
        ui_print "[TEE] Unexpected hotfix layout; leaving $module unchanged."
        continue
    fi
    if : >> "$module/disable"; then
        ui_print "[TEE] Disabled temporary hotfix: $module. ROM backend takes over on reboot."
    else
        ui_print "[TEE] Could not disable $module; disable it manually before reboot."
    fi
done
