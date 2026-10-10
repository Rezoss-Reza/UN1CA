#!/sbin/sh
#
# dm3q recovery installer wrapper.
# Runs the stock UN1CA updater first, then optionally runs the DFE maintainer.

API="$1"
OUTFD="$2"
ZIPFILE="$3"

TMPDIR="/tmp/unica-installer"
ORIGINAL="$TMPDIR/META-INF/com/google/android/update-binary.unica"
DFE_SCRIPT="$TMPDIR/unica/dfe/dfe-maintain.sh"

ui_print() {
    if [ -n "$OUTFD" ] && [ -e "/proc/self/fd/$OUTFD" ]; then
        echo "ui_print $1" > "/proc/self/fd/$OUTFD"
        echo "ui_print" > "/proc/self/fd/$OUTFD"
    else
        echo "$1"
    fi
}

rm -rf "$TMPDIR"
mkdir -p "$TMPDIR" || exit 1

unzip -oq "$ZIPFILE" "META-INF/com/google/android/update-binary.unica" -d "$TMPDIR" || {
    ui_print "E: Missing wrapped UN1CA updater"
    exit 1
}
chmod 0755 "$ORIGINAL"

# OTA-only preflight; full release installation keeps its existing behavior.
OTA_HOOKS="$TMPDIR/unica/ota/hooks.sh"
OTA_ACTIVE=false
if unzip -l "$ZIPFILE" | grep -q 'unica/ota/update.properties'; then
    unzip -oq "$ZIPFILE" 'unica/ota/hooks.sh' -d "$TMPDIR" || exit 1
    [ -f "$OTA_HOOKS" ] || exit 1
    . "$OTA_HOOKS"
    OTA_ACTIVE=true
    ota_preflight || exit 1
fi

"$ORIGINAL" "$API" "$OUTFD" "$ZIPFILE"
STATUS="$?"
if [ "$STATUS" != "0" ]; then
    $OTA_ACTIVE && ota_complete
    exit "$STATUS"
fi

if ! unzip -oq "$ZIPFILE" "unica/dfe/dfe-maintain.sh" -d "$TMPDIR"; then
    if $OTA_ACTIVE; then STATUS=1; ota_complete; exit 1; fi
    exit 0
fi
chmod 0755 "$DFE_SCRIPT"

sh "$DFE_SCRIPT" "$OUTFD" "$ZIPFILE"
STATUS="$?"

$OTA_ACTIVE && ota_complete
rm -rf "$TMPDIR"
exit "$STATUS"
