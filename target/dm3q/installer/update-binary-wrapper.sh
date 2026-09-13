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

"$ORIGINAL" "$API" "$OUTFD" "$ZIPFILE"
STATUS="$?"
if [ "$STATUS" != "0" ]; then
    exit "$STATUS"
fi

unzip -oq "$ZIPFILE" "unica/dfe/dfe-maintain.sh" -d "$TMPDIR" || exit 0
chmod 0755 "$DFE_SCRIPT"

sh "$DFE_SCRIPT" "$OUTFD" "$ZIPFILE"
STATUS="$?"

rm -rf "$TMPDIR"
exit "$STATUS"
