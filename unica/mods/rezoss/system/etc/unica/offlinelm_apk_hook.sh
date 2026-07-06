#!/system/bin/sh

PKG="com.samsung.android.offline.languagemodel"
TARGET_VERSION="400119000"
STATE_DIR="${UNICA_OFFLINELM_STATE_DIR:-/data/system/unica_offlinelm}"
META_FILE="$STATE_DIR/meta"
LOG_FILE="$STATE_DIR/patch.log"
CLASSES2_CRC_ORIG="4c6b2b29"
CLASSES2_CRC_PATCHED="e0ace78e"
HTP_JSON_CRC_ORIG="01e6bdbf"
HTP_JSON_CRC_PATCHED="07a125a9"

MODE="${1:-apply}"
APK_ARG="$2"

log_msg() {
    mkdir -p "$STATE_DIR" 2>/dev/null
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)" "$*" >> "$LOG_FILE" 2>/dev/null
}

file_size() {
    wc -c < "$1" 2>/dev/null | tr -d ' '
}

restore_path() {
    command -v restorecon >/dev/null 2>&1 && restorecon "$1" >/dev/null 2>&1
}

le16_at() {
    dd if="$1" bs=1 skip="$2" count=2 2>/dev/null | od -An -tu2 -v | tr -d ' \n'
}

le32_at() {
    dd if="$1" bs=1 skip="$2" count=4 2>/dev/null | od -An -tu4 -v | tr -d ' \n'
}

hex4_at() {
    dd if="$1" bs=1 skip="$2" count=4 2>/dev/null | od -An -tx1 -v | tr -d ' \n'
}

write_hex4_at() {
    file="$1"
    off="$2"
    hex="$3"

    case "$hex" in
        [0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F][0-9a-fA-F]) ;;
        *) return 1 ;;
    esac

    b1="${hex%??????}"
    rest="${hex#??}"
    b2="${rest%????}"
    rest="${rest#??}"
    b3="${rest%??}"
    b4="${rest#??}"
    esc="$(printf '\\%03o\\%03o\\%03o\\%03o' "$((0x$b1))" "$((0x$b2))" "$((0x$b3))" "$((0x$b4))")"
    printf '%b' "$esc" | dd of="$file" bs=1 seek="$off" conv=notrunc 2>/dev/null
}

grep_offsets() {
    grep -a -b -o -F "$1" "$2" 2>/dev/null || grep -b -o -F "$1" "$2" 2>/dev/null
}

