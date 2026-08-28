#!/system/bin/sh

LOG_TAG="RezossBootAnim"
SYSTEM_ZIP="/system/media/bootanimation.zip"
AOSP_BOOTANIM="/system/bin/bootanimation_zip"
SYSTEM_BOOTANIM="/system/bin/bootanimation"
MODE="apply"
BIND_CHANGED=0

case "$1" in
    ""|--apply)
        MODE="apply"
        ;;
esac

log_msg()
{
    log -t "$LOG_TAG" "$1"
}

is_mounted()
{
    grep -q " $1 " /proc/mounts 2>/dev/null
}

bind_file()
{
    SRC="$1"
    DST="$2"

    if is_mounted "$DST"; then
        log_msg "$DST is already bind mounted"
        return 0
    fi

    if mount -o bind "$SRC" "$DST"; then
        BIND_CHANGED=1
        return 0
    fi

    return 1
}

restart_bootanim()
{
    if [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ]; then
        log_msg "boot completed, not restarting bootanim"
        return 0
    fi

    if [ "$BIND_CHANGED" != "1" ]; then
        log_msg "no new bind mount, not restarting bootanim"
        return 0
    fi

    BOOTANIM_STATE="$(getprop init.svc.bootanim 2>/dev/null)"
    if [ "$BOOTANIM_STATE" != "running" ]; then
        log_msg "bootanim service is $BOOTANIM_STATE, bind will apply when it starts"
        return 0
    fi

    setprop ctl.restart bootanim 2>/dev/null
    log_msg "requested bootanim restart"
}

apply_system_zip()
{
    if [ ! -s "$SYSTEM_ZIP" ]; then
        log_msg "no /system/media/bootanimation.zip, keeping Samsung QMG bootanimation"
        return 0
    fi

    if [ ! -f "$AOSP_BOOTANIM" ]; then
        log_msg "$AOSP_BOOTANIM is missing"
        return 0
    fi

    if [ ! -e "$SYSTEM_BOOTANIM" ]; then
        log_msg "$SYSTEM_BOOTANIM bind target is missing"
        return 0
    fi

    if ! bind_file "$AOSP_BOOTANIM" "$SYSTEM_BOOTANIM"; then
        log_msg "failed to bind AOSP bootanimation binary"
        return 0
    fi

    log_msg "using /system/media/bootanimation.zip with AOSP bootanimation"
    restart_bootanim
    return 0
}

case "$MODE" in
    *)
        apply_system_zip
        ;;
esac

exit 0
