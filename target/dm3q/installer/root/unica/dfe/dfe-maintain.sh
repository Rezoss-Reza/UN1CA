#!/sbin/sh
#
# Auto DFE maintainer for dm3q recovery installs.
# It never formats /data. Encrypted or ambiguous existing data is left intact.

OUTFD="$1"
ZIPFILE="$2"

WORKDIR="/data/local/tmp"
TMPDIR="$WORKDIR/dfework"
TOOLS="$TMPDIR/tools"
BUSYBOX="$TOOLS/busybox"
LPDUMP_BIN="/system/bin/lpdump"
LPUNPACK_DIR="$WORKDIR/lpunpack"
UNPACK_DIR="$WORKDIR/unpack"
SUPER_BLK="/dev/block/by-name/super"

ui_print() {
    if [ -n "$OUTFD" ] && [ -e "/proc/self/fd/$OUTFD" ]; then
        echo "ui_print $1" > "/proc/self/fd/$OUTFD"
        echo "ui_print" > "/proc/self/fd/$OUTFD"
    else
        echo "$1"
    fi
}

cleanup_dfe_work() {
    rm -rf "$TMPDIR" "$LPUNPACK_DIR" "$UNPACK_DIR" \
        "$WORKDIR/vendor_mod.img" "$WORKDIR/super_repacked.img" \
        "$WORKDIR/verify_vendor" "$WORKDIR/postflash_live" \
        /tmp/lpmargs.txt /tmp/lpmake_debug.log /tmp/final_lpmargs_debug.txt
}

zip_requests_fe() {
    local name
    name="$(basename "$ZIPFILE" | tr "[:upper:]" "[:lower:]")"
    case "$name" in
        *-fe*) return 0 ;;
        *) return 1 ;;
    esac
}

mount_data_if_needed() {
    grep -q " /data " /proc/mounts && return 0
    mount /data >/dev/null 2>&1 && return 0
    mount -t f2fs /dev/block/by-name/userdata /data >/dev/null 2>&1 && return 0
    mount -t ext4 /dev/block/by-name/userdata /data >/dev/null 2>&1 && return 0
    return 1
}

