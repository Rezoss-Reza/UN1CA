#!/sbin/sh
# SPDX-License-Identifier: Apache-2.0
# Standalone dm3q DFE entrypoint: no ROM updater and no super repack.

API="$1"
OUTFD="$2"
ZIPFILE="$3"
MODE="${4:-install}"
STANDALONE_TMP="/tmp/unica-dfe-standalone-$$"

ui_print() {
    if [ -n "$OUTFD" ] && [ -e "/proc/self/fd/$OUTFD" ]; then
        printf 'ui_print %s\nui_print\n' "$1" > "/proc/self/fd/$OUTFD"
    else
        printf '%s\n' "$1"
    fi
}
fail() { ui_print "[DFE] $1"; exit 1; }
cleanup() { rm -rf "$STANDALONE_TMP"; }

case "$MODE" in install|--check) ;; *) fail "Unknown mode." ;; esac
case "$(getprop ro.product.device)" in
    dm3q|dm3q*|fox_dm3q) ;;
    *) fail "This ZIP supports dm3q/S23 Ultra recovery only." ;;
esac
case "$(uname -m)" in aarch64) ;; *) fail "ARM64 recovery required." ;; esac
case "$(getprop ro.boot.verifiedbootstate)" in
    green|yellow) fail "Verified boot is enforced; refusing to modify vendor." ;;
esac
[ -b /dev/block/by-name/super ] || fail "Physical super partition not found."
[ -b /dev/block/mapper/vendor ] || fail "Unsuffixed vendor mapping required; reboot recovery after the ROM install."
[ -z "$(getprop ro.boot.slot_suffix)" ] || fail "Slotted layouts are not supported by this dm3q ZIP."
[ ! -e "$STANDALONE_TMP" ] || fail "Temporary directory already exists."
mkdir -p "$STANDALONE_TMP" || fail "Cannot create temporary directory."
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
unzip -oq "$ZIPFILE" 'unica/dfe/*' -d "$STANDALONE_TMP" || fail "Cannot extract DFE payload."
TOOLS="$STANDALONE_TMP/unica/dfe/tools"
chmod 0755 "$TOOLS/"* || fail "Cannot prepare tools."
for tool in busybox dfe-mapper extract.erofs64 mkfs.erofs lpdump; do
    [ -x "$TOOLS/$tool" ] || fail "Missing tool: $tool"
done
"$TOOLS/dfe-mapper" help >/dev/null 2>&1 || fail "Bundled mapper cannot run."
"$TOOLS/busybox" true || fail "Bundled busybox cannot run."
"$TOOLS/lpdump" /dev/block/by-name/super > "$STANDALONE_TMP/layout.txt" 2>&1 || fail "Bundled lpdump cannot read super."
# Guard the standalone ZIP against unfamiliar/suffixed vendor layouts.
PARTS="$(awk '$1 == "Name:" {name=$2} $1 == "Group:" && name ~ /^vendor(_a|_b)?$/ {print name; name=""}' "$STANDALONE_TMP/layout.txt")"
[ "$PARTS" = vendor ] || fail "Expected a single unsuffixed vendor partition."
"$TOOLS/busybox" dd if=/dev/block/mapper/vendor of="$STANDALONE_TMP/header.bin" bs=4096 count=1 >/dev/null 2>&1 || fail "Cannot read vendor header."
MAGIC="$("$TOOLS/busybox" od -An -tx1 -j1024 -N4 "$STANDALONE_TMP/header.bin" | tr -d ' \r\n')"
[ "$MAGIC" = e2e1f5e0 ] || fail "Vendor is not EROFS; unsupported ROM."
ui_print "[DFE] Standalone vendor-only DFE for dm3q."
ui_print "[DFE] No whole-super repack. Existing data is never formatted."
if [ "$MODE" = --check ]; then
    ui_print "[DFE] Check passed: device, layout, EROFS header, and packaged tools."
    ui_print "[DFE] Check only: no partition writes or vendor unmounts performed."
    exit 0
fi
sh "$STANDALONE_TMP/unica/dfe/dfe-maintain.sh" "$OUTFD" "$ZIPFILE"
exit "$?"
