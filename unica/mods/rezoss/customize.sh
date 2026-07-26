SKIPUNZIP=1

LOG_STEP_IN "- Rezoss experimental mods"

# =============================================================================
# Base Overlay
# =============================================================================
ADD_TO_WORK_DIR "$MODPATH" "system" "." 0 0 755 "u:object_r:system_file:s0"
ADD_TO_WORK_DIR "$MODPATH" "system_ext" "." 0 0 755 "u:object_r:system_file:s0"
LOG "- Adding optional AOSP zip boot animation support"
ADD_TO_WORK_DIR "$MODPATH/bootanimation_zip" "system" \
    "system/bin/bootanimation_zip" 0 2000 755 "u:object_r:bootanim_exec:s0"
ADD_TO_WORK_DIR "$MODPATH/bootanimation_zip" "system" \
    "system/lib64/libbootanimation.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "$MODPATH/bootanimation_zip" "system" \
    "system/media/bootanimation.zip" 0 0 644 "u:object_r:bootanim_oem_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" \
    "system/etc/default-permissions/default-permissions-com.samsung.android.smartsuggestions.xml" 0 0 644 "u:object_r:system_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" \
    "system/etc/permissions/privapp-permissions-com.samsung.android.smartsuggestions.xml" 0 0 644 "u:object_r:system_file:s0"
LOG "- Adding Samsung Messages for Now Nudge in-app support"
ADD_TO_WORK_DIR "m3qxxx" "system" \
    "system/priv-app/SamsungMessages/SamsungMessages.apk" 0 0 644 "u:object_r:system_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" \
    "system/etc/permissions/privapp-permissions-com.samsung.android.messaging.xml" 0 0 644 "u:object_r:system_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" \
    "system/etc/default-permissions/default-permissions-com.samsung.android.messaging.xml" 0 0 644 "u:object_r:system_file:s0"
APPLY_PATCH "system" "system/priv-app/SamsungMessages/SamsungMessages.apk" \
    "$MODPATH/samsungmessages/SamsungMessages.apk/0001-Advertise-Now-Nudge-in-app-revision.patch"
LOG "- Patching SmartSuggestions Developer Mode access"
REZOSS_SMARTSUGGESTIONS_APK="$WORK_DIR/system/system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk"
REZOSS_SMARTSUGGESTIONS_TMP="$TMP_DIR/rezoss_smartsuggestions_dev_mode"
REZOSS_SMARTSUGGESTIONS_CERT_PREFIX="aosp"
$ROM_IS_OFFICIAL && REZOSS_SMARTSUGGESTIONS_CERT_PREFIX="unica"
EVAL "rm -rf \"$REZOSS_SMARTSUGGESTIONS_TMP\" && mkdir -p \"$REZOSS_SMARTSUGGESTIONS_TMP\""
EVAL "unzip -q -p \"$REZOSS_SMARTSUGGESTIONS_APK\" classes11.dex > \"$REZOSS_SMARTSUGGESTIONS_TMP/classes11.dex\""
EVAL "unzip -q -p \"$REZOSS_SMARTSUGGESTIONS_APK\" classes13.dex > \"$REZOSS_SMARTSUGGESTIONS_TMP/classes13.dex\""
EVAL "unzip -q -p \"$REZOSS_SMARTSUGGESTIONS_APK\" classes14.dex > \"$REZOSS_SMARTSUGGESTIONS_TMP/classes14.dex\""
EVAL "unzip -q -p \"$REZOSS_SMARTSUGGESTIONS_APK\" classes15.dex > \"$REZOSS_SMARTSUGGESTIONS_TMP/classes15.dex\""
EVAL "unzip -q -p \"$REZOSS_SMARTSUGGESTIONS_APK\" AndroidManifest.xml > \"$REZOSS_SMARTSUGGESTIONS_TMP/AndroidManifest.xml\""
EVAL "python3 \"$MODPATH/smartsuggestions/patch_contents_visibility_dex.py\" \"$REZOSS_SMARTSUGGESTIONS_TMP/classes14.dex\""
EVAL "python3 \"$MODPATH/smartsuggestions/patch_now_nudge_dex.py\" \"$REZOSS_SMARTSUGGESTIONS_TMP/classes15.dex\""
EVAL "python3 \"$MODPATH/smartsuggestions/patch_smartsuggestions_dev_mode.py\" \"$REZOSS_SMARTSUGGESTIONS_TMP/classes11.dex\" \"$REZOSS_SMARTSUGGESTIONS_TMP/classes13.dex\" \"$REZOSS_SMARTSUGGESTIONS_TMP/classes15.dex\" \"$REZOSS_SMARTSUGGESTIONS_TMP/AndroidManifest.xml\""
EVAL "cp -a \"$REZOSS_SMARTSUGGESTIONS_APK\" \"$REZOSS_SMARTSUGGESTIONS_TMP/SamsungSmartSuggestions.apk\""
EVAL "cd \"$REZOSS_SMARTSUGGESTIONS_TMP\" && zip -q -0 \"SamsungSmartSuggestions.apk\" classes11.dex classes13.dex classes14.dex classes15.dex AndroidManifest.xml"
EVAL "signapk \"$SRC_DIR/security/${REZOSS_SMARTSUGGESTIONS_CERT_PREFIX}_platform.x509.pem\" \"$SRC_DIR/security/${REZOSS_SMARTSUGGESTIONS_CERT_PREFIX}_platform.pk8\" \"$REZOSS_SMARTSUGGESTIONS_TMP/SamsungSmartSuggestions.apk\" \"$REZOSS_SMARTSUGGESTIONS_TMP/SamsungSmartSuggestions.signed.apk\""
EVAL "mv -f \"$REZOSS_SMARTSUGGESTIONS_TMP/SamsungSmartSuggestions.signed.apk\" \"$REZOSS_SMARTSUGGESTIONS_APK\""
SET_METADATA "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk" 0 0 644 "u:object_r:system_file:s0"
EVAL "rm -rf \"$REZOSS_SMARTSUGGESTIONS_TMP\""
LOG "- Patching SmartSuggestions native library extraction"
REZOSS_SMARTSUGGESTIONS_DECODED="$APKTOOL_DIR/system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk"
EVAL "rm -rf \"$REZOSS_SMARTSUGGESTIONS_DECODED\""
DECODE_APK "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk"
EVAL "sed -i 's/android:extractNativeLibs=\"false\"/android:extractNativeLibs=\"true\"/g' \"$REZOSS_SMARTSUGGESTIONS_DECODED/AndroidManifest.xml\""
if ! grep -q 'android:extractNativeLibs="true"' "$REZOSS_SMARTSUGGESTIONS_DECODED/AndroidManifest.xml"; then
    LOGE "Failed to enable native library extraction for SamsungSmartSuggestions.apk"
    return 1
fi
unset REZOSS_SMARTSUGGESTIONS_DECODED
LOG "- Applying SmartSuggestions China compatibility patch"
APPLY_PATCH "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk" \
    "$MODPATH/smartsuggestions/SamsungSmartSuggestions.apk/0010-China-SmartSuggestions-compatibility.patch"
APPLY_PATCH "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk" \
    "$MODPATH/smartsuggestions/SamsungSmartSuggestions.apk/0011-Enable-Netflix-custom-card-helpers-and-forced-skills.patch"
APPLY_PATCH "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk" \
    "$MODPATH/smartsuggestions/SamsungSmartSuggestions.apk/0012-Bind-event-travel-custom-card-sources.patch"
APPLY_PATCH "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk" \
    "$MODPATH/smartsuggestions/SamsungSmartSuggestions.apk/0013-Allow-calendar-travel-custom-card-prompts.patch"
APPLY_PATCH "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk" \
    "$MODPATH/smartsuggestions/SamsungSmartSuggestions.apk/0016-Use-data-backed-Now-Nudge-reply-fallbacks.patch"
APPLY_PATCH "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk" \
    "$MODPATH/smartsuggestions/SamsungSmartSuggestions.apk/0018-Reuse-OfflineLanguageCore-Suggestion-metadata.patch"
APPLY_PATCH "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk" \
    "$MODPATH/smartsuggestions/SamsungSmartSuggestions.apk/0019-Use-JSON-category-rules-for-Now-Nudge-fallbacks.patch"
APPLY_PATCH "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk" \
    "$MODPATH/smartsuggestions/SamsungSmartSuggestions.apk/0017-Add-Telegram-ContentCapture-allowlist.patch"
APPLY_PATCH "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk" \
    "$MODPATH/smartsuggestions/SamsungSmartSuggestions.apk/0020-Restore-CHN-Now-Nudge-conversation-bridge.patch"
APPLY_PATCH "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk" \
    "$MODPATH/smartsuggestions/SamsungSmartSuggestions.apk/0021-Add-Telegram-class-text-parser-fallback.patch"
APPLY_PATCH "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk" \
    "$MODPATH/smartsuggestions/SamsungSmartSuggestions.apk/0022-Prefer-JSON-fallback-before-AIOS-chat-replies.patch"
APPLY_PATCH "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk" \
    "$MODPATH/smartsuggestions/SamsungSmartSuggestions.apk/0023-Ignore-stale-Now-Nudge-fallback-data.patch"
APPLY_PATCH "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk" \
    "$MODPATH/smartsuggestions/SamsungSmartSuggestions.apk/0014-Allow-Now-Brief-recall-calendar-reminder-fallback.patch"
APPLY_PATCH "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk" \
    "$MODPATH/smartsuggestions/SamsungSmartSuggestions.apk/0015-Force-Now-Brief-non-empty-fallback.patch"
# Temporarily disabled for CHN SmartSuggestions testing.
# LOG "- Patching SmartSuggestions China custom-card feature gates"
# SMALI_PATCH "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk" \
#     "smali_classes15/com/samsung/android/smartsuggestions/featureconfig/rune/Rune.smali" "return" \
#     'getSUPPORT_AI_SUGGESTION_CUSTOM_CARD_CHN()Z' \
#     'true' \
#     > /dev/null
# SMALI_PATCH "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk" \
#     "smali_classes15/com/samsung/android/smartsuggestions/featureconfig/rune/Rune.smali" "return" \
#     'getSUPPORT_AI_SUGGESTION_EDITABLE_CARD_CHN()Z' \
#     'true' \
#     > /dev/null
LOG "- Patching SmartSuggestions Now Nudge experimental runtime gates"
SMALI_PATCH "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk" \
    "smali_classes15/com/samsung/android/smartsuggestions/service/screenintelligence/setting/NowNudgesSettingHelper.smali" "return" \
    'isAvailableAccount(Landroid/content/Context;)Z' \
    'true' \
    > /dev/null
