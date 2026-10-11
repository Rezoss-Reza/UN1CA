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
LPUNPACK_DIR="$TMPDIR/images"
UNPACK_DIR="$TMPDIR/unpack"
SUPER_BLK="/dev/block/by-name/super"
MAPPER=""
DFE_MAP="unica-dfe-vendor-$$"
DFE_MAPPED=0
DFE_LOG="/tmp/unica-dfe.log"

ui_print() {
    if [ -n "$OUTFD" ] && [ -e "/proc/self/fd/$OUTFD" ]; then
        echo "ui_print $1" > "/proc/self/fd/$OUTFD"
        echo "ui_print" > "/proc/self/fd/$OUTFD"
    else
        echo "$1"
    fi
}

cleanup_dfe_work() {
    rm -rf "$TMPDIR"
}

release_vendor_mapping() {
    [ "$DFE_MAPPED" = 1 ] || return 0
    "$MAPPER" delete "$DFE_MAP" >>"$DFE_LOG" 2>&1 || return 1
    DFE_MAPPED=0
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
    if [ -e "$TMPDIR" ]; then
        ui_print "[DFE] Previous workspace exists: $TMPDIR; preserve or remove it before retrying."
        return 1
    fi
    : > "$DFE_LOG"
    mkdir -p "$TOOLS" "$LPUNPACK_DIR" "$UNPACK_DIR" || return 1
    unzip -oqj "$ZIPFILE" "unica/dfe/tools/*" -d "$TOOLS" || return 1
    chmod -R 0755 "$TOOLS"

    for tool in busybox extract.erofs64 mkfs.erofs dfe-mapper; do
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
    MAPPER="$TOOLS/dfe-mapper"
    "$MAPPER" help >>"$DFE_LOG" 2>&1 || {
        ui_print "[DFE] Bundled mapping tool cannot run; check $DFE_LOG"
        return 1
    }

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

# Refuse ambiguous slots and unmount every alias of the existing vendor device.
prepare_vendor_mapping() {
    local partitions part real dev mounts point holder
    "$LPDUMP_BIN" "$SUPER_BLK" > "$TMPDIR/lpdump.txt" 2>>"$DFE_LOG" || return 1
    partitions="$(awk '$1 == "Name:" { name = $2 }
        $1 == "Group:" && name ~ /^vendor(_a|_b)?$/ { print name; name = "" }' "$TMPDIR/lpdump.txt")"
    set -- $partitions
    [ "$#" = 1 ] || {
        ui_print "[DFE] Ambiguous vendor layout; refusing to select a slot."
        return 1
    }
    part="$1"
    real="$(readlink -f "/dev/block/mapper/$part")"
    [ -b "$real" ] || {
        ui_print "[DFE] Missing existing vendor mapping."
        return 1
    }
    dev="$(cat "/sys/class/block/${real##*/}/dev")" || return 1
    for holder in /sys/class/block/"${real##*/}"/holders/*; do
        [ -e "$holder" ] || continue
        ui_print "[DFE] Vendor has dependent mappings; reboot recovery before retrying."
        return 1
    done
    mounts="$(awk -v dev="$dev" '$3 == dev { print $5 }' /proc/self/mountinfo)"
    for point in $mounts; do
        umount "$point" >>"$DFE_LOG" 2>&1 || {
            ui_print "[DFE] Cannot unmount vendor: $point"
            return 1
        }
    done
    if awk -v dev="$dev" '$3 == dev { found=1 } END { exit !found }' /proc/self/mountinfo; then
        ui_print "[DFE] Vendor remains mounted; refusing to write."
        return 1
    fi
    # Accept only linear vendor extents on this physical super device.
    # The native helper checks continuity, physical overlap and device bounds.
    awk -v part="$part" '
        $1 == "Name:" { selected = ($2 == part); extents = 0; next }
        selected && $1 == "Extents:" { extents = 1; next }
        /^[[:space:]]*-+/ { extents = 0; next }
        selected && extents && NF {
            if (NF != 6 || $2 != ".." || $4 != "linear" || $5 != "super" ||
                $1 !~ /^[0-9]+$/ || $3 !~ /^[0-9]+$/ || $6 !~ /^[0-9]+$/ || $3 < $1) {
                bad = 1; exit
            }
            printf "%.0f %.0f %.0f\n", $1, $3 - $1 + 1, $6
            count++
        }
        END { if (bad || !count) exit 1 }
    ' "$TMPDIR/lpdump.txt" > "$TMPDIR/vendor.table" || {
        ui_print "[DFE] Unsupported vendor extents; refusing to map."
        return 1
    }
    # A new writable mapping uses current metadata, not cached updater mappings.
    DFE_MAPPED=1
    "$MAPPER" create "$SUPER_BLK" "$DFE_MAP" "$TMPDIR/vendor.table" > "$TMPDIR/mapper.txt" 2>>"$DFE_LOG" || {
        ui_print "[DFE] Cannot create writable vendor mapping; check $DFE_LOG"
        return 1
    }
    vendor_mapper="$(cat "$TMPDIR/mapper.txt")"
    [ -b "$vendor_mapper" ] || return 1
    [ "$("$BUSYBOX" blockdev --getro "$vendor_mapper")" = 0 ] || {
        ui_print "[DFE] Temporary vendor mapping is read-only."
        return 1
    }
    vendor_size="$("$BUSYBOX" blockdev --getsize64 "$vendor_mapper")"
    case "$vendor_size" in ""|*[!0-9]*|0) return 1 ;; esac
    return 0
}

verify_vendor_write() {
    "$BUSYBOX" head -c "$image_size" "$vendor_mapper" > "$TMPDIR/readback.img" 2>>"$DFE_LOG" || return 1
    "$BUSYBOX" cmp "$TMPDIR/vendor_mod.img" "$TMPDIR/readback.img" >>"$DFE_LOG" 2>&1
}

run_dfe() {
    local vendor_mapper
    local vendor_size
    local image_size
    local vendor_img
    local fs_options
    local uuid
    local timestamp
    local fs_config
    local file_contexts

    prepare_tools || return 1

    [ -b "$SUPER_BLK" ] || {
        ui_print "[DFE] Missing $SUPER_BLK"
        return 1
    }

    prepare_vendor_mapping || return 1
    vendor_img="$LPUNPACK_DIR/vendor.img"
    ui_print "[DFE] Copying vendor only..."
    dd if="$vendor_mapper" of="$vendor_img" bs=1M >>"$DFE_LOG" 2>&1 || return 1
    [ "$("$BUSYBOX" stat -c %s "$vendor_img")" = "$vendor_size" ] || return 1

    ui_print "[DFE] Extracting vendor..."
    "$TOOLS/extract.erofs64" -i "$LPUNPACK_DIR/vendor.img" -x -f -o "$UNPACK_DIR" >>"$DFE_LOG" 2>&1 || {
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
    set -- --mount-point=vendor -z lz4hc,9
    [ -n "$uuid" ] && set -- "$@" -U "$uuid"
    [ -n "$timestamp" ] && set -- "$@" -T "$timestamp"
    set -- "$@" --file-contexts="$file_contexts" --fs-config-file="$fs_config" \
        "$TMPDIR/vendor_mod.img" "$UNPACK_DIR/vendor"

    "$TOOLS/mkfs.erofs" "$@" >>"$DFE_LOG" 2>&1 || {
        ui_print "[DFE] mkfs.erofs failed."
        return 1
    }

    image_size="$("$BUSYBOX" stat -c %s "$TMPDIR/vendor_mod.img")"
    case "$image_size" in ""|*[!0-9]*|0) return 1 ;; esac
    # Recovery sh may compare integers as signed 32-bit values. BusyBox awk
    # uses floating-point numbers, exact for byte counts at these sizes.
    # Require a successful fit check before writing; equal sizes are allowed.
    if ! "$BUSYBOX" awk -v image="$image_size" -v capacity="$vendor_size" \
        'BEGIN { exit !((image + 0) <= (capacity + 0)) }' >>"$DFE_LOG" 2>&1; then
        ui_print "[DFE] Vendor size check failed: image $image_size bytes, partition $vendor_size bytes; refusing to write."
        return 1
    fi
    # Reserve space for readback before touching the partition.
    cp "$vendor_img" "$TMPDIR/readback.img" >>"$DFE_LOG" 2>&1 || return 1
    ui_print "[DFE] Flashing vendor only ($image_size bytes)..."
    if dd if="$TMPDIR/vendor_mod.img" of="$vendor_mapper" bs=1M conv=fsync >>"$DFE_LOG" 2>&1 && verify_vendor_write; then
        ui_print "[DFE] Vendor write verified."
    else
        ui_print "[DFE] Vendor write/verification failed; restoring original vendor."
        if dd if="$vendor_img" of="$vendor_mapper" bs=1M conv=fsync >>"$DFE_LOG" 2>&1 &&
            "$BUSYBOX" head -c "$vendor_size" "$vendor_mapper" > "$TMPDIR/readback.img" 2>>"$DFE_LOG" &&
            "$BUSYBOX" cmp "$vendor_img" "$TMPDIR/readback.img" >>"$DFE_LOG" 2>&1; then
            ui_print "[DFE] Original vendor restored and verified."
        else
            ui_print "[DFE] CRITICAL: vendor restore failed; reflash ROM before booting."
        fi
        return 1
    fi
    sync

    ui_print "[DFE] Decryption fstab patch applied."
    return 0
}

ui_print "[DFE] Auto detection started."

if ! should_run_dfe; then
    ui_print "[DFE] Skipped."
    exit 0
fi

run_dfe
STATUS="$?"
if ! release_vendor_mapping; then
    ui_print "[DFE] Cannot release temporary vendor mapping; reboot recovery."
    STATUS=1
fi

if [ "$STATUS" != "0" ]; then
    ui_print "[DFE] Failed. Diagnostics: $DFE_LOG; workspace: $TMPDIR"
    exit "$STATUS"
fi

cleanup_dfe_work
ui_print "[DFE] Done."
ui_print "DFE complete. Reboot recovery before installing additional ZIPs or mounting vendor."
exit 0