data_looks_formatted() {
    local entry

    [ -d /data ] || return 1

    for entry in /data/* /data/.[!.]* /data/..?*; do
        [ -e "$entry" ] || continue
        case "$(basename "$entry")" in
            "."|".."|"lost+found"|"per_boot"|"unencrypted")
                ;;
            *)
                return 1
                ;;
        esac
    done

    return 0
}

sdcard_looks_empty() {
    local dir
    local entry
    local name
    local zip_name

    if [ -d /data/media/0 ]; then
        dir="/data/media/0"
    elif [ -d /sdcard ]; then
        dir="/sdcard"
    elif [ -d /data/media ]; then
        dir="/data/media"
    else
        return 1
    fi

    zip_name="$(basename "$ZIPFILE")"

    for entry in "$dir"/* "$dir"/.[!.]* "$dir"/..?*; do
        [ -e "$entry" ] || continue
        name="$(basename "$entry")"
        case "$name" in
            "."|".."|"$zip_name"|UN1CA_*.zip)
                ;;
            *)
                return 1
                ;;
        esac
    done

    return 0
}

should_run_dfe() {
    if ! mount_data_if_needed; then
        ui_print "[DFE] /data is not mountable; assuming encrypted or unavailable."
        return 1
    fi

    if [ -d /data/media ]; then
        if sdcard_looks_empty; then
            if zip_requests_fe; then
                ui_print "[DFE] /sdcard has no user data and zip name requests FE."
                return 1
            fi

            ui_print "[DFE] /sdcard has no user data; maintaining decrypted install."
            return 0
        fi

        ui_print "[DFE] /data/media contains user data; maintaining decrypted install."
        return 0
    fi

    if data_looks_formatted; then
        if zip_requests_fe; then
            ui_print "[DFE] /data looks freshly formatted, but zip name requests FE."
            return 1
        fi

        ui_print "[DFE] /data looks freshly formatted; maintaining decrypted install."
        return 0
    fi

    ui_print "[DFE] /data is not clearly decrypted; leaving encryption untouched."
    return 1
}

prepare_tools() {
    cleanup_dfe_work
    mkdir -p "$TOOLS" "$LPUNPACK_DIR" "$UNPACK_DIR" || return 1
    unzip -oqj "$ZIPFILE" "unica/dfe/tools/*" -d "$TOOLS" || return 1
    chmod -R 0755 "$TOOLS"

    for tool in busybox extract.erofs64 mkfs.erofs lpmake detect_slot.sh gen_lpmake_args.sh; do
        [ -x "$TOOLS/$tool" ] || {
            ui_print "[DFE] Missing tool: $tool"
            return 1
        }
    done

    if [ ! -x "$LPDUMP_BIN" ]; then
        LPDUMP_BIN="$TOOLS/lpdump"
    fi
    [ -x "$LPDUMP_BIN" ] || {
        ui_print "[DFE] Missing tool: lpdump"
        return 1
    }
    export LPDUMP_BIN

    return 0
}

patch_vendor_fstab() {
    local fstab_files
    local fstab
    local changed=1

    fstab_files="$(grep -rlE "fileencryption|forceencrypt" "$UNPACK_DIR/vendor/etc" 2>/dev/null)"
    [ -n "$fstab_files" ] || {
        ui_print "[DFE] No encryption flags found; vendor already looks DFE-ready."
        return 0
    }

    for fstab in $fstab_files; do
        [ -f "$fstab" ] || continue
        ui_print "[DFE] Patching $(basename "$fstab")"
        "$BUSYBOX" sed -i \
            -e 's/^\([^#].*\)fileencryption=[^,]*\(.*\)$/# &\n\1encryptable\2/g' \
            -e 's/^\([^#].*\)forceencrypt=[^,]*\(.*\)$/# &\n\1encryptable\2/g' \
            "$fstab" || return 1
        changed=0
    done

    return "$changed"
}

copy_dynamic_images_from_mapper() {
    local partitions
    local partition
    local mapper
    local image

    partitions="$("$LPDUMP_BIN" "$SUPER_BLK" | awk '
        $1 == "Name:" {
            name = $2
            next
        }
        $1 == "Group:" && name != "" {
            print name
            name = ""
        }
    ')"

    [ -n "$partitions" ] || {
        ui_print "[DFE] Failed to read dynamic partition list."
        return 1
    }

    for partition in $partitions; do
        mapper="/dev/block/mapper/$partition"
        image="$LPUNPACK_DIR/$partition.img"

        [ -b "$mapper" ] || {
            ui_print "[DFE] Missing mapped partition: $partition"
            return 1
        }

        dd if="$mapper" of="$image" bs=1M >/dev/null 2>&1 || {
            ui_print "[DFE] Failed to copy mapped partition: $partition"
            return 1
        }
    done

    return 0
}

run_dfe() {
    local slot
    local vendor_part
    local vendor_img
    local fs_options
    local uuid
    local timestamp
    local fs_config
    local file_contexts
    local metadata_slots
    local lpmargs
    local super_size

    prepare_tools || return 1

    [ -b "$SUPER_BLK" ] || {
        ui_print "[DFE] Missing $SUPER_BLK"
        return 1
    }

    slot="$("$TOOLS/detect_slot.sh")" || {
        ui_print "[DFE] Failed to detect logical vendor slot."
        return 1
    }
    vendor_part="vendor${slot}"
    vendor_img="$LPUNPACK_DIR/${vendor_part}.img"

    ui_print "[DFE] Copying mapped dynamic partitions..."
    copy_dynamic_images_from_mapper || return 1

    [ -f "$vendor_img" ] || {
        ui_print "[DFE] Missing $vendor_part image."
        return 1
    }

    if [ "$vendor_img" != "$LPUNPACK_DIR/vendor.img" ]; then
        cp -f "$vendor_img" "$LPUNPACK_DIR/vendor.img" || return 1
    fi

    ui_print "[DFE] Extracting vendor..."
    "$TOOLS/extract.erofs64" -i "$LPUNPACK_DIR/vendor.img" -x -f -o "$UNPACK_DIR" >/dev/null 2>&1 || {
        ui_print "[DFE] vendor extraction failed."
        return 1
    }

    patch_vendor_fstab || return 1

    rm -f "$UNPACK_DIR/vendor/etc/recovery-resource.dat" \
        "$UNPACK_DIR/vendor/recovery-from-boot.p"

    fs_options="$(find "$UNPACK_DIR/config" -name "vendor_fs_options" | head -n 1)"
    fs_config="$UNPACK_DIR/config/vendor_fs_config"
    file_contexts="$UNPACK_DIR/config/vendor_file_contexts"

    [ -f "$fs_options" ] || {
        ui_print "[DFE] vendor_fs_options not found."
        return 1
    }
    [ -f "$fs_config" ] || {
        ui_print "[DFE] vendor_fs_config not found."
        return 1
    }
    [ -f "$file_contexts" ] || {
        ui_print "[DFE] vendor_file_contexts not found."
        return 1
    }

    uuid="$(awk '/^mkfs.erofs options:/ { for (i = 1; i <= NF; i++) if ($i == "-U") print $(i + 1) }' "$fs_options" | head -n 1)"
    timestamp="$(awk '/^mkfs.erofs options:/ { for (i = 1; i <= NF; i++) if ($i == "-T") print $(i + 1) }' "$fs_options" | head -n 1)"

    ui_print "[DFE] Repacking vendor..."
    set -- --mount-point=vendor -z lz4
    [ -n "$uuid" ] && set -- "$@" -U "$uuid"
    [ -n "$timestamp" ] && set -- "$@" -T "$timestamp"
    set -- "$@" --file-contexts="$file_contexts" --fs-config-file="$fs_config" \
        "$WORKDIR/vendor_mod.img" "$UNPACK_DIR/vendor"

    "$TOOLS/mkfs.erofs" "$@" >/dev/null 2>&1 || {
        ui_print "[DFE] mkfs.erofs failed."
        return 1
    }

    cp -f "$WORKDIR/vendor_mod.img" "$vendor_img" || return 1

    metadata_slots="$("$LPDUMP_BIN" "$SUPER_BLK" | awk '/Metadata slot count/ { print $NF; exit }')"
    [ -n "$metadata_slots" ] || metadata_slots=2

    "$TOOLS/gen_lpmake_args.sh" "$LPUNPACK_DIR" "$SUPER_BLK" > /tmp/lpmargs.txt || {
        ui_print "[DFE] Failed to calculate super layout."
        return 1
    }
    grep -q -- "--partition" /tmp/lpmargs.txt || {
        ui_print "[DFE] Super layout has no partitions."
        return 1
    }
    lpmargs="$(sed 's/\\//g' /tmp/lpmargs.txt)"

    super_size="$("$BUSYBOX" blockdev --getsize64 "$SUPER_BLK")"
    [ -n "$super_size" ] || {
        ui_print "[DFE] Failed to read super size."
        return 1
    }

    ui_print "[DFE] Repacking super..."
    # shellcheck disable=SC2086
    "$TOOLS/lpmake" \
        --metadata-size 65536 \
        --metadata-slots "$metadata_slots" \
        --super-name super \
        --device "super:$super_size" \
        $lpmargs \
        --output "$WORKDIR/super_repacked.img" >/tmp/lpmake_debug.log 2>&1 || {
            ui_print "[DFE] lpmake failed."
            return 1
        }

    ui_print "[DFE] Flashing patched super..."
    dd if="$WORKDIR/super_repacked.img" of="$SUPER_BLK" bs=1M >/dev/null 2>&1 || {
        ui_print "[DFE] Failed to flash super."
        return 1
    }
    sync

    ui_print "[DFE] Decryption fstab patch applied."
    return 0
}

ui_print "[DFE] Auto detection started."

if ! should_run_dfe; then
    cleanup_dfe_work
    ui_print "[DFE] Skipped."
    exit 0
fi

run_dfe
STATUS="$?"
cleanup_dfe_work

if [ "$STATUS" != "0" ]; then
    ui_print "[DFE] Failed."
    exit "$STATUS"
fi

ui_print "[DFE] Done."
exit 0