SMALI_PATCH "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk" \
    "smali_classes15/com/samsung/android/smartsuggestions/service/screenintelligence/setting/NowNudgesSettingHelper.smali" "return" \
    'isOnNowNudgesInApp(Landroid/content/Context;)Z' \
    'true' \
    > /dev/null
SMALI_PATCH "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk" \
    "smali_classes15/com/samsung/android/smartsuggestions/service/screenintelligence/setting/NowNudgesSettingHelper.smali" "return" \
    'isNowNudgesSwitchOn(Landroid/content/Context;)Z' \
    'true' \
    > /dev/null
SMALI_PATCH "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk" \
    "smali_classes15/com/samsung/android/smartsuggestions/service/autofill/AutofillNudgeProvider.smali" "return" \
    'checkAutofillSupported()Z' \
    'true' \
    > /dev/null
SMALI_PATCH "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk" \
    "smali_classes15/com/samsung/android/smartsuggestions/service/autofill/AutofillNudgeProvider.smali" "return" \
    'checkKeyboardLocale()Z' \
    'true' \
    > /dev/null
SMALI_PATCH "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk" \
    "smali_classes15/com/samsung/android/smartsuggestions/service/policy/AutofillPolicyManager.smali" "return" \
    'isNudgeAllowed(Landroid/content/ComponentName;)Z' \
    'true' \
    > /dev/null
SMALI_PATCH "system" "system/priv-app/SamsungSmartSuggestions/SamsungSmartSuggestions.apk" \
    "smali_classes15/com/samsung/android/smartsuggestions/service/smartreply/SmartReplyResolver.smali" "return" \
    'getInAppNudgeRevisionFromMessageApp(Landroid/content/Context;)I' \
    '1' \
    > /dev/null
unset REZOSS_SMARTSUGGESTIONS_APK REZOSS_SMARTSUGGESTIONS_TMP REZOSS_SMARTSUGGESTIONS_CERT_PREFIX

# =============================================================================
# Device Care / Smart Manager CN
# =============================================================================
DELETE_FROM_WORK_DIR "system" "system/priv-app/SmartManager_v5"
DELETE_FROM_WORK_DIR "system" "system/app/SmartManager_v6_DeviceSecurity"
DELETE_FROM_WORK_DIR "system" "system/etc/permissions/privapp-permissions-com.samsung.android.lool.xml"
DELETE_FROM_WORK_DIR "system" "system/etc/permissions/signature-permissions-com.samsung.android.lool.xml"
DELETE_FROM_WORK_DIR "system" "system/etc/permissions/privapp-permissions-com.samsung.android.sm.devicesecurity_v6.xml"

SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_SECURITY_CONFIG_DEVICEMONITOR_PACKAGE_NAME" "com.samsung.android.sm.devicesecurity.tcm"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_SMARTMANAGER_CONFIG_PACKAGE_NAME" "com.samsung.android.sm_cn"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_COMMON_SUPPORT_NAL_PRELOADAPP_REGULATION" "TRUE"

# =============================================================================
# Floating Features - Camera
# =============================================================================
# Stock S23U does not define CAMERA_CONFIG_AUTOFRAMING; forcing "uhd" crashes smooth_transition on dm3q.
# SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_CONFIG_AUTOFRAMING" "uhd"
# SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_CONFIG_VENDOR_LIB_INFO" "de_flicker.arcsoft.v1,de_flicker_hdr.arcsoft.v1,food.samsung.v1,face_landmark.arcsoft.v2_1,beauty.samsung.v4,facial_restoration.arcsoft.v1,facial_attribute.samsung.v1,human_tracking_hand.arcsoft.v4,fr_tracking.arcsoft.v1,smart_scan.samsung.v2,aimode.samsung.v2,aimfisp.samsung.v1,ai_clear_zoom.arcsoft.v1,macro_raw_sr.arcsoft.v1,super_resolution_raw.arcsoft.v2,aebhdr.arcsoft.v1,hybridhdr.arcsoft.v1,single_bokeh.samsung.v2,super_night.mpi.v2,swuwdc.arcsoft.v1,event_detection.samsung.v2,selfie_correction.samsung.v1,dual_bokeh.samsung.v1_1,image_codec.samsung.v2,pro_single_rgb.mpi.v1,image_enhance.arcsoft.v1,localtm.samsung.v1_1,stereo_photo.samsung.v1,compressed_raw_decoder.samsung.v1"
# Keep S26U autofocus/fusion/SW-binning paths disabled on dm3q; they crash the S23U vendor camera stack.
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_AFSENSITIVITY" "FALSE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_AFSMARTTRACKING" "FALSE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_AFSPEED" "FALSE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_CINEMATIC_PORTRAITVIDEO" "FALSE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_FUSION_HIGH_RESOLUTION" "FALSE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_HIGH_RESOLUTION_SWBINNING" "FALSE"
# These S26U capture pipelines are not exposed by the stock dm3q camera stack.
# Advertising them makes SamsungCamera submit an unsupported night request and
# triggers CHI recovery (Usecase::DumpSystemEvent) in the camera provider.
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_NIGHT_INTEGRATED_PHOTO_MODE" "FALSE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_SUPER_NIGHT_DRAFT_RAW" "FALSE"
# WARNING: Might Cause Crash
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_VDIS_ON_MOTIONPHOTO" "FALSE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_VIDEO_SOFTENING" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_DUAL_PORTRAITVIDEO" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_EDITABLE_PORTRAITVIDEO" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_PORTRAIT_INTELLIGENT_OPTIMIZATION_AI_ISP" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_SEAMLESS_PORTRAITVIDEO" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_AUTO_EXPOSURE_LIMIT" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_MOTIONPHOTO_AUTO_TRIM_MODE" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_MOTIONPHOTO_SOUND_TIMING" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_MOTIONPHOTO_SOUND_TYPE" "TRUE"

# =============================================================================
# Floating Features - Display / Gallery / Media
# =============================================================================
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_LCD_SUPPORT_DOZE_AP_SLEEP" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_MMFW_SUPPORT_CFA_CODEC" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_MMFW_SUPPORT_VIDEO_SEARCH" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_MMFW_SUPPORT_VIDEOSERACH" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_GALLERY_CONFIG_LIVEFOCUS_EFFECT_DUAL_BOKEH" "BLUR,EFFECT,BIGBOKEH,PORTRAIT,RELIGHT,REFOCUS,LIGHT360,GENERIC_REFOCUS,GENERIC_REEDIT"
#SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_GALLERY_CONFIG_AI_EXPANSION" "AI_Timelapse,singletake.hidt.support.on,singletake.capture.support.off,singletake.video_res.config.fhd,singletake.video.previous_record"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_GRAPHICS_SUPPORT_REDUCE_FLASH_LIGHT" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_GRAPHICS_SUPPORT_TOUCH_FAST_RESPONSE" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_LCD_CONFIG_AOD_BRIGHTNESS_ANIMATION" "1"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_LCD_CONFIG_AOD_FULLSCREEN" "1"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_MMFW_SUPPORT_HDR2SDR_MAX_8K" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_MMFW_SUPPORT_HIERARCHICAL_B_ENCODING" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_MMFW_SUPPORT_LONGEXPOSURE_EFFECT_10BIT" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_MMFW_SUPPORT_PHOTOHDR" "TRUE"
# Keep foundational_segmentation disabled on dm3q; the S26U FM engine crashes
# PhotoEditor_AIFull on the S23U SNAP/vendor stack during contour/lasso execution.
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_VIDEO_CONFIG_VIDEO_CLIPPING_MODE" "NPU,unifiedclipper"

# =============================================================================
# Floating Features - Galaxy AI / Framework / Search
# =============================================================================
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_ACCESSIBILITY_SUPPORT_AI_CORE_IMAGE_DESCRIPTION" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_COMMON_CONFIG_WAKEUP_MULTIAGENT_VERSION" "1"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_FRAMEWORK_CONFIG_APPFUNCTION_AGENT_APPLIST" "ai.perplexity.app.android"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_FRAMEWORK_SUPPORT_COMPUTER_CONTROL" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_FRAMEWORK_SUPPORT_PROVIDE_TSP_RAWDATA" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_SAMSUNG_SEARCH_SEMANTIC_SEARCH_VERSION" "510"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_GENAI_CONFIG_FOUNDATION_MODEL" "3B"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_GENAI_SUPPORT_TIME_WEATHER_WALLPAPER" "V7"

# =============================================================================
# Helpers
# =============================================================================
_REZOSS_SET_VENDOR_FLOATING_FEATURE_CONFIG()
{
    local CONFIG="$1"
    local VALUE="$2"
    local FILE="$WORK_DIR/vendor/etc/floating_feature.xml"

    if [ ! -f "$FILE" ]; then
        LOGW "File not found: ${FILE//$WORK_DIR/}"
        return 0
    fi

    if grep -q "$CONFIG" "$FILE"; then
        LOG "- Replacing \"$CONFIG\" config with \"$VALUE\" in /vendor/etc/floating_feature.xml"
        sed -i "$(sed -n "/<${CONFIG}>/=" "$FILE") c\ \ \ \ <${CONFIG}>${VALUE}</${CONFIG}>" "$FILE"
    else
        LOG "- Adding \"$CONFIG\" config with \"$VALUE\" in /vendor/etc/floating_feature.xml"
        sed -i "/<\/SecFloatingFeatureSet>/d" "$FILE"
        if ! grep -q "Added by unica/mods/rezoss" "$FILE"; then
            echo "    <!-- Added by unica/mods/rezoss/customize.sh -->" >> "$FILE"
        fi
        echo "    <${CONFIG}>${VALUE}</${CONFIG}>" >> "$FILE"
        echo "</SecFloatingFeatureSet>" >> "$FILE"
    fi
}

_REZOSS_APPEND_UNIQUE_LINE()
{
    local FILE="$1"
    local LINE="$2"

    if ! grep -q -F "$LINE" "$FILE"; then
        echo "$LINE" >> "$FILE"
    fi
}