find_data_app_apk() {
    if [ -n "$APK_ARG" ] && [ -f "$APK_ARG" ]; then
        printf '%s\n' "$APK_ARG"
        return 0
    fi

    if [ "$MODE" = "restore" ] && [ -r "$META_FILE" ]; then
        . "$META_FILE" 2>/dev/null
        if [ -n "$APK_PATH" ] && [ -f "$APK_PATH" ]; then
            printf '%s\n' "$APK_PATH"
            return 0
        fi
    fi

    for apk in /data/app/*/"$PKG"-*/base.apk /data/app/"$PKG"-*/base.apk; do
        [ -f "$apk" ] || continue
        printf '%s\n' "$apk"
        return 0
    done

    if command -v cmd >/dev/null 2>&1; then
        cmd package path "$PKG" 2>/dev/null | sed -n 's/^package://p' | while IFS= read -r apk; do
            case "$apk" in
                /data/app/*/base.apk|/data/app/*.apk)
                    [ -f "$apk" ] && printf '%s\n' "$apk"
                    break
                    ;;
            esac
        done
    fi
}

get_version_code() {
    command -v dumpsys >/dev/null 2>&1 || return 0
    dumpsys package "$PKG" 2>/dev/null | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -n 1
}

entry_info_from_zip() {
    apk="$1"
    entry="$2"

    grep_offsets "$entry" "$apk" | while IFS=: read -r name_off _; do
        case "$name_off" in
            ''|*[!0-9]*) continue ;;
        esac
        hdr_off=$((name_off - 30))
        [ "$hdr_off" -ge 0 ] || continue
        [ "$(hex4_at "$apk" "$hdr_off")" = "504b0304" ] || continue

        method="$(le16_at "$apk" $((hdr_off + 8)))"
        csize="$(le32_at "$apk" $((hdr_off + 18)))"
        nlen="$(le16_at "$apk" $((hdr_off + 26)))"
        xlen="$(le16_at "$apk" $((hdr_off + 28)))"
        [ "$method" = "0" ] || continue
        [ "$nlen" = "${#entry}" ] || continue

        data_off=$((hdr_off + 30 + nlen + xlen))
        printf '%s %s %s\n' "$hdr_off" "$data_off" "$csize"
        break
    done
}

central_header_offset() {
    apk="$1"
    entry="$2"

    grep_offsets "$entry" "$apk" | while IFS=: read -r name_off _; do
        case "$name_off" in
            ''|*[!0-9]*) continue ;;
        esac
        hdr_off=$((name_off - 46))
        [ "$hdr_off" -ge 0 ] || continue
        [ "$(hex4_at "$apk" "$hdr_off")" = "504b0102" ] || continue
        printf '%s\n' "$hdr_off"
        break
    done
}

update_entry_crc() {
    apk="$1"
    entry="$2"
    local_hdr="$3"
    crc_hex="$4"

    if ! write_hex4_at "$apk" $((local_hdr + 14)) "$crc_hex"; then
        log_msg "failed to update local CRC for $entry"
        return 1
    fi

    central_hdr="$(central_header_offset "$apk" "$entry")"
    if [ -z "$central_hdr" ]; then
        log_msg "failed to locate central header for $entry"
        return 1
    fi
    if ! write_hex4_at "$apk" $((central_hdr + 16)) "$crc_hex"; then
        log_msg "failed to update central CRC for $entry"
        return 1
    fi

    log_msg "$entry: wrote CRC $crc_hex"
    return 0
}

load_or_create_meta() {
    apk="$1"
    size="$(file_size "$apk")"

    if [ -r "$META_FILE" ]; then
        . "$META_FILE" 2>/dev/null
        if [ "$APK_PATH" = "$apk" ] && [ "$APK_SIZE" = "$size" ] &&
                [ -n "$CLASSES2_HDR" ] && [ -n "$HTP_JSON_HDR" ] &&
                [ -n "$CLASSES2_OFF" ] && [ -n "$CLASSES2_SIZE" ] &&
                [ -n "$HTP_JSON_OFF" ] && [ -n "$HTP_JSON_SIZE" ]; then
            return 0
        fi
    fi

    set -- $(entry_info_from_zip "$apk" "classes2.dex")
    CLASSES2_HDR="$1"
    CLASSES2_OFF="$2"
    CLASSES2_SIZE="$3"
    set -- $(entry_info_from_zip "$apk" "assets/ssgen/data/htp_backend_ext_config.json")
    HTP_JSON_HDR="$1"
    HTP_JSON_OFF="$2"
    HTP_JSON_SIZE="$3"

    if [ -z "$CLASSES2_HDR" ] || [ -z "$CLASSES2_OFF" ] || [ -z "$CLASSES2_SIZE" ] ||
            [ -z "$HTP_JSON_HDR" ] || [ -z "$HTP_JSON_OFF" ] || [ -z "$HTP_JSON_SIZE" ]; then
        log_msg "failed to locate stored classes2.dex or htp_backend_ext_config.json in $apk"
        return 1
    fi

    mkdir -p "$STATE_DIR" 2>/dev/null
    cat > "$META_FILE" <<EOF
APK_PATH='$apk'
APK_SIZE='$size'
CLASSES2_HDR='$CLASSES2_HDR'
CLASSES2_OFF='$CLASSES2_OFF'
CLASSES2_SIZE='$CLASSES2_SIZE'
HTP_JSON_HDR='$HTP_JSON_HDR'
HTP_JSON_OFF='$HTP_JSON_OFF'
HTP_JSON_SIZE='$HTP_JSON_SIZE'
EOF
    chown system system "$META_FILE" 2>/dev/null
    chmod 0600 "$META_FILE" 2>/dev/null
    restore_path "$META_FILE"
    return 0
}

patch_range() {
    apk="$1"
    off="$2"
    size="$3"
    from="$4"
    to="$5"
    label="$6"

    if [ "${#from}" -ne "${#to}" ]; then
        log_msg "skip $label: unequal replacement lengths"
        return 1
    fi

    tmp="$STATE_DIR/range.$$"
    offsets="$STATE_DIR/offsets.$$"
    rm -f "$tmp" "$offsets"
    mkdir -p "$STATE_DIR" 2>/dev/null

    if ! dd if="$apk" of="$tmp" bs=1 skip="$off" count="$size" 2>/dev/null; then
        log_msg "failed to read $label range"
        rm -f "$tmp" "$offsets"
        return 1
    fi

    grep_offsets "$from" "$tmp" > "$offsets" 2>/dev/null || true
    count="$(wc -l < "$offsets" 2>/dev/null | tr -d ' ')"
    if [ -z "$count" ] || [ "$count" = "0" ]; then
        log_msg "$label: no '$from' occurrences"
        rm -f "$tmp" "$offsets"
        return 0
    fi

    while IFS=: read -r rel _; do
        case "$rel" in
            ''|*[!0-9]*) continue ;;
        esac
        abs=$((off + rel))
        if ! printf '%s' "$to" | dd of="$apk" bs=1 seek="$abs" conv=notrunc 2>/dev/null; then
            log_msg "failed writing $label at $abs"
            rm -f "$tmp" "$offsets"
            return 1
        fi
    done < "$offsets"

    log_msg "$label: replaced $count occurrence(s) '$from' -> '$to'"
    rm -f "$tmp" "$offsets"
    return 0
}

clear_runtime_caches() {
    apk="$1"
    app_dir="$(dirname "$apk")"

    command -v am >/dev/null 2>&1 && am force-stop "$PKG" >/dev/null 2>&1
    rm -rf "$app_dir/oat" "$app_dir"/oat_* 2>/dev/null
    find /data/dalvik-cache -type f -name "*com.samsung.android.offline.languagemodel*" -delete 2>/dev/null
    find /data/system/package_cache -type f \( -iname "*OfflineLanguageModel*" -o -iname "*languagemodel*" -o -iname "*offline*" \) -delete 2>/dev/null
}

apply_patch_bytes() {
    apk="$1"

    version="$(get_version_code)"
    if [ -n "$version" ] && [ "$version" != "$TARGET_VERSION" ]; then
        log_msg "skip apply: versionCode=$version target=$TARGET_VERSION"
        return 0
    fi

    load_or_create_meta "$apk" || return 1

    patch_range "$apk" "$CLASSES2_OFF" "$CLASSES2_SIZE" "qc_sm8850" "qc_sm8550" "classes2 device flavor" || return 1
    patch_range "$apk" "$CLASSES2_OFF" "$CLASSES2_SIZE" "libQnnHtpV81Skel.so" "libQnnHtpV73Skel.so" "classes2 qnn skel" || return 1
    patch_range "$apk" "$HTP_JSON_OFF" "$HTP_JSON_SIZE" '"soc_id": 87' '"soc_id": 43' "htp soc id" || return 1
    patch_range "$apk" "$HTP_JSON_OFF" "$HTP_JSON_SIZE" '"dsp_arch": "v81"' '"dsp_arch": "v73"' "htp dsp arch" || return 1
    update_entry_crc "$apk" "classes2.dex" "$CLASSES2_HDR" "$CLASSES2_CRC_PATCHED" || return 1
    update_entry_crc "$apk" "assets/ssgen/data/htp_backend_ext_config.json" "$HTP_JSON_HDR" "$HTP_JSON_CRC_PATCHED" || return 1

    clear_runtime_caches "$apk"
    restore_path "$apk"
    sync
    log_msg "apply complete: $apk"
}

restore_original_bytes() {
    apk="$1"

    if [ ! -r "$META_FILE" ]; then
        log_msg "skip restore: no metadata"
        return 0
    fi
    . "$META_FILE" 2>/dev/null
    if [ "$APK_PATH" != "$apk" ]; then
        log_msg "skip restore: metadata path mismatch"
        return 0
    fi

    patch_range "$apk" "$CLASSES2_OFF" "$CLASSES2_SIZE" "qc_sm8550" "qc_sm8850" "restore classes2 device flavor" || return 1
    patch_range "$apk" "$CLASSES2_OFF" "$CLASSES2_SIZE" "libQnnHtpV73Skel.so" "libQnnHtpV81Skel.so" "restore classes2 qnn skel" || return 1
    patch_range "$apk" "$HTP_JSON_OFF" "$HTP_JSON_SIZE" '"soc_id": 43' '"soc_id": 87' "restore htp soc id" || return 1
    patch_range "$apk" "$HTP_JSON_OFF" "$HTP_JSON_SIZE" '"dsp_arch": "v73"' '"dsp_arch": "v81"' "restore htp dsp arch" || return 1
    update_entry_crc "$apk" "classes2.dex" "$CLASSES2_HDR" "$CLASSES2_CRC_ORIG" || return 1
    update_entry_crc "$apk" "assets/ssgen/data/htp_backend_ext_config.json" "$HTP_JSON_HDR" "$HTP_JSON_CRC_ORIG" || return 1

    clear_runtime_caches "$apk"
    restore_path "$apk"
    sync
    log_msg "restore complete: $apk"
}

main() {
    mkdir -p "$STATE_DIR" 2>/dev/null
    chown system system "$STATE_DIR" 2>/dev/null
    chmod 0700 "$STATE_DIR" 2>/dev/null
    restore_path "$STATE_DIR"

    apk="$(find_data_app_apk | head -n 1)"
    if [ -z "$apk" ] || [ ! -f "$apk" ]; then
        log_msg "skip $MODE: data APK not found"
        exit 0
    fi

    if [ "${UNICA_OFFLINELM_ALLOW_TEST_PATH:-0}" != "1" ]; then
        case "$apk" in
            /data/app/*) ;;
            *)
                log_msg "skip $MODE: refusing non-data APK $apk"
                exit 0
                ;;
        esac
    fi

    case "$MODE" in
        apply)
            apply_patch_bytes "$apk"
            ;;
        restore)
            restore_original_bytes "$apk"
            ;;
        *)
            log_msg "unknown mode: $MODE"
            exit 1
            ;;
    esac
}

main "$@"
