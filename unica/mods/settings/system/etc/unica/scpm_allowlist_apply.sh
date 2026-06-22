#!/system/bin/sh

PACKAGES_KEY="unica_scpm_allowlist_packages"
REGION_KEY="unica_scpm_region_bypass"
PKG_FILE="/data/system/unica_scpm_allowlist_packages.txt"
FLAGS_FILE="/data/system/unica_scpm_allowlist_flags"
NOTI_FILE="/data/system/notification_ai_scpm_policies.json"
AUDIO_JSON="/data/system/unica_audiomirroring_allowlist.json"
LOG_FILE="/data/system/unica_scpm_allowlist_apply.log"
AUDIO_PACKAGE="com.samsung.android.audiomirroring"
AUDIO_DATA_DIR="/data/user/0/$AUDIO_PACKAGE"
AUDIO_PREF_DIR="$AUDIO_DATA_DIR/shared_prefs"
AUDIO_PREF_FILE="$AUDIO_PREF_DIR/AudioMirroring.xml"

log_msg() {
    printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null)" "$*" >> "$LOG_FILE" 2>/dev/null
}

REGION_BYPASS="$(settings get system "$REGION_KEY" 2>/dev/null)"
if [ -z "$REGION_BYPASS" ] || [ "$REGION_BYPASS" = "null" ]; then
    REGION_BYPASS="1"
    settings put system "$REGION_KEY" 1 >/dev/null 2>&1
fi

if [ "$REGION_BYPASS" = "0" ] || [ "$REGION_BYPASS" = "false" ]; then
    setprop persist.sys.unica.scpm_region_bypass false
    REGION_ENABLED="false"
else
    setprop persist.sys.unica.scpm_region_bypass true
    REGION_ENABLED="true"
fi

mkdir -p /data/system
log_msg "start region_bypass=$REGION_ENABLED"

cat > "$FLAGS_FILE" <<EOF
region_bypass=$REGION_ENABLED
EOF
chown system system "$FLAGS_FILE"
chmod 0600 "$FLAGS_FILE"
restorecon "$FLAGS_FILE" 2>/dev/null

PACKAGES_RAW="$(settings get system "$PACKAGES_KEY" 2>/dev/null)"
if [ -z "$PACKAGES_RAW" ] || [ "$PACKAGES_RAW" = "null" ]; then
    log_msg "no packages in $PACKAGES_KEY"
    exit 0
fi

printf '%s\n' "$PACKAGES_RAW" | awk '
    /^[A-Za-z0-9_]+([.][A-Za-z0-9_]+)+$/ && !seen[$0]++ { print }
' > "$PKG_FILE"

if [ ! -s "$PKG_FILE" ]; then
    log_msg "no valid package names after filtering"
    rm -f "$PKG_FILE"
    exit 0
fi

PKG_COUNT="$(wc -l < "$PKG_FILE" 2>/dev/null)"
chown system system "$PKG_FILE"
chmod 0600 "$PKG_FILE"
restorecon "$PKG_FILE" 2>/dev/null
log_msg "prepared $PKG_COUNT packages"

JSON="$(
    awk '
        BEGIN { printf "["; first = 1 }
        {
            value = $0
            gsub(/\\/, "\\\\", value)
            gsub(/"/, "\\\"", value)
            if (!first) {
                printf ","
            }
            printf "\"%s\"", value
            first = 0
        }
        END { print "]" }
    ' "$PKG_FILE"
)"

cat > "$AUDIO_JSON" <<EOF
$JSON
EOF
chown system system "$AUDIO_JSON"
chmod 0600 "$AUDIO_JSON"
restorecon "$AUDIO_JSON" 2>/dev/null

cat > "$NOTI_FILE" <<EOF
{
  "policy_version": 1000000000,
  "summary_policies": [10, 3, 3, 9, 12, 40],
  "summary_exclude_keywords": "\uBD80\uACE0, \u8A03\u544A, \uC0AC\uB9DD, \uBCC4\uC138",
  "summary_allow_list": $JSON,
  "except_scenarios": []
}
EOF
chown system system "$NOTI_FILE"
chmod 0600 "$NOTI_FILE"
restorecon "$NOTI_FILE" 2>/dev/null

APP_UID="$(cmd package list packages -U "$AUDIO_PACKAGE" 2>/dev/null | sed -n 's/.*uid://p' | head -n 1)"
if [ -z "$APP_UID" ]; then
    APP_UID="$(cmd package list packages -U 2>/dev/null | sed -n "s/^package:$AUDIO_PACKAGE uid://p" | head -n 1)"
fi
if [ -z "$APP_UID" ]; then
    APP_UID="$(pm list packages -U "$AUDIO_PACKAGE" 2>/dev/null | sed -n 's/.*uid://p' | head -n 1)"
fi
if [ -z "$APP_UID" ]; then
    APP_UID="$(pm list packages -U 2>/dev/null | sed -n "s/^package:$AUDIO_PACKAGE uid://p" | head -n 1)"
fi
if [ -z "$APP_UID" ] && [ -d "$AUDIO_DATA_DIR" ]; then
    APP_UID="$(stat -c '%u' "$AUDIO_DATA_DIR" 2>/dev/null)"
fi

case "$APP_UID" in
    ''|*[!0-9]*)
        log_msg "cannot resolve uid for $AUDIO_PACKAGE; skipping $AUDIO_PREF_FILE"
        APP_UID=""
        ;;
esac

if [ "$APP_UID" ]; then
    mkdir -p "$AUDIO_PREF_DIR"
    XML_JSON="$(printf '%s' "$JSON" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g')"
    if cat > "$AUDIO_PREF_FILE" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="ALLOW_LIST">$XML_JSON</string>
</map>
EOF
    then
        chown "$APP_UID:$APP_UID" "$AUDIO_DATA_DIR" "$AUDIO_PREF_DIR" "$AUDIO_PREF_FILE" 2>/dev/null
        chmod 0771 "$AUDIO_DATA_DIR" "$AUDIO_PREF_DIR" 2>/dev/null
        chmod 0660 "$AUDIO_PREF_FILE" 2>/dev/null
        restorecon -RF "$AUDIO_DATA_DIR" 2>/dev/null
        log_msg "wrote $AUDIO_PREF_FILE uid=$APP_UID packages=$PKG_COUNT"
    else
        log_msg "failed to write $AUDIO_PREF_FILE uid=$APP_UID"
    fi
fi