_REZOSS_ENSURE_VENDOR_CONFIG_FILE_CONTEXTS()
{
    local FC_FILE="$WORK_DIR/vendor/etc/selinux/vendor_file_contexts"

    if [ ! -f "$FC_FILE" ]; then
        LOGW "File not found: ${FC_FILE//$WORK_DIR/}"
        return 0
    fi

    LOG "- Ensuring S26U vendor config/model file contexts"
    _REZOSS_APPEND_UNIQUE_LINE "$FC_FILE" "/vendor/etc/saiv/image_understanding/db/doc_rectifier(/.*)? u:object_r:vendor_configs_file:s0"
    _REZOSS_APPEND_UNIQUE_LINE "$FC_FILE" "/vendor/etc/saiv/image_understanding/db/fm(/.*)? u:object_r:vendor_configs_file:s0"
    _REZOSS_APPEND_UNIQUE_LINE "$FC_FILE" "/vendor/etc/saiv/image_understanding/db/ss_magnet(/.*)? u:object_r:vendor_configs_file:s0"
    _REZOSS_APPEND_UNIQUE_LINE "$FC_FILE" "/vendor/etc/midas_enhancedocumentscan(/.*)? u:object_r:vendor_configs_file:s0"
}

_REZOSS_SET_VENDOR_CONFIG_DIR_METADATA()
{
    local ENTRY="$1"
    local BASE="$WORK_DIR/vendor/$ENTRY"
    local FILE
    local REL

    if [ ! -d "$BASE" ]; then
        LOGW "Directory not found: ${BASE//$WORK_DIR/}"
        return 0
    fi

    while IFS= read -r FILE; do
        REL="${FILE#$WORK_DIR/vendor/}"
        if [ -d "$FILE" ]; then
            SET_METADATA "vendor" "$REL" 0 2000 755 "u:object_r:vendor_configs_file:s0"
        else
            SET_METADATA "vendor" "$REL" 0 0 644 "u:object_r:vendor_configs_file:s0"
        fi
    done < <(find "$BASE")
}

_REZOSS_SET_SYSTEM_LIB64_METADATA()
{
    local FILE="$1"

    SET_METADATA "system" "system/lib64/$FILE" 0 0 644 "u:object_r:system_lib_file:s0"
}

_REZOSS_GET_SEPOLICY_API_SUFFIX()
{
    local CIL_FILE="$1"

    if grep -q "(type init_34_0)" "$CIL_FILE"; then
        echo "34_0"
    else
        echo "33_0"
    fi
}

_REZOSS_CIL_HAS_SYMBOL()
{
    local SYMBOL="$1"
    shift

    local FILE
    for FILE in "$@"; do
        [ -f "$FILE" ] || continue
        if grep -q " ${SYMBOL}[ )]" "$FILE" || grep -q "(${SYMBOL}[ )]" "$FILE"; then
            return 0
        fi
    done

    return 1
}

_REZOSS_GET_MOSEY_APP_DOMAIN()
{
    local API_SUFFIX="$1"
    local SYSTEM_EXT_SELINUX
    local VERSIONED_DOMAIN="mosey_app_${API_SUFFIX}"
    local VERSIONED_MAPPING="${API_SUFFIX/_/.}.cil"

    if $TARGET_OS_BUILD_SYSTEM_EXT_PARTITION; then
        SYSTEM_EXT_SELINUX="$WORK_DIR/system_ext/etc/selinux"
    else
        SYSTEM_EXT_SELINUX="$WORK_DIR/system/system/system_ext/etc/selinux"
    fi

    if _REZOSS_CIL_HAS_SYMBOL "$VERSIONED_DOMAIN" \
        "$SYSTEM_EXT_SELINUX/mapping/$VERSIONED_MAPPING" \
        "$WORK_DIR/vendor/etc/selinux/plat_pub_versioned.cil"; then
        echo "$VERSIONED_DOMAIN"
        return
    fi

    if _REZOSS_CIL_HAS_SYMBOL "mosey_app" \
        "$SYSTEM_EXT_SELINUX/system_ext_sepolicy.cil" \
        "$WORK_DIR/product/etc/selinux/product_sepolicy.cil"; then
        echo "mosey_app"
        return
    fi

    echo "priv_app_${API_SUFFIX}"
}

_REZOSS_DROP_MOSEY_APP_VENDOR_RULES()
{
    local CIL_FILE="$1"

    sed -i -E \
        -e '/^\(allow (mosey_app(_[0-9]+_[0-9]+)?|priv_app_[0-9]+_[0-9]+) mosey_(server|service) /d' \
        -e '/^\(allow mosey_server (mosey_app(_[0-9]+_[0-9]+)?|priv_app_[0-9]+_[0-9]+) /d' \
        "$CIL_FILE"
}

