ADB=/mnt/c/platform-tools/adb.exe
CAPTURE_DIR="$HOME/hermes-capture-$(TZ=Europe/Paris date +%Y%m%d-%H%M%S)"
mkdir -p "$CAPTURE_DIR"

while true; do
    "$ADB" wait-for-device

    # Wait until the connected device is Android rather than TWRP.
    RECOVERY_VERSION=$("$ADB" shell getprop ro.twrp.version 2>/dev/null | tr -d '\r')
    if [ -n "$RECOVERY_VERSION" ]; then
        sleep 2
        continue
    fi

    STAMP=$(TZ=Europe/Paris date +%Y%m%d-%H%M%S)
    "$ADB" logcat -b all -v threadtime '*:V' \
        > "$CAPTURE_DIR/logcat-$STAMP.txt" 2>&1

    sleep 1
done