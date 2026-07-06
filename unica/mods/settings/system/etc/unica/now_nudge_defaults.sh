#!/system/bin/sh

STATE_KEY="unica_now_nudge_experimental"
LOG_FILE="/data/system/unica_now_nudge_defaults.log"

log_msg() {
    mkdir -p /data/system
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)" "$*" >> "$LOG_FILE" 2>/dev/null
}

log_msg "start"

STATE="$(settings get system "$STATE_KEY" 2>/dev/null)"
case "$STATE" in
    0|false|FALSE)
        log_msg "skip: $STATE_KEY explicitly disabled"
        exit 0
        ;;
esac

settings put system "$STATE_KEY" 1 >/dev/null 2>&1
settings put global sss_enabled 1 >/dev/null 2>&1
settings put global now_nudge_setting 1 >/dev/null 2>&1
settings put global now_nudge_enabled 1 >/dev/null 2>&1
settings put system ai_info_confirmed 1 >/dev/null 2>&1
settings put secure content_capture_enabled 1 >/dev/null 2>&1

log_msg "enabled Now Nudge defaults"