_REZOSS_ENSURE_MOSEY_VENDOR_SELINUX()
{
    local FC_FILE="$WORK_DIR/vendor/etc/selinux/vendor_file_contexts"
    local SERVICE_FILE="$WORK_DIR/vendor/etc/selinux/vendor_service_contexts"
    local CIL_FILE="$WORK_DIR/vendor/etc/selinux/vendor_sepolicy.cil"
    local API_SUFFIX
    local APP_DOMAIN

    if [ -f "$FC_FILE" ]; then
        LOG "- Ensuring S24U Mosey vendor file context"
        _REZOSS_APPEND_UNIQUE_LINE "$FC_FILE" "/vendor/bin/mosey_server u:object_r:mosey_server_exec:s0"
    else
        LOGW "File not found: ${FC_FILE//$WORK_DIR/}"
    fi

    if [ -f "$SERVICE_FILE" ]; then
        LOG "- Ensuring S24U Mosey service contexts"
        _REZOSS_APPEND_UNIQUE_LINE "$SERVICE_FILE" "com.google.pixel.moseyservice.IMoseyService/default u:object_r:mosey_service:s0"
        _REZOSS_APPEND_UNIQUE_LINE "$SERVICE_FILE" "com.google.android.moseyservice.IMoseyService/default u:object_r:mosey_service:s0"
    else
        LOGW "File not found: ${SERVICE_FILE//$WORK_DIR/}"
    fi

    if [ -f "$CIL_FILE" ]; then
        API_SUFFIX="$(_REZOSS_GET_SEPOLICY_API_SUFFIX "$CIL_FILE")"
        APP_DOMAIN="$(_REZOSS_GET_MOSEY_APP_DOMAIN "$API_SUFFIX")"
        LOG "- Ensuring S24U Mosey vendor SELinux policy (${API_SUFFIX}, ${APP_DOMAIN})"
        _REZOSS_DROP_MOSEY_APP_VENDOR_RULES "$CIL_FILE"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(type mosey_server)"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(roletype object_r mosey_server)"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(type mosey_server_exec)"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(roletype object_r mosey_server_exec)"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(type mosey_service)"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(roletype object_r mosey_service)"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(typeattributeset domain (mosey_server))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(typeattributeset file_type (mosey_server_exec))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(typeattributeset exec_type (mosey_server_exec))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(typeattributeset vendor_file_type (mosey_server_exec))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(typeattributeset service_manager_type (mosey_service))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(typeattributeset vendor_service (mosey_service))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(typeattributeset hal_service_type (mosey_service))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow ${APP_DOMAIN} mosey_server (binder (call transfer)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow mosey_server ${APP_DOMAIN} (binder (transfer)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow ${APP_DOMAIN} mosey_server (fd (use)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow ${APP_DOMAIN} mosey_service (service_manager (find)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow mosey_server servicemanager_${API_SUFFIX} (binder (call transfer)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow servicemanager_${API_SUFFIX} mosey_server (binder (call transfer)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow servicemanager_${API_SUFFIX} mosey_server (dir (search)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow servicemanager_${API_SUFFIX} mosey_server (file (read open)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow servicemanager_${API_SUFFIX} mosey_server (process (getattr)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow init_${API_SUFFIX} mosey_server_exec (file (read getattr map execute open)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow init_${API_SUFFIX} mosey_server (process (transition)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow mosey_server mosey_server_exec (file (read getattr map execute open entrypoint)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(dontaudit init_${API_SUFFIX} mosey_server (process (noatsecure)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow init_${API_SUFFIX} mosey_server (process (siginh rlimitinh)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(typetransition init_${API_SUFFIX} mosey_server_exec process mosey_server)"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow mosey_server mosey_service (service_manager (add find)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow mosey_server self (capability (net_admin net_raw)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow mosey_server sysfs_net_${API_SUFFIX} (dir (ioctl read getattr lock open watch watch_reads search)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow mosey_server sysfs_net_${API_SUFFIX} (file (ioctl read getattr lock map open watch watch_reads)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow mosey_server sysfs_net_${API_SUFFIX} (lnk_file (ioctl read getattr lock map open watch watch_reads)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow mosey_server self (udp_socket (ioctl create)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allowx mosey_server self (ioctl udp_socket ((range 0x8913 0x8914) 0x8916 0x8922 0x8924 0x8936 0x8946 0x8994 0x89f1)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow mosey_server self (unix_dgram_socket (ioctl)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allowx mosey_server self (ioctl unix_dgram_socket ((range 0x8913 0x8914) 0x8946 0x8994)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow mosey_server self (packet_socket (ioctl read write create getattr setattr lock append map bind connect listen accept getopt setopt shutdown)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allowx mosey_server self (ioctl packet_socket (0x8913 0x8927 0x8933)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow mosey_server self (netlink_route_socket (read write create nlmsg_read nlmsg_write nlmsg_readpriv nlmsg_getneigh)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow mosey_server mosey_server (netlink_netfilter_socket (create)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow mosey_server tun_device_${API_SUFFIX} (chr_file (ioctl read write getattr lock append map open watch watch_reads)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allowx mosey_server tun_device_${API_SUFFIX} (ioctl chr_file (0x54ca 0x54d2)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow mosey_server self (tun_socket (create)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow mosey_server self (netlink_generic_socket (ioctl read write create getattr setattr lock append map bind connect getopt setopt shutdown)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allowx mosey_server self (ioctl netlink_generic_socket (0x8946)))"
    else
        LOGW "File not found: ${CIL_FILE//$WORK_DIR/}"
    fi
}

_REZOSS_ENSURE_LOG_VIDEO_FILTER_SELINUX()
{
    local FC_FILE="$WORK_DIR/vendor/etc/selinux/vendor_file_contexts"
    local CIL_FILE="$WORK_DIR/vendor/etc/selinux/vendor_sepolicy.cil"

    if [ -f "$FC_FILE" ]; then
        if ! grep -q -F "/data/vendor/LogVideofilters" "$FC_FILE"; then
            LOG "- Adding LogVideofilters data file context"
            echo "/data/vendor/LogVideofilters(/.*)? u:object_r:LogVideofilters_data_file:s0" >> "$FC_FILE"
        fi
    else
        LOGW "File not found: ${FC_FILE//$WORK_DIR/}"
    fi

    if [ -f "$CIL_FILE" ]; then
        LOG "- Ensuring LogVideofilters SELinux access"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(type LogVideofilters_data_file)"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(roletype object_r LogVideofilters_data_file)"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(typeattributeset file_type (LogVideofilters_data_file))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(typeattributeset data_file_type (LogVideofilters_data_file))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow hal_camera_default LogVideofilters_data_file (dir (ioctl read getattr lock open watch watch_reads search)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow hal_camera_default LogVideofilters_data_file (file (ioctl read getattr lock map open watch watch_reads)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow platform_app_33_0 LogVideofilters_data_file (dir (ioctl read getattr lock open watch watch_reads search)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow platform_app_33_0 LogVideofilters_data_file (file (ioctl read getattr lock map open watch watch_reads)))"
    else
        LOGW "File not found: ${CIL_FILE//$WORK_DIR/}"
    fi
}

_REZOSS_ENSURE_BOOTANIMATION_SELINUX()
{
    local SYSTEM_EXT_SELINUX
    local CIL_FILE

    if $TARGET_OS_BUILD_SYSTEM_EXT_PARTITION; then
        SYSTEM_EXT_SELINUX="$WORK_DIR/system_ext/etc/selinux"
    else
        SYSTEM_EXT_SELINUX="$WORK_DIR/system/system/system_ext/etc/selinux"
    fi

    CIL_FILE="$SYSTEM_EXT_SELINUX/system_ext_sepolicy.cil"

    if [ -f "$CIL_FILE" ]; then
        LOG "- Ensuring optional AOSP bootanimation SELinux access"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "; Added by unica/mods/rezoss/customize.sh for optional AOSP zip boot animation"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow sec_system_init_shell self (capability (dac_override dac_read_search sys_admin)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow sec_system_init_shell bootanim_data_file (dir (ioctl read write create getattr setattr lock rename open watch watch_reads add_name remove_name search)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow sec_system_init_shell bootanim_data_file (file (ioctl read write create getattr setattr lock append map unlink rename open watch watch_reads)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow sec_system_init_shell media_rw_data_file (dir (ioctl read getattr lock open watch watch_reads search)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow sec_system_init_shell media_rw_data_file (file (ioctl read getattr lock map open watch watch_reads)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow sec_system_init_shell bootanim_exec (file (ioctl read getattr lock map open watch watch_reads)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow sec_system_init_shell bootanim_oem_file (file (ioctl read getattr lock map open watch watch_reads mounton)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow sec_system_init_shell bootanim_exec (file (mounton)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow sec_system_init_shell ctl_restart_prop (property_service (set)))"
        _REZOSS_APPEND_UNIQUE_LINE "$CIL_FILE" "(allow sec_system_init_shell ctl_restart_prop (file (read getattr map open)))"
    else
        LOGW "File not found: ${CIL_FILE//$WORK_DIR/}"
    fi
}

_REZOSS_ENSURE_BOOTANIMATION_SELINUX

# =============================================================================
# S24U OneUI 8.5 Quick Share Apple Devices Extension
# =============================================================================
REZOSS_ENABLE_MOSEY_APPLE_SHARING="${REZOSS_ENABLE_MOSEY_APPLE_SHARING:-true}"
if [[ "$REZOSS_ENABLE_MOSEY_APPLE_SHARING" == "true" ]]; then
    LOG "- Adding S24U OneUI 8.5 Mosey Quick Share extension"
    ADD_TO_WORK_DIR "$MODPATH" "vendor" "bin/mosey_server" 0 2000 755 "u:object_r:mosey_server_exec:s0"
    ADD_TO_WORK_DIR "$MODPATH" "vendor" "lib64/libmosey_daemon_ffi.so" 0 0 644 "u:object_r:vendor_file:s0"
    ADD_TO_WORK_DIR "$MODPATH" "vendor" "etc/init/mosey.rc" 0 0 644 "u:object_r:vendor_configs_file:s0"
    ADD_TO_WORK_DIR "$MODPATH" "vendor" "etc/vintf/manifest/manifest_mosey.xml" 0 0 644 "u:object_r:vendor_configs_file:s0"
    _REZOSS_ENSURE_MOSEY_VENDOR_SELINUX
else
    LOG "- Skipping S24U OneUI 8.5 Mosey Quick Share extension; REZOSS_ENABLE_MOSEY_APPLE_SHARING=false"
    DELETE_FROM_WORK_DIR "system" "system/etc/default-permissions/default-permissions-com.google.android.mosey.xml"
    DELETE_FROM_WORK_DIR "system" "system/etc/init/rezoss_mosey_permissions.rc"
    DELETE_FROM_WORK_DIR "system" "system/etc/unica/rezoss_mosey_permissions.sh"
    DELETE_FROM_WORK_DIR "system_ext" "priv-app/MoseyApp"
    DELETE_FROM_WORK_DIR "system_ext" "etc/default-permissions/default-permissions-com.google.android.mosey.xml"
    DELETE_FROM_WORK_DIR "system_ext" "etc/permissions/privapp-permissions-com.google.android.mosey.xml"
fi
unset REZOSS_ENABLE_MOSEY_APPLE_SHARING

# =============================================================================
# S26U Prebuilts - Gallery LOG / Camera LOG-LUT / Privacy Display
# =============================================================================
LOG "- Adding S26U Privacy Display, Gallery LOG, Camera LOG/LUT, and Horizon Lock support files"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/cameradata/logCubefiles" 0 0 755 "u:object_r:system_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libcontextanalyzer_jni.media.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/liblogProcessingEngine.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libmediacontextanalyzer.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libmpp.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libmpp_common.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libmppclient.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libmppcolorgrade.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libmppfilter.so" 0 0 644 "u:object_r:system_lib_file:s0"

# =============================================================================
# S26U Prebuilts - Video Clipping / Preview Dependencies
# =============================================================================
# Required by S26U Camera LOG/LUT preview.
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libppvdis_core.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libppvdis_interface.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libppvdis_wrapper.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libveframework.videoeditor.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libvideo-highlight-arm64-v8a.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/vendor.samsung.hardware.media.mpp-V5-ndk.so" 0 0 644 "u:object_r:system_lib_file:s0"

# =============================================================================
# S26U Prebuilts - Dual Recording Split View
# =============================================================================
# Disabled after S26U vendor nodes crashed on the dm3q/S23U camera provider stack.
# Keep the existing dm3q/S23U SamsungCamera, recording node, sensor bridge,
# dual calibration bins, and QTI/CHI provider stack active.
# LOG "- Adding S26U Dual Recording Split View vendor nodes"
# ADD_TO_WORK_DIR "m3qxxx" "system" "system/priv-app/SamsungCamera/SamsungCamera.apk" 0 0 644 "u:object_r:system_file:s0"
# ADD_TO_WORK_DIR "m3qxxx" "system" "system/priv-app/SamsungCamera/SamsungCamera.apk.prof" 0 0 644 "u:object_r:system_file:s0"
# ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libsecsettingsmanager.so" 0 0 644 "u:object_r:vendor_file:s0"
# ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libsensorndkbridge.so" 0 0 644 "u:object_r:vendor_file:s0"
# ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libunidatamanager.so" 0 0 644 "u:object_r:vendor_file:s0"
# ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/camera/components/com.samsung.node.uniplugin_recording.so" 0 0 644 "u:object_r:vendor_file:s0"
# ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/camera/components/com.samsung.node.uniplugin_meta_surface.so" 0 0 644 "u:object_r:vendor_file:s0"
# ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/camera/components/com.samsung.node.uniplugin_record_portrait_depth_extract.so" 0 0 644 "u:object_r:vendor_file:s0"
# ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/camera/components/com.samsung.node.uniplugin_record_portrait_render.so" 0 0 644 "u:object_r:vendor_file:s0"
# ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/camera/multical_param.bin" 0 0 644 "u:object_r:vendor_file:s0"
# ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/camera/t2_w_dual_calibration.bin" 0 0 644 "u:object_r:vendor_file:s0"
# ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/camera/t2_t1_dual_calibration.bin" 0 0 644 "u:object_r:vendor_file:s0"

# =============================================================================
# S26U Prebuilts - Camera Core / Photo Processing Libraries
# =============================================================================
LOG "- Adding S26U camera node libraries for partial vendor-lib feature set"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libAIDeflicker.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libDeflickerHDR.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libsnapshotdebanding.arcsoft.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libStereoSolution.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libSR_StereoCapture.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
# Disabled after Photo Editor native crash in FoundationalSegmentationImpl.
# ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libfoundational_segmentation.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
# S26U replacements previously carried by the rezoss static lib64 overlay.
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/DualOutFocusViewer_B.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libArtifactDetector_v1.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libC2paDps.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libGenSR_saicc_core.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libSR_DynamicRectifier.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libSR_NearDetector.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libStereoWarp.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libTextEnhancementV2.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libVirtualApertureCapture.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libframebooster.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libphotohdr.so" 0 0 644 "u:object_r:system_lib_file:s0"

# =============================================================================
# S26U Prebuilts - PhotoHDR Simba / HEIF Stack
# =============================================================================
# System-side test stack only. Keep the existing libphotohdr.so and imagecodec
# APEX contents unchanged for this pass.
LOG "- Adding S26U PhotoHDR Simba/HEIF system stack"
for f in \
    "libsimba.media.samsung.so" \
    "libsimba.decoder.media.samsung.so" \
    "libsimba.cfa.media.samsung.so" \
    "libheifcapture.so" \
    "libheifcapture_jni.media.samsung.so" \
    "libheif.so" \
    "libheifcodec_jni.so" \
    "libheifregiondec_jni.so" \
    "libsheif.so" \
    "libsheifdecadapter.so" \
    "libsecultrahdr.so" \
    "libultrahdr.so" \
    "libjpegsq.media.samsung.so"; do
    ADD_TO_WORK_DIR "$MODPATH" "system" "lib64/$f" 0 0 644 "u:object_r:system_lib_file:s0"
    _REZOSS_SET_SYSTEM_LIB64_METADATA "$f"
done

# =============================================================================
# S26U Prebuilts - Enhanced Document Scan
# =============================================================================
# S26U enhanced document-scan native libs.
# WARNING: Might cause crash
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libDocColorEnhance.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libDocColorEnhance_Auto.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libDocMagnetEngine.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
if [ -e "$WORK_DIR/system/system/lib64/libDocObjectRemovalV2.camera.samsung.so" ] || \
        [ -L "$WORK_DIR/system/system/lib64/libDocObjectRemovalV2.camera.samsung.so" ]; then
    DELETE_FROM_WORK_DIR "system" "system/lib64/libDocObjectRemovalV2.camera.samsung.so"
fi
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libDocScannerFilterV2.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libDocShadowRemoval.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libMoireFilterV2.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
for f in \
    "libcontextanalyzer_jni.media.samsung.so" \
    "liblogProcessingEngine.so" \
    "libmediacontextanalyzer.so" \
    "libmpp.so" \
    "libmpp_common.so" \
    "libmppclient.so" \
    "libmppcolorgrade.so" \
    "libmppfilter.so" \
    "libppvdis_core.so" \
    "libppvdis_interface.so" \
    "libppvdis_wrapper.so" \
    "libveframework.videoeditor.samsung.so" \
    "libvideo-highlight-arm64-v8a.so" \
    "vendor.samsung.hardware.media.mpp-V5-ndk.so" \
    "libAIDeflicker.camera.samsung.so" \
    "DualOutFocusViewer_B.so" \
    "libArtifactDetector_v1.camera.samsung.so" \
    "libC2paDps.camera.samsung.so" \
    "libDeflickerHDR.camera.samsung.so" \
    "libDocColorEnhance.camera.samsung.so" \
    "libDocColorEnhance_Auto.camera.samsung.so" \
    "libDocMagnetEngine.camera.samsung.so" \
    "libDocScannerFilterV2.camera.samsung.so" \
    "libDocShadowRemoval.camera.samsung.so" \
    "libGenSR_saicc_core.camera.samsung.so" \
    "libMoireFilterV2.camera.samsung.so" \
    "libSR_DynamicRectifier.camera.samsung.so" \
    "libSR_NearDetector.camera.samsung.so" \
    "libsnapshotdebanding.arcsoft.so" \
    "libStereoSolution.camera.samsung.so" \
    "libSR_StereoCapture.camera.samsung.so" \
    "libStereoWarp.camera.samsung.so" \
    "libTextEnhancementV2.camera.samsung.so" \
    "libVirtualApertureCapture.camera.samsung.so" \
    "libframebooster.so" \
    "libphotohdr.so" \
    "libsamsungSoundbooster_plus_legacy.so" \
    "libsoundboostereq_legacy.so"; do
    _REZOSS_SET_SYSTEM_LIB64_METADATA "$f"
done

# =============================================================================
# S26U Prebuilts - Video Clipping / Document Scan Models
# =============================================================================
LOG "- Adding S26U video clipping and document-scan model files"
_REZOSS_ENSURE_VENDOR_CONFIG_FILE_CONTEXTS
ADD_TO_WORK_DIR "m3qxxx" "vendor" "etc/saiv/image_understanding/db/fm" 0 2000 755 "u:object_r:vendor_configs_file:s0"
# S26U enhanced document-scan configs/models.
# WARNING: Might cause crash.
ADD_TO_WORK_DIR "m3qxxx" "vendor" "etc/saiv/image_understanding/db/doc_rectifier" 0 2000 755 "u:object_r:vendor_configs_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "vendor" "etc/saiv/image_understanding/db/ss_magnet" 0 2000 755 "u:object_r:vendor_configs_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "vendor" "etc/midas_enhancedocumentscan" 0 2000 755 "u:object_r:vendor_configs_file:s0"
for f in \
    "etc/saiv/image_understanding/db/fm"; do
    _REZOSS_SET_VENDOR_CONFIG_DIR_METADATA "$f"
done

# Do not install the S26U compressed-RAW stack on dm3q. These libraries execute
# inside the S23U camera-provider process and can stall its Night capture graph.
# Keep the matching libraries from the untouched target firmware instead.

# =============================================================================
# S26U Prebuilts - Video Editor / LOG Transitive Dependencies
# =============================================================================
# Transitive dependencies of the S26U video editor / LOG correction stack.
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libmultisourceseparator.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libsbs.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libtensorflowlite_gpu_delegate.so" 0 0 644 "u:object_r:system_lib_file:s0"
for f in \
    "libmultisourceseparator.so" \
    "libsbs.so" \
    "libtensorflowlite_gpu_delegate.so"; do
    _REZOSS_SET_SYSTEM_LIB64_METADATA "$f"
done

# =============================================================================
# Disabled Experiments - Camera Vendor Stack
# =============================================================================
# Rezoss S26U Horizon Lock vendor VDIS experiment.
# WARNING: Disabled after confirmed camera crash.
# ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/camera/components/com.samsung.node.uniplugin_vdis.so" 0 0 644 "u:object_r:vendor_file:s0"
# ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libvdis_interface.so" 0 0 644 "u:object_r:vendor_file:s0"
# ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libvdis_core.so" 0 0 644 "u:object_r:vendor_file:s0"
# ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libsecsettingsmanager.so" 0 0 644 "u:object_r:vendor_file:s0"
# ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libIMUSensor.so" 0 0 644 "u:object_r:vendor_file:s0"

# Optional deeper S26U sensor/OIS test.
# WARNING: Disabled after crash.
# ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libsensorndkbridge.so" 0 0 644 "u:object_r:vendor_file:s0"
# ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libprotobuf-cpp-lite-21.12.so" 0 0 644 "u:object_r:vendor_file:s0"
# ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/liboischannel.so" 0 0 644 "u:object_r:vendor_file:s0"
# ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libois_channel_stub.so" 0 0 644 "u:object_r:vendor_file:s0"
# ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libois_channel_factory_test_stub.so" 0 0 644 "u:object_r:vendor_file:s0"

# =============================================================================
# S26U Prebuilts - PhotoHDR Vendor Encoder Plugin
# =============================================================================
# Direct plugin stack only. Vendor Simba/HEIF/libphotohdr remains on the existing
# target stack for this pass.
LOG "- Adding S26U PhotoHDR vendor encoder plugin stack"
for f in \
    "libSecPhotoHdrEncoder.uniplugin@1.0.so" \
    "libUniPluginUtils.so" \
    "libimgproc_sw.unifunc@common.so" \
    "unihal_cutils.so"; do
    ADD_TO_WORK_DIR "$MODPATH" "vendor" "lib64/$f" 0 0 644 "u:object_r:vendor_file:s0"
done

# =============================================================================
# Vendor Floating Feature Mirror / SELinux Fixups
# =============================================================================
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_LCD_CONFIG_PRIVACY_DISPLAY" "1"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_GALLERY_SUPPORT_LOG_CORRECT_COLOR" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_CONFIG_LOG_VIDEO" "V1.0"
_REZOSS_SET_VENDOR_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_LCD_CONFIG_PRIVACY_DISPLAY" "1"
_REZOSS_SET_VENDOR_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_CONFIG_VENDOR_LIB_INFO" "single_bokeh.samsung.v2,smart_scan.samsung.v2,beauty.samsung.v4"
_REZOSS_SET_VENDOR_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_FUSION_HIGH_RESOLUTION" "FALSE"
_REZOSS_SET_VENDOR_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_HIGH_RESOLUTION_SWBINNING" "FALSE"
_REZOSS_ENSURE_LOG_VIDEO_FILTER_SELINUX

unset -f _REZOSS_SET_VENDOR_FLOATING_FEATURE_CONFIG _REZOSS_APPEND_UNIQUE_LINE
unset -f _REZOSS_ENSURE_MOSEY_VENDOR_SELINUX _REZOSS_GET_MOSEY_APP_DOMAIN
unset -f _REZOSS_DROP_MOSEY_APP_VENDOR_RULES _REZOSS_CIL_HAS_SYMBOL _REZOSS_GET_SEPOLICY_API_SUFFIX
unset -f _REZOSS_ENSURE_LOG_VIDEO_FILTER_SELINUX _REZOSS_ENSURE_BOOTANIMATION_SELINUX


# =============================================================================
# Build Display Branding
# =============================================================================
NOW_BUILD="UN1CA-ROM built by Rezoss on $(GET_PROP "system" "ro.build.PDA")"
SET_PROP "system" "ro.build.display.id" "${NOW_BUILD}"
SET_PROP "product" "ro.build.display.id" "${NOW_BUILD}"

# =============================================================================
# Local System App Overlays
# =============================================================================
ADD_TO_WORK_DIR "dm3qxxx" "system" "system/app/SamsungSans/SamsungSans.apk" 0 0 644 "u:object_r:system_file:s0"
ADD_TO_WORK_DIR "dm3qxxx" "system" "system/app/VisionModel-Stub/VisionModel-Stub.apk" 0 0 644 "u:object_r:system_file:s0"

# =============================================================================
# Ambient Weather Wallpaper / VisualCloudCore Patches
# =============================================================================
# Enable built-in spoof to use Ambient Weather Wallpaper
LOG "- Patch DressRoom Weather wallpaper AICore gate"
APPLY_PATCH "system" "system/priv-app/DressRoom/DressRoom.apk" \
    "$MODPATH/dressroom/DressRoom.apk/0001-Bypass-AICore-weather-feature-check.patch"

LOG "- Patch VisualCloudCore Galaxy Store model check"
APPLY_PATCH "system" "system/app/VisualCloudCore/VisualCloudCore.apk" \
    "$MODPATH/visualcloudcore/VisualCloudCore.apk/0001-Use-S25-Ultra-model-for-stub-update-check.patch"

LOG "- Patch product framework overlay doze auto-brightness"
APPLY_PATCH "product" "overlay/framework-res__dm3qxxx__auto_generated_rro_product.apk" \
    "$MODPATH/rro/framework-res__dm3qxxx__auto_generated_rro_product.apk/0001-Add-doze-auto-brightness-arrays.patch"

# =============================================================================
# Notification Highlights / Galaxy AI Stack
# =============================================================================
# S26U Notification highlights requirements: expose the Galaxy AI common-AI gate, keep LLM/offline model metadata enabled for the backend, ensure the offline language model stub is present, remove the extra SecSettings LLM-version UI gate, allow dmxq devices in NmRune, and enable priority/summary defaults.
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_COMMON_CONFIG_AI_VERSION" "20263"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_FRAMEWORK_CONFIG_NOW_NUDGE_VERSION" "1"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_GENAI_CONFIG_LLM_VERSION" "0.70"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_GENAI_SUPPORT_OFFLINE_LANGUAGEMODEL" "TRUE"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/app/SketchBook/SketchBook.apk" 0 0 644 "u:object_r:system_file:s0"
LOG "- Overlay S26U Notification highlights AI APKs"
ADD_TO_WORK_DIR "$MODPATH" "system" "system/priv-app/SamsungIntelliVoiceServices/SamsungIntelliVoiceServices.apk" 0 0 644 "u:object_r:system_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/priv-app/SamsungAiCore/SamsungAiCore.apk" 0 0 644 "u:object_r:system_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/etc/permissions/privapp-permissions-com.samsung.android.aicore.xml" 0 0 644 "u:object_r:system_file:s0"
DELETE_FROM_WORK_DIR "system" "system/app/AIOSKernelService"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/priv-app/AIOSKernelService/AIOSKernelService.apk" 0 0 644 "u:object_r:system_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/etc/permissions/privapp-permissions-com.samsung.android.aioskernelservice.xml" 0 0 644 "u:object_r:system_file:s0"
ADD_TO_WORK_DIR "pa2qxxx" "system" "system/etc/sysconfig/aioskernelservice.xml" 0 0 644 "u:object_r:system_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/etc/permissions/signature-permissions-com.samsung.android.offline.languagemodel.xml" 0 0 644 "u:object_r:system_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/priv-app/OfflineLanguageModel_stub/OfflineLanguageModel_stub.apk" 0 0 644 "u:object_r:system_file:s0"

LOG "- Re-signing OfflineLanguageModel_stub.apk for custom Offline Language Core updates"
OFFLINELM_STUB_APK="$WORK_DIR/system/system/priv-app/OfflineLanguageModel_stub/OfflineLanguageModel_stub.apk"
OFFLINELM_STUB_TMP="$TMP_DIR/offline_language_model_stub"
OFFLINELM_STUB_CERT_PREFIX="aosp"
$ROM_IS_OFFICIAL && OFFLINELM_STUB_CERT_PREFIX="unica"
EVAL "rm -rf \"$OFFLINELM_STUB_TMP\" && mkdir -p \"$OFFLINELM_STUB_TMP\""
EVAL "signapk \"$SRC_DIR/security/${OFFLINELM_STUB_CERT_PREFIX}_platform.x509.pem\" \"$SRC_DIR/security/${OFFLINELM_STUB_CERT_PREFIX}_platform.pk8\" \"$OFFLINELM_STUB_APK\" \"$OFFLINELM_STUB_TMP/OfflineLanguageModel_stub.signed.apk\""
EVAL "zipalign -c -p 4 \"$OFFLINELM_STUB_TMP/OfflineLanguageModel_stub.signed.apk\""
EVAL "mv -f \"$OFFLINELM_STUB_TMP/OfflineLanguageModel_stub.signed.apk\" \"$OFFLINELM_STUB_APK\""
SET_METADATA "system" "system/priv-app/OfflineLanguageModel_stub/OfflineLanguageModel_stub.apk" 0 0 644 "u:object_r:system_file:s0"
EVAL "rm -rf \"$OFFLINELM_STUB_TMP\""
unset OFFLINELM_STUB_APK OFFLINELM_STUB_TMP OFFLINELM_STUB_CERT_PREFIX

LOG "- Adding S26U NMT languagepack preload metadata"
S26U_NMT_TARGET_PRELOAD="$WORK_DIR/system/system/etc/removable_preload.txt"
if [ ! -f "$S26U_NMT_TARGET_PRELOAD" ]; then
    LOGE "File not found: ${S26U_NMT_TARGET_PRELOAD//$SRC_DIR\//}"
    return 1
fi
ADD_S26U_NMT_PRELOAD()
{
    local NMT_LANG="$1"
    local PKG="com.samsung.android.nmt.apps.t2t.languagepack.$NMT_LANG"
    local PRELOAD_BLOCK="$MODPATH/nmt-preload/$NMT_LANG.txt"

    if grep -Fq "name='$PKG'" "$S26U_NMT_TARGET_PRELOAD"; then
        return 0
    fi

    if [ ! -s "$PRELOAD_BLOCK" ]; then
        LOGE "Missing S26U preload metadata for $PKG"
        return 1
    fi

    LOG "- Adding $PKG to removable_preload.txt"
    {
        echo ""
        cat "$PRELOAD_BLOCK"
    } >> "$S26U_NMT_TARGET_PRELOAD"
}
for NMT_LANG in \
    enar \
    enesus \
    enfil \
    enid \
    enko \
    ennl \
    enpt \
    enro \
    enru \
    ensv \
    entr \
    enzh; do
    ADD_S26U_NMT_PRELOAD "$NMT_LANG" || return 1
done
unset -f ADD_S26U_NMT_PRELOAD
unset NMT_LANG S26U_NMT_TARGET_PRELOAD
#ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/vendor.qti.hardware.dsp-V1-ndk.so" 0 0 644 "u:object_r:vendor_file:s0"
# Causing bootloop
# ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/android.hardware.common-V2-ndk.so" 0 0 644 "u:object_r:vendor_file:s0"
# DOWNLOAD_FILE "$(GET_GALAXY_STORE_DOWNLOAD_URL "com.samsung.android.aicore")" \
    # "$WORK_DIR/system/system/priv-app/SamsungAiCore/SamsungAiCore.apk"

REZOSS_SIVS_MODEL_PATCHED_FILE="$APKTOOL_DIR/system/priv-app/SamsungIntelliVoiceServices/SamsungIntelliVoiceServices.apk/smali/com/samsung/android/intellivoiceservice/common/connection/server/LlmClientInterceptor.smali"
REZOSS_SIVS_MODEL_PATCH_MARKER="$APKTOOL_DIR/system/priv-app/SamsungIntelliVoiceServices/SamsungIntelliVoiceServices.apk/.rezoss_s26u_device_model_patch_applied"
if [ -f "$REZOSS_SIVS_MODEL_PATCHED_FILE" ] && grep -qF 'const-string v4, "SM-S948B"' "$REZOSS_SIVS_MODEL_PATCHED_FILE"; then
    touch "$REZOSS_SIVS_MODEL_PATCH_MARKER"
fi
if [ ! -f "$REZOSS_SIVS_MODEL_PATCH_MARKER" ]; then
    LOG "- Patch SamsungIntelliVoiceServices SCS device-model header to SM-S948B"
    APPLY_PATCH "system" "system/priv-app/SamsungIntelliVoiceServices/SamsungIntelliVoiceServices.apk" \
        "$MODPATH/sivs/SamsungIntelliVoiceServices.apk/0001-Use-S26U-device-model-for-SCS-requests.patch"
    touch "$REZOSS_SIVS_MODEL_PATCH_MARKER"
fi
unset REZOSS_SIVS_MODEL_PATCHED_FILE REZOSS_SIVS_MODEL_PATCH_MARKER

LOG "- Patch SamsungAiCore Vision Model device mapping"
APPLY_PATCH "system" "system/priv-app/SamsungAiCore/SamsungAiCore.apk" \
    "$MODPATH/aicore/SamsungAiCore.apk/0001-Map-dm-series-to-m3q-for-vision-model.patch"

LOG "- Patching AIOSKernelService service config for SM8550"
APPLY_PATCH "system" "system/priv-app/AIOSKernelService/AIOSKernelService.apk" \
    "$MODPATH/aioskernel/AIOSKernelService.apk/0001-Allow-SM8550-service-config.patch"
LOG "- Patching AIOSKernelService QNN Skel name for Hexagon V73"
APPLY_PATCH "system" "system/priv-app/AIOSKernelService/AIOSKernelService.apk" \
    "$MODPATH/aioskernel/AIOSKernelService.apk/0002-Use-Hexagon-V73-QNN-skel.patch"
LOG "- Spoofing AIOSKernelService build flavor for SM8550"
APPLY_PATCH "system" "system/priv-app/AIOSKernelService/AIOSKernelService.apk" \
    "$MODPATH/aioskernel/AIOSKernelService.apk/0003-Spoof-SM8550-build-flavor.patch"
# Replace the S26U V81 HTP binaries inside AIOSKernelService.apk with the S23U Hexagon V73 pair.
local AIOS_DECODED_APK="$APKTOOL_DIR/system/priv-app/AIOSKernelService/AIOSKernelService.apk"
local AIOS_DECODED_LIB="$AIOS_DECODED_APK/lib/arm64-v8a"
local AIOS_DECODED_SSGEN_LIB="$AIOS_DECODED_APK/assets/ssgen/libs"
local AIOS_SSN_LIB="$AIOS_DECODED_LIB/libssneural_vndk.so"
local AIOS_SSN_PATCHED_LIB="$TMP_DIR/aios_libssneural_vndk.so"
local AIOS_SNAP_QNN_LIB="$AIOS_DECODED_LIB/libsnap_qnn.so"
local AIOS_SNAP_QNN_PATCHED_LIB="$TMP_DIR/aios_libsnap_qnn.so"
local S23U_FW_DIR="$FW_DIR/SM-S918B_EUX"
local AIOS_QNN_MISSING=0
if [ ! -d "$AIOS_DECODED_LIB" ] || [ ! -d "$AIOS_DECODED_SSGEN_LIB" ]; then
    LOGE "AIOSKernelService.apk decoded QNN directories are missing"
    return 1
fi
if [ ! -f "$AIOS_SSN_LIB" ]; then
    LOGE "AIOSKernelService.apk libssneural_vndk.so is missing"
    return 1
fi
if [ ! -f "$AIOS_SNAP_QNN_LIB" ]; then
    LOGE "AIOSKernelService.apk libsnap_qnn.so is missing"
    return 1
fi
for f in \
    "$S23U_FW_DIR/vendor/lib64/snap/libQnnHtp.so" \
    "$S23U_FW_DIR/vendor/lib64/snap/libQnnSystem.so" \
    "$S23U_FW_DIR/vendor/lib64/snap/libQnnHtpV73Stub.so" \
    "$S23U_FW_DIR/vendor/lib/rfsa/adsp/snap/libQnnHtpV73Skel.so"; do
    if [ ! -f "$f" ]; then
        LOGE "File not found: ${f//$SRC_DIR\//}"
        AIOS_QNN_MISSING=1
    fi
done
if [ "$AIOS_QNN_MISSING" != "0" ]; then
    return 1
fi
LOG "- Replacing AIOSKernelService.apk QNN HTP V81 binaries with S23U Hexagon V73 binaries"
cp -f "$S23U_FW_DIR/vendor/lib64/snap/libQnnHtp.so" "$AIOS_DECODED_LIB/libQnnHtp.so"
cp -f "$S23U_FW_DIR/vendor/lib64/snap/libQnnSystem.so" "$AIOS_DECODED_LIB/libQnnSystem.so"
cp -f "$S23U_FW_DIR/vendor/lib64/snap/libQnnHtpV73Stub.so" "$AIOS_DECODED_LIB/libQnnHtpV73Stub.so"
cp -f "$S23U_FW_DIR/vendor/lib64/snap/libQnnHtpV73Stub.so" "$AIOS_DECODED_LIB/libQnnHtpV81Stub.so"
cp -f "$S23U_FW_DIR/vendor/lib/rfsa/adsp/snap/libQnnHtpV73Skel.so" "$AIOS_DECODED_SSGEN_LIB/libQnnHtpV73Skel.so"
cp -f "$S23U_FW_DIR/vendor/lib/rfsa/adsp/snap/libQnnHtpV73Skel.so" "$AIOS_DECODED_SSGEN_LIB/libQnnHtpV81Skel.so"
if [ -f "$S23U_FW_DIR/vendor/lib64/libqnnengine.so" ]; then
    cp -f "$S23U_FW_DIR/vendor/lib64/libqnnengine.so" "$AIOS_DECODED_LIB/libqnnengine.so"
fi
LOG "- Patching AIOSKernelService native SSNeural SM8550 support gate"
EVAL "python3 \"$MODPATH/aioskernel/patch_sm8550_chipset.py\" \"$AIOS_SSN_LIB\" \"$AIOS_SSN_PATCHED_LIB\""
EVAL "mv -f \"$AIOS_SSN_PATCHED_LIB\" \"$AIOS_SSN_LIB\""
LOG "- Patching AIOSKernelService QNN logging null guard"
EVAL "python3 \"$MODPATH/aioskernel/patch_qnn_logging_nullguard.py\" \"$AIOS_SNAP_QNN_LIB\" \"$AIOS_SNAP_QNN_PATCHED_LIB\""
EVAL "mv -f \"$AIOS_SNAP_QNN_PATCHED_LIB\" \"$AIOS_SNAP_QNN_LIB\""
LOG "- Patching AIOSKernelService QNN backend V73 fallback"
EVAL "python3 \"$MODPATH/aioskernel/patch_qnn_backend_v73_fallback.py\" \"$AIOS_SNAP_QNN_LIB\" \"$AIOS_SNAP_QNN_PATCHED_LIB\""
EVAL "mv -f \"$AIOS_SNAP_QNN_PATCHED_LIB\" \"$AIOS_SNAP_QNN_LIB\""
unset AIOS_DECODED_APK AIOS_DECODED_LIB AIOS_DECODED_SSGEN_LIB AIOS_SSN_LIB AIOS_SSN_PATCHED_LIB AIOS_SNAP_QNN_LIB AIOS_SNAP_QNN_PATCHED_LIB S23U_FW_DIR AIOS_QNN_MISSING
SET_METADATA "system" "system/priv-app/AIOSKernelService" 0 0 755 "u:object_r:system_file:s0"
SET_METADATA "system" "system/priv-app/AIOSKernelService/AIOSKernelService.apk" 0 0 644 "u:object_r:system_file:s0"

LOG "- Re-signing AIOSKernelService.apk for SamsungAiCore signature-permission access"
AIOS_KERNEL_APK="$WORK_DIR/system/system/priv-app/AIOSKernelService/AIOSKernelService.apk"
AIOS_KERNEL_TMP="$TMP_DIR/aios_kernel_service"
AIOS_KERNEL_CERT_PREFIX="aosp"
$ROM_IS_OFFICIAL && AIOS_KERNEL_CERT_PREFIX="unica"
EVAL "rm -rf \"$AIOS_KERNEL_TMP\" && mkdir -p \"$AIOS_KERNEL_TMP\""
EVAL "signapk \"$SRC_DIR/security/${AIOS_KERNEL_CERT_PREFIX}_platform.x509.pem\" \"$SRC_DIR/security/${AIOS_KERNEL_CERT_PREFIX}_platform.pk8\" \"$AIOS_KERNEL_APK\" \"$AIOS_KERNEL_TMP/AIOSKernelService.signed.apk\""
EVAL "zipalign -c -p 4 \"$AIOS_KERNEL_TMP/AIOSKernelService.signed.apk\""
EVAL "mv -f \"$AIOS_KERNEL_TMP/AIOSKernelService.signed.apk\" \"$AIOS_KERNEL_APK\""
SET_METADATA "system" "system/priv-app/AIOSKernelService/AIOSKernelService.apk" 0 0 644 "u:object_r:system_file:s0"
EVAL "rm -rf \"$AIOS_KERNEL_TMP\""
unset AIOS_KERNEL_APK AIOS_KERNEL_TMP AIOS_KERNEL_CERT_PREFIX

for REZOSS_NOTI_AI_REQ in \
    "system/priv-app/SecSettings/SecSettings.apk" \
    "system/priv-app/SecSettingsIntelligence/SecSettingsIntelligence.apk" \
    "system/priv-app/SettingsProvider/SettingsProvider.apk" \
    "system/priv-app/SamsungIntelliVoiceServices/SamsungIntelliVoiceServices.apk" \
    "system/priv-app/SamsungAiCore/SamsungAiCore.apk" \
    "system/priv-app/AIOSKernelService/AIOSKernelService.apk" \
    "system/etc/permissions/privapp-permissions-com.samsung.android.intellivoiceservice.xml" \
    "system/etc/permissions/privapp-permissions-com.samsung.android.aicore.xml" \
    "system/etc/permissions/privapp-permissions-com.samsung.android.aioskernelservice.xml" \
    "system/etc/permissions/signature-permissions-com.samsung.android.offline.languagemodel.xml" \
    "system/etc/permissions/signature-permissions-downloadable.xml" \
    "system/etc/sysconfig/samsungintellivoiceservice.xml" \
    "system/etc/sysconfig/allowed-system-preload-apps.xml"; do
    if [ ! -f "$WORK_DIR/system/$REZOSS_NOTI_AI_REQ" ]; then
        LOGW "Notification highlights requirement missing: /system/$REZOSS_NOTI_AI_REQ"
    fi
done
unset REZOSS_NOTI_AI_REQ

LOG "- Patch SecSettings Notification highlights S26U gate"
APPLY_PATCH "system" "system/priv-app/SecSettings/SecSettings.apk" \
    "$MODPATH/notification-priority/SecSettings.apk/0001-Match-S26U-notification-highlights-gate.patch"

LOG "- Patch services.jar AI notification priority/summary model gate"
APPLY_PATCH "system" "system/framework/services.jar" \
    "$MODPATH/notification-priority/services.jar/0001-Allow-dm1q-dm2q-dm3q-AI-notification-priority.patch"

LOG "- Patch SettingsProvider notification priority/summary defaults"
APPLY_PATCH "system" "system/priv-app/SettingsProvider/SettingsProvider.apk" \
    "$MODPATH/notification-priority/SettingsProvider.apk/0001-Enable-notification-priority-default.patch"

# =============================================================================
# Firewall Region String Cleanup
# =============================================================================
LOG "- Sanitize Firewall province/country strings"
DECODE_APK "system" "system/priv-app/Firewall/Firewall.apk"
python3 "$MODPATH/firewall/patch_region_strings.py" \
    "$APKTOOL_DIR/system/priv-app/Firewall/Firewall.apk" \
    "$MODPATH/firewall/region_names.tsv" \
    || ABORT "Failed to sanitize Firewall province/country strings"

LOG_STEP_OUT

# =============================================================================
# Kernel fallback helpers
# =============================================================================
REZOSS_ARCHIVED_KERNEL_DIR="$SRC_DIR/out/archived/kernel"

_REZOSS_RESTORE_ARCHIVED_KERNEL_IMAGE()
{
  local IMAGE_NAME="$1"
  local ARCHIVED_IMAGE="$REZOSS_ARCHIVED_KERNEL_DIR/$IMAGE_NAME"
  local TARGET_IMAGE="$WORK_DIR/kernel/$IMAGE_NAME"

  if [ ! -f "$ARCHIVED_IMAGE" ]; then
    ABORT "Archived kernel image not found: ${ARCHIVED_IMAGE//$SRC_DIR\//}"
    return 1
  fi

  LOG "- Restoring $IMAGE_NAME from ${ARCHIVED_IMAGE//$SRC_DIR\//}"
  cp -f "$ARCHIVED_IMAGE" "$TARGET_IMAGE" \
    || {
      ABORT "Failed to restore $IMAGE_NAME from archived kernel"
      return 1
    }
}

_REZOSS_RESTORE_ARCHIVED_KERNEL()
{
  local RESTORE_BOOT="$1"
  local RESTORE_INIT_BOOT="$2"

  if [ "$RESTORE_BOOT" = "true" ]; then
    _REZOSS_RESTORE_ARCHIVED_KERNEL_IMAGE "boot.img" || return 1
  fi
  if [ "$RESTORE_INIT_BOOT" = "true" ]; then
    _REZOSS_RESTORE_ARCHIVED_KERNEL_IMAGE "init_boot.img" || return 1
  fi
}

_REZOSS_CHANGE_EDGARS_KERNEL_IMPL()
{
  local TMP_DIR="$MODPATH/tmp"
  local RELEASE_JSON ZIP_URL ZIP_NAME IMAGE_GZ MAGISKBOOT MAGISK_APK_URL

  LOG "- Get latest release kernel"
  rm -rf "$TMP_DIR" || return 1
  mkdir -p "$TMP_DIR" || return 1

  RELEASE_JSON="$(curl -fsSL "https://api.github.com/repos/Rezoss-Reza/s23-ksu-next-susfs/releases/latest")" \
    || return 1
  ZIP_URL="$(echo "$RELEASE_JSON" | jq -r '
    .assets[]
    | select(.name | test("\\.zip$"))
    | .browser_download_url
  ' | head -n1)" || return 1
  if [ ! "$ZIP_URL" ] || [ "$ZIP_URL" = "null" ]; then
    LOGE "Edgars Kernel zip asset not found"
    return 1
  fi

  ZIP_NAME="$(basename "$ZIP_URL")" || return 1
  curl -fL --retry 3 -o "$TMP_DIR/$ZIP_NAME" "$ZIP_URL" || return 1

  LOG "- Extracting Image.gz"
  rm -rf "$TMP_DIR/zip_extract" || return 1
  mkdir -p "$TMP_DIR/zip_extract" || return 1
  unzip -o "$TMP_DIR/$ZIP_NAME" -d "$TMP_DIR/zip_extract" >/dev/null || return 1
  IMAGE_GZ="$(find "$TMP_DIR/zip_extract" -type f -name 'Image.gz' | head -n1)"
  if [ ! -f "$IMAGE_GZ" ]; then
    LOGE "Image.gz not found in Edgars Kernel release zip"
    return 1
  fi

  LOG "- Download magiskboot from magisk github"
  MAGISKBOOT="$TMP_DIR/magiskboot"
  if [[ ! -x "$MAGISKBOOT" ]]; then
    LOG "[*] magiskboot not found, downloading Magisk app to extract it..."

    MAGISK_APK_URL="$(curl -fsSL https://api.github.com/repos/topjohnwu/Magisk/releases/latest \
      | jq -r '.assets[] | select(.name | test("Magisk-v.*\\.apk$")) | .browser_download_url' \
      | head -n1)" || return 1
    if [ ! "$MAGISK_APK_URL" ] || [ "$MAGISK_APK_URL" = "null" ]; then
      LOGE "Magisk APK asset not found"
      return 1
    fi

    curl -fL --retry 3 -o "$TMP_DIR/Magisk.apk" "$MAGISK_APK_URL" || return 1
    unzip -p "$TMP_DIR/Magisk.apk" 'lib/x86_64/libmagiskboot.so' > "$MAGISKBOOT" 2>/dev/null || return 1
    chmod +x "$MAGISKBOOT" || return 1
  fi

  if [ ! -f "$WORK_DIR/kernel/boot.img" ]; then
    LOGE "File not found: ${WORK_DIR//$SRC_DIR\//}/kernel/boot.img"
    return 1
  fi

  # ===== UNPACK BOOT.IMG =====
  LOG "- Copying original boot image..."
  cp -f "$WORK_DIR/kernel/boot.img" "$TMP_DIR/boot.img" || return 1
  (
    LOG "- Unpacking boot.img..."
    cd "$TMP_DIR" || exit 1
    "$MAGISKBOOT" unpack boot.img || exit 1

    # ===== REPLACE KERNEL =====
    LOG "- Replacing kernel with new Image.gz..."
    cp -f "$IMAGE_GZ" "$TMP_DIR/kernel" || exit 1

    # ===== REPACK =====
    LOG "- Repacking boot image..."
    "$MAGISKBOOT" repack boot.img || exit 1

    if [[ ! -f new-boot.img ]]; then
      LOG "Repack failed: new-boot.img not generated."
      exit 1
    fi

    cp -f new-boot.img "$WORK_DIR/kernel/boot.img" || exit 1
  ) || return 1

  cd "$SRC_DIR" || return 1
}

_REZOSS_CHANGE_EDGARS_KERNEL()
{
  local STATUS=0

  LOG_STEP_IN "- Change Kernel with Edgars Kernel"
  _REZOSS_CHANGE_EDGARS_KERNEL_IMPL || STATUS=$?
  LOG_STEP_OUT

  return "$STATUS"
}

_REZOSS_PATCH_KSU_NEXT_INIT_BOOT_IMPL()
{
  local TMP_DIR="$MODPATH/tmp"
  local KSU_KMI="android13-5.15"
  local KSU_INIT_BOOT="$WORK_DIR/kernel/init_boot.img"
  local KSU_RELEASE_JSON KSU_RELEASE_TAG KSU_HOST_ARCH KSU_KSUD_REGEX
  local KSU_KSUD_URL KSU_MODULE_NAME KSU_MODULE_URL KSU_KSUD KSU_MODULE
  local KSU_PATCHED_INIT_BOOT

  if [ ! -f "$KSU_INIT_BOOT" ]; then
    LOGE "File not found: ${KSU_INIT_BOOT//$SRC_DIR\//}"
    return 1
  fi

  LOG "- Get latest KernelSU-Next release"
  KSU_RELEASE_JSON="$(curl -fsSL "https://api.github.com/repos/KernelSU-Next/KernelSU-Next/releases/latest")" \
    || return 1
  KSU_RELEASE_TAG="$(echo "$KSU_RELEASE_JSON" | jq -r '.tag_name // empty')" || return 1
  LOG "- Using KernelSU-Next ${KSU_RELEASE_TAG:-latest}"

  KSU_HOST_ARCH="$(uname -m)" || return 1
  case "$KSU_HOST_ARCH" in
    x86_64|amd64)
      KSU_KSUD_REGEX="^ksud-(x86_64|amd64).*linux"
      ;;
    aarch64|arm64)
      KSU_KSUD_REGEX="^ksud-aarch64.*linux"
      ;;
    *)
      LOGE "Unsupported host architecture for KernelSU-Next ksud: $KSU_HOST_ARCH"
      return 1
      ;;
  esac

  KSU_KSUD_URL="$(echo "$KSU_RELEASE_JSON" | jq -r --arg regex "$KSU_KSUD_REGEX" '
    .assets[]
    | select(.name | test($regex))
    | select(.name | test("android") | not)
    | .browser_download_url
  ' | head -n1)" || return 1
  if [ ! "$KSU_KSUD_URL" ] || [ "$KSU_KSUD_URL" = "null" ]; then
    LOGE "KernelSU-Next ksud asset not found for host architecture: $KSU_HOST_ARCH"
    return 1
  fi

  KSU_MODULE_NAME="${KSU_KMI}_kernelsu.ko"
  KSU_MODULE_URL="$(echo "$KSU_RELEASE_JSON" | jq -r --arg name "$KSU_MODULE_NAME" '
    .assets[]
    | select(.name == $name)
    | .browser_download_url
  ' | head -n1)" || return 1
  if [ ! "$KSU_MODULE_URL" ] || [ "$KSU_MODULE_URL" = "null" ]; then
    LOGE "KernelSU-Next module asset not found: $KSU_MODULE_NAME"
    return 1
  fi

  KSU_KSUD="$TMP_DIR/ksud"
  KSU_MODULE="$TMP_DIR/$KSU_MODULE_NAME"

  LOG "- Download ksud"
  curl -fL --retry 3 -o "$KSU_KSUD" "$KSU_KSUD_URL" || return 1
  chmod +x "$KSU_KSUD" || return 1

  LOG "- Download $KSU_MODULE_NAME"
  curl -fL --retry 3 -o "$KSU_MODULE" "$KSU_MODULE_URL" || return 1

  LOG "- Patching init_boot.img for KMI $KSU_KMI"
  cp -f "$KSU_INIT_BOOT" "$TMP_DIR/init_boot.img" || return 1
  (
    cd "$TMP_DIR" || exit 1
    "$KSU_KSUD" boot-patch -b init_boot.img --module "$KSU_MODULE" --kmi "$KSU_KMI"
  ) || return 1

  KSU_PATCHED_INIT_BOOT="$(find "$TMP_DIR" -maxdepth 1 -type f \( -name "*patched*.img" -o -name "new-boot.img" \) -printf "%T@ %p\n" | sort -nr | head -n1 | cut -d " " -f 2-)"
  if [ ! -f "$KSU_PATCHED_INIT_BOOT" ]; then
    KSU_PATCHED_INIT_BOOT="$(find "$TMP_DIR" -maxdepth 1 -type f -name "*.img" ! -name "init_boot.img" -printf "%T@ %p\n" | sort -nr | head -n1 | cut -d " " -f 2-)"
  fi
  if [ ! -f "$KSU_PATCHED_INIT_BOOT" ]; then
    LOGE "KernelSU-Next patched init_boot image was not generated"
    return 1
  fi

  cp -f "$KSU_PATCHED_INIT_BOOT" "$KSU_INIT_BOOT" || return 1
  rm -rf "$TMP_DIR" || LOGW "Failed to remove temporary kernel directory: ${TMP_DIR//$SRC_DIR\//}"
}

_REZOSS_PATCH_KSU_NEXT_INIT_BOOT()
{
  local STATUS=0

  LOG_STEP_IN "- Patch init_boot.img with KernelSU-Next LKM"
  _REZOSS_PATCH_KSU_NEXT_INIT_BOOT_IMPL || STATUS=$?
  LOG_STEP_OUT

  return "$STATUS"
}

# =============================================================================
# Kernel - Edgar Kernel Replacement and KernelSU-Next LKM
# =============================================================================
if ! _REZOSS_CHANGE_EDGARS_KERNEL; then
  LOGW "Edgars Kernel replacement failed; restoring archived boot.img and init_boot.img"
  _REZOSS_RESTORE_ARCHIVED_KERNEL "true" "true"
elif ! _REZOSS_PATCH_KSU_NEXT_INIT_BOOT; then
  LOGW "KernelSU-Next init_boot patch failed; restoring archived init_boot.img"
  _REZOSS_RESTORE_ARCHIVED_KERNEL "false" "true"
fi
