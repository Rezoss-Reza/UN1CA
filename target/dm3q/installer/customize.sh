SCRIPT_FILE="$TMP_DIR/META-INF/com/google/android/updater-script"
UPDATE_BINARY="$TMP_DIR/META-INF/com/google/android/update-binary"
UPDATE_BINARY_WRAPPER="$SRC_DIR/target/$TARGET_CODENAME/installer/update-binary-wrapper.sh"
DFE_SCRIPT="$SRC_DIR/target/$TARGET_CODENAME/installer/root/unica/dfe/dfe-maintain.sh"

if [ -f "$SCRIPT_FILE" ]; then
    LOG "- Adding package parser cache invalidation"
    {
        echo 'ui_print("Invalidating Android package parser cache...");'
        echo 'run_program("/sbin/sh", "-c", "mount /data 2>/dev/null || true; rm -rf /data/system/package_cache /data/system/package_cache_*");'
    } >> "$SCRIPT_FILE" || return 1
fi

if [ -f "$UPDATE_BINARY_WRAPPER" ] && [ -f "$DFE_SCRIPT" ]; then
    LOG "- Adding dm3q auto DFE installer wrapper"
    mv "$UPDATE_BINARY" "$UPDATE_BINARY.unica" || return 1
    cp -a "$UPDATE_BINARY_WRAPPER" "$UPDATE_BINARY" || return 1
    chmod 0755 "$UPDATE_BINARY" || return 1
fi

unset SCRIPT_FILE UPDATE_BINARY UPDATE_BINARY_WRAPPER DFE_SCRIPT
