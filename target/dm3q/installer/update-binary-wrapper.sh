#!/sbin/sh
#
# dm3q recovery installer wrapper.
# Runs the stock UN1CA updater first, then optionally runs the DFE maintainer.

API="$1"
OUTFD="$2"
ZIPFILE="$3"

# Filled at packaging time only for make_rom -sha.
INTEGRITY_SHA256=""

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

# Verify all installation payloads before executing the updater or OTA hooks.
if [ -n "$INTEGRITY_SHA256" ]; then
    ui_print "Checking ROM SHA-256 integrity. Please wait..."
    command -v sha256sum >/dev/null 2>&1 || {
        ui_print "E: Recovery lacks sha256sum. Installation stopped."
        exit 1
    }
    MANIFEST="$TMPDIR/sha256.list"
    unzip -p "$ZIPFILE" unica/integrity/sha256.list > "$MANIFEST" || {
        ui_print "E: Missing or corrupt integrity manifest. Installation stopped."
        exit 1
    }
    ACTUAL="$(sha256sum "$MANIFEST")" || exit 1
    [ "${ACTUAL%% *}" = "$INTEGRITY_SHA256" ] || {
        ui_print "E: Integrity manifest checksum failed. Download the ZIP again."
        exit 1
    }
    FIFO="$TMPDIR/integrity-stream"
    mkfifo "$FIFO" || exit 1
    COUNT=0
    while IFS= read -r ROW; do
        EXPECTED="${ROW%% *}"
        ENTRY="${ROW#*  }"
        case "$EXPECTED" in *[!0-9a-f]*|'') exit 1;; esac
        [ "${#EXPECTED}" = 64 ] || exit 1
        [ "$ENTRY" != "$ROW" ] && [ -n "$ENTRY" ] || exit 1
        unzip -p "$ZIPFILE" "$ENTRY" > "$FIFO" &
        EXTRACT_PID=$!
        ACTUAL="$(sha256sum < "$FIFO")"
        HASH_STATUS=$?
        wait "$EXTRACT_PID"
        EXTRACT_STATUS=$?
        if [ "$HASH_STATUS" != 0 ] || [ "$EXTRACT_STATUS" != 0 ] || [ "${ACTUAL%% *}" != "$EXPECTED" ]; then
            ui_print "E: ZIP integrity failed: $ENTRY"
            ui_print "E: Download the ZIP again. No partition writes performed."
            exit 1
        fi
        COUNT=$((COUNT + 1))
    done < "$MANIFEST"
    [ "$COUNT" -gt 0 ] || exit 1
    rm -f "$FIFO"
    ui_print "ROM integrity verified."
fi

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
