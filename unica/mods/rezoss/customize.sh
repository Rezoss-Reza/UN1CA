LOG_STEP_IN "- Rezoss experimental mods"
ADD_TO_WORK_DIR "$MODPATH" "system" "." 0 0 755 "u:object_r:system_file:s0"

DELETE_FROM_WORK_DIR "system" "system/priv-app/SmartManager_v5"
DELETE_FROM_WORK_DIR "system" "system/app/SmartManager_v6_DeviceSecurity"
DELETE_FROM_WORK_DIR "system" "system/etc/permissions/privapp-permissions-com.samsung.android.lool.xml"
DELETE_FROM_WORK_DIR "system" "system/etc/permissions/signature-permissions-com.samsung.android.lool.xml"
DELETE_FROM_WORK_DIR "system" "system/etc/permissions/privapp-permissions-com.samsung.android.sm.devicesecurity_v6.xml"

SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_SECURITY_CONFIG_DEVICEMONITOR_PACKAGE_NAME" "com.samsung.android.sm.devicesecurity.tcm"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_SMARTMANAGER_CONFIG_PACKAGE_NAME" "com.samsung.android.sm_cn"

SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_CONFIG_VENDOR_LIB_INFO" "de_flicker.arcsoft.v1,de_flicker_hdr.arcsoft.v1,ai_isp.samsung.v1,food.samsung.v1,face_landmark.arcsoft.v2_1,beauty.samsung.v4,facial_restoration.arcsoft.v1,facial_attribute.samsung.v1,human_tracking_hand.arcsoft.v4,fr_tracking.arcsoft.v1,smart_scan.samsung.v2,aimode.samsung.v2,aimfisp.samsung.v1,ai_clear_zoom.arcsoft.v1,macro_raw_sr.arcsoft.v1,super_resolution_raw.arcsoft.v2,super_resolution_tetra.samsung.v1,aebhdr.arcsoft.v1,hybridhdr.arcsoft.v1,single_bokeh.samsung.v2,super_night.mpi.v2,swuwdc.arcsoft.v1,event_detection.samsung.v2,selfie_correction.samsung.v1,dual_bokeh.samsung.v1_1,image_codec.samsung.v2,pro_single_rgb.mpi.v1,image_enhance.arcsoft.v1,localtm.samsung.v1_1,stereo_photo.samsung.v1,compressed_raw_decoder.samsung.v1"
# From S26U
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_CONFIG_AI_HIGH_RESOLUTION_MAX_CAPTURE" "-1"
# WARNING: Might cause crash
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_AI_HIGH_RESOLUTION" "FALSE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_AI_HIGH_RESOLUTION_DRAFT_DOWNSCALE" "FALSE"
# WARNING: Might Cause Crash
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_AFSENSITIVITY" "FALSE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_AFSMARTTRACKING" "FALSE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_AFSPEED" "FALSE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_CINEMATIC_PORTRAITVIDEO" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_FUSION_HIGH_RESOLUTION" "FALSE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_HIGH_RESOLUTION_SWBINNING" "FALSE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_ACCESSIBILITY_SUPPORT_AI_CORE_IMAGE_DESCRIPTION" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_NIGHT_INTEGRATED_PHOTO_MODE" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_SUPER_NIGHT_DRAFT_RAW" "TRUE"
# WARNING: Might Cause Crash
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_VDIS_ON_MOTIONPHOTO" "FALSE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_VIDEO_SOFTENING" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_COMMON_CONFIG_WAKEUP_MULTIAGENT_VERSION" "1"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_FRAMEWORK_CONFIG_APPFUNCTION_AGENT_APPLIST" "ai.perplexity.app.android"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_FRAMEWORK_SUPPORT_COMPUTER_CONTROL" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_PREWARMING_LEVEL" "1"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_LCD_SUPPORT_DOZE_AP_SLEEP" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_MMFW_SUPPORT_CFA_CODEC" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_MMFW_SUPPORT_VIDEO_SEARCH" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_MMFW_SUPPORT_VIDEOSERACH" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_SAMSUNG_SEARCH_SEMANTIC_SEARCH_VERSION" "510"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_GENAI_CONFIG_FOUNDATION_MODEL" "3B"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_GENAI_CONFIG_LLM_VERSION" "0.70"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_DOCUMENTSCAN_SOLUTIONS" "AI_DEWARPING,SHADOW_REMOVAL,DEBLUR,OBJECT_REMOVAL"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_AUTO_EXPOSURE_LIMIT" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_MOTIONPHOTO_AUTO_TRIM_MODE" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_MOTIONPHOTO_SOUND_TIMING" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_MOTIONPHOTO_SOUND_TYPE" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_GALLERY_CONFIG_LIVEFOCUS_EFFECT_DUAL_BOKEH" "BLUR,EFFECT,BIGBOKEH,PORTRAIT,RELIGHT,REFOCUS,LIGHT360,GENERIC_REFOCUS,GENERIC_REEDIT"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_GRAPHICS_SUPPORT_REDUCE_FLASH_LIGHT" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_VIDEO_CONFIG_VIDEO_CLIPPING_MODE" "NPU,unifiedclipper,foundational_segmentation"

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
    _REZOSS_APPEND_UNIQUE_LINE "$FC_FILE" "/vendor/etc/aip(/.*)? u:object_r:vendor_configs_file:s0"
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
# Required by S26U Camera LOG/LUT preview.
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libppvdis_core.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libppvdis_interface.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libppvdis_wrapper.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libveframework.videoeditor.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libvideo-highlight-arm64-v8a.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/vendor.samsung.hardware.media.mpp-V5-ndk.so" 0 0 644 "u:object_r:system_lib_file:s0"

LOG "- Adding S26U camera node libraries for partial vendor-lib feature set"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libAIDeflicker.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libDeflickerHDR.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libsnapshotdebanding.arcsoft.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libStereoSolution.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libSR_StereoCapture.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libfoundational_segmentation.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
# S26U enhanced document-scan native libs.
# WARNING: Might cause crash
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libDocColorEnhance.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libDocColorEnhance_Auto.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libDocMagnetEngine.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "system" "system/lib64/libDocObjectRemovalV2.camera.samsung.so" 0 0 644 "u:object_r:system_lib_file:s0"
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
    "libDeflickerHDR.camera.samsung.so" \
    "libsnapshotdebanding.arcsoft.so" \
    "libStereoSolution.camera.samsung.so" \
    "libSR_StereoCapture.camera.samsung.so" \
    "libfoundational_segmentation.camera.samsung.so"; do
    _REZOSS_SET_SYSTEM_LIB64_METADATA "$f"
done
ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libAIISP10.camera.samsung.so" 0 0 644 "u:object_r:vendor_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libAIISP10Tuning.camera.samsung.so" 0 0 644 "u:object_r:vendor_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libbayeraiphoto_wrapper_v1.camera.samsung.so" 0 0 644 "u:object_r:vendor_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libBayerAIPhoto.camera.samsung.so" 0 0 644 "u:object_r:vendor_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libBayerAIPhotoTuning.camera.samsung.so" 0 0 644 "u:object_r:vendor_file:s0"
# S26U high-res/fusion native stack.
# WARNING: Disabled after repeated dm3q smooth_transition/SAT provider crashes.
ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libAIHDR_core.camera.samsung.so" 0 0 644 "u:object_r:vendor_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libAIHRWrapper.camera.samsung.so" 0 0 644 "u:object_r:vendor_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libAIHR_ERAW_Wrapper.camera.samsung.so" 0 0 644 "u:object_r:vendor_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libAIHighRes_interface.so" 0 0 644 "u:object_r:vendor_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libBayerSR10.camera.samsung.so" 0 0 644 "u:object_r:vendor_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libBayerSR10Tuning.camera.samsung.so" 0 0 644 "u:object_r:vendor_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libsecsuperresolution_wrapper_v1.camera.samsung.so" 0 0 644 "u:object_r:vendor_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libTetraSR10.camera.samsung.so" 0 0 644 "u:object_r:vendor_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libTetraSR10Tuning.camera.samsung.so" 0 0 644 "u:object_r:vendor_file:s0"
# S26U AI autofocus experiment.
# WARNING: Might cause crash.
ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libaiAutoFocus.so" 0 0 644 "u:object_r:vendor_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/aiAutoFocus_v1.0.24U.dlc" 0 0 644 "u:object_r:vendor_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/aiAutoFocus_v1.1.24U_TELE.dlc" 0 0 644 "u:object_r:vendor_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/aiAutoFocus_v1.1.24U_UW.dlc" 0 0 644 "u:object_r:vendor_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libDepthPortraitVideo_interface.so" 0 0 644 "u:object_r:vendor_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libRenderPortraitVideo_interface.so" 0 0 644 "u:object_r:vendor_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "vendor" "lib64/libSRCinematicVideo.so" 0 0 644 "u:object_r:vendor_file:s0"

LOG "- Adding S26U AI high-res and video clipping model files"
_REZOSS_ENSURE_VENDOR_CONFIG_FILE_CONTEXTS
ADD_TO_WORK_DIR "m3qxxx" "vendor" "etc/aip/config" 0 2000 755 "u:object_r:vendor_configs_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "vendor" "etc/aip/model" 0 2000 755 "u:object_r:vendor_configs_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "vendor" "etc/saiv/image_understanding/db/fm" 0 2000 755 "u:object_r:vendor_configs_file:s0"
# S26U enhanced document-scan configs/models.
# WARNING: Might cause crash.
ADD_TO_WORK_DIR "m3qxxx" "vendor" "etc/saiv/image_understanding/db/doc_rectifier" 0 2000 755 "u:object_r:vendor_configs_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "vendor" "etc/saiv/image_understanding/db/ss_magnet" 0 2000 755 "u:object_r:vendor_configs_file:s0"
ADD_TO_WORK_DIR "m3qxxx" "vendor" "etc/midas_enhancedocumentscan" 0 2000 755 "u:object_r:vendor_configs_file:s0"
for f in \
    "etc/aip/config" \
    "etc/aip/model" \
    "etc/saiv/image_understanding/db/fm"; do
    _REZOSS_SET_VENDOR_CONFIG_DIR_METADATA "$f"
done

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
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_LCD_CONFIG_PRIVACY_DISPLAY" "1"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_GALLERY_SUPPORT_LOG_CORRECT_COLOR" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_CONFIG_LOG_VIDEO" "V1.0"
_REZOSS_SET_VENDOR_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_LCD_CONFIG_PRIVACY_DISPLAY" "1"
_REZOSS_SET_VENDOR_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_CONFIG_AI_HIGH_RESOLUTION_MAX_CAPTURE" "0"
_REZOSS_SET_VENDOR_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_CONFIG_MOTIONPHOTO_CAPTURE_MODE" "[[3,1]]"
_REZOSS_SET_VENDOR_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_CONFIG_MOTIONPHOTO_DEFAULT_SOUND_TIMING" "recording_finishes"
_REZOSS_SET_VENDOR_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_CONFIG_MOTIONPHOTO_DEFAULT_SOUND_TYPE" "motion_photo"
_REZOSS_SET_VENDOR_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_DOCUMENTSCAN_SOLUTIONS" "AI_DEWARPING,SHADOW_REMOVAL,DEBLUR,OBJECT_REMOVAL"
_REZOSS_SET_VENDOR_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_AI_HIGH_RESOLUTION_DRAFT_DOWNSCALE" "FALSE"
_REZOSS_SET_VENDOR_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_PREWARMING_LEVEL" "0"
_REZOSS_ENSURE_LOG_VIDEO_FILTER_SELINUX

unset -f _REZOSS_SET_VENDOR_FLOATING_FEATURE_CONFIG _REZOSS_APPEND_UNIQUE_LINE
unset -f _REZOSS_ENSURE_LOG_VIDEO_FILTER_SELINUX


#JustForMe
NOW_BUILD="UN1CA-ROM built by Rezoss on $(GET_PROP "system" "ro.build.PDA")"
SET_PROP "system" "ro.build.display.id" "${NOW_BUILD}"
SET_PROP "product" "ro.build.display.id" "${NOW_BUILD}"

ADD_TO_WORK_DIR "pa2qxxx" "system" "system/lib64/libmediasndk.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "pa2qxxx" "system" "system/lib64/libvoice_booster.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "pa2qxxx" "system" "system/lib64/lib_sag_ai_sound_sep_v1.00.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "pa2qxxx" "system" "system/lib64/lib_sag_ai_sound_sep_v2.00.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "pa2qxxx" "system" "system/lib64/lib_SAG_EQ_ver2060.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "pa2qxxx" "system" "system/lib64/libSAG_VM_Energy_v300.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "pa2qxxx" "system" "system/lib64/libSAG_VM_Score_V300.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "pa2qxxx" "system" "system/lib64/lib_SoundAlive_play_plus_ver900.so" 0 0 644 "u:object_r:system_lib_file:s0"
ADD_TO_WORK_DIR "pa2qxxx" "system" "system/etc/audio_effects_common.conf" 0 0 644 "u:object_r:system_lib_file:s0"
echo "/system/lib64/lib_sag_ai_sound_sep_v1.00.so" >> "$WORK_DIR/system/system/etc/irremovable_list.txt" 0 0 644 "u:object_r:system_lib_file:s0"

ADD_TO_WORK_DIR "dm3qxxx" "system" "system/app/SamsungSans/SamsungSans.apk" 0 0 644 "u:object_r:system_file:s0"
ADD_TO_WORK_DIR "dm3qxxx" "system" "system/app/VisionModel-Stub/VisionModel-Stub.apk" 0 0 644 "u:object_r:system_file:s0"
ADD_TO_WORK_DIR "dm3qxxx" "system" "system/priv-app/OfflineLanguageModel_stub/OfflineLanguageModel_stub.apk" 0 0 644 "u:object_r:system_file:s0"

# Enable built-in spoof to use Ambient Weather Wallpaper
LOG "- Patch DressRoom Weather wallpaper AICore gate"
APPLY_PATCH "system" "system/priv-app/DressRoom/DressRoom.apk" \
    "$MODPATH/dressroom/DressRoom.apk/0001-Bypass-AICore-weather-feature-check.patch"

LOG "- Patch SamsungAiCore Vision Model device mapping"
APPLY_PATCH "system" "system/priv-app/SamsungAiCore/SamsungAiCore.apk" \
    "$MODPATH/aicore/SamsungAiCore.apk/0001-Map-dm-series-to-pa3q-for-vision-model.patch"

LOG "- Patch VisualCloudCore Galaxy Store model check"
APPLY_PATCH "system" "system/app/VisualCloudCore/VisualCloudCore.apk" \
    "$MODPATH/visualcloudcore/VisualCloudCore.apk/0001-Use-S25-Ultra-model-for-stub-update-check.patch"

LOG "- Patch product framework overlay doze auto-brightness"
APPLY_PATCH "product" "overlay/framework-res__dm3qxxx__auto_generated_rro_product.apk" \
    "$MODPATH/rro/framework-res__dm3qxxx__auto_generated_rro_product.apk/0001-Add-doze-auto-brightness-arrays.patch"

# Enable Notification Priority/Summary for dmxq-series devices behind Samsung's m-series gate
LOG "- Patch services.jar AI notification priority/summary model gate"
APPLY_PATCH "system" "system/framework/services.jar" \
    "$MODPATH/notification-priority/services.jar/0001-Allow-dm1q-dm2q-dm3q-AI-notification-priority.patch"

LOG "- Patch SettingsProvider notification priority/summary defaults"
APPLY_PATCH "system" "system/priv-app/SettingsProvider/SettingsProvider.apk" \
    "$MODPATH/notification-priority/SettingsProvider.apk/0001-Enable-notification-priority-default.patch"

LOG "- Sanitize Firewall province/country strings"
DECODE_APK "system" "system/priv-app/Firewall/Firewall.apk"
python3 "$MODPATH/firewall/patch_region_strings.py" \
    "$APKTOOL_DIR/system/priv-app/Firewall/Firewall.apk" \
    "$MODPATH/firewall/region_names.tsv" \
    || ABORT "Failed to sanitize Firewall province/country strings"

LOG_STEP_OUT

#Lets add Edgars plain kernel
LOG_STEP_IN "- Change Kernel with Edgars Kernel"
LOG "- Get latest release kernel"

rm -rf "$MODPATH/tmp"
mkdir -p "$MODPATH/tmp"
TMP_DIR="$MODPATH/tmp"
RELEASE_JSON="$(curl -fsSL "https://api.github.com/repos/Rezoss-Reza/s23-ksu-next-susfs/releases/latest")"
ZIP_URL="$(echo "$RELEASE_JSON" | jq -r '
  .assets[]
  | select(.name | test("\\.zip$"))
  | .browser_download_url
' | head -n1)"
ZIP_NAME="$(basename "$ZIP_URL")"
curl -fL --retry 3 -o "$TMP_DIR/$ZIP_NAME" "$ZIP_URL"

LOG "- Extracting Image.gz"
rm -rf "$TMP_DIR/zip_extract"
mkdir -p "$TMP_DIR/zip_extract"
unzip -o "$TMP_DIR/$ZIP_NAME" -d "$TMP_DIR/zip_extract" >/dev/null
IMAGE_GZ="$(find "$TMP_DIR/zip_extract" -type f -name 'Image.gz' | head -n1)"

LOG "- Download magiskboot from magisk github"
MAGISKBOOT="$TMP_DIR/magiskboot"
if [[ ! -x "$MAGISKBOOT" ]]; then
  LOG "[*] magiskboot not found, downloading Magisk app to extract it..."

  MAGISK_APK_URL="$(curl -fsSL https://api.github.com/repos/topjohnwu/Magisk/releases/latest \
    | jq -r '.assets[] | select(.name | test("Magisk-v.*\\.apk$")) | .browser_download_url' \
    | head -n1)"

  curl -fL --retry 3 -o "$TMP_DIR/Magisk.apk" "$MAGISK_APK_URL"

  unzip -p "$TMP_DIR/Magisk.apk" 'lib/x86_64/libmagiskboot.so' > "$MAGISKBOOT" 2>/dev/null || true

  chmod +x "$MAGISKBOOT"
fi

# ===== UNPACK BOOT.IMG =====
LOG "- Copying original boot image..."
cp -f "$WORK_DIR/kernel/boot.img" "$TMP_DIR/boot.img"
(
LOG "- Unpacking boot.img..."
cd "$TMP_DIR" && "$MAGISKBOOT" unpack boot.img

# ===== REPLACE KERNEL =====
LOG "- Replacing kernel with new Image.gz..."
cp -f "$IMAGE_GZ" "$TMP_DIR/kernel"

# ===== REPACK =====
LOG "- Repacking boot image..."
"$MAGISKBOOT" repack boot.img

if [[ ! -f new-boot.img ]]; then
  LOG "Repack failed: new-boot.img not generated."
  exit 1
fi

cp -f new-boot.img "$WORK_DIR/kernel/boot.img"
)

cd "$SRC_DIR"
LOG_STEP_OUT

#Lets patch init_boot with KernelSU-Next LKM
LOG_STEP_IN "- Patch init_boot.img with KernelSU-Next LKM"
KSU_KMI="android13-5.15"
KSU_INIT_BOOT="$WORK_DIR/kernel/init_boot.img"
#KSU_TMP_DIR="$MODPATH/tmp-ksu-next"

if [ ! -f "$KSU_INIT_BOOT" ]; then
  ABORT "File not found: ${KSU_INIT_BOOT//$SRC_DIR\//}"
fi

# rm -rf "$KSU_TMP_DIR"
# mkdir -p "$KSU_TMP_DIR"

LOG "- Get latest KernelSU-Next release"
KSU_RELEASE_JSON="$(curl -fsSL "https://api.github.com/repos/KernelSU-Next/KernelSU-Next/releases/latest")" \
  || ABORT "Failed to resolve KernelSU-Next latest release"
KSU_RELEASE_TAG="$(echo "$KSU_RELEASE_JSON" | jq -r '.tag_name // empty')"
LOG "- Using KernelSU-Next ${KSU_RELEASE_TAG:-latest}"

KSU_HOST_ARCH="$(uname -m)"
case "$KSU_HOST_ARCH" in
  x86_64|amd64)
    KSU_KSUD_REGEX="^ksud-(x86_64|amd64).*linux"
    ;;
  aarch64|arm64)
    KSU_KSUD_REGEX="^ksud-aarch64.*linux"
    ;;
  *)
    ABORT "Unsupported host architecture for KernelSU-Next ksud: $KSU_HOST_ARCH"
    ;;
esac

KSU_KSUD_URL="$(echo "$KSU_RELEASE_JSON" | jq -r --arg regex "$KSU_KSUD_REGEX" '
  .assets[]
  | select(.name | test($regex))
  | select(.name | test("android") | not)
  | .browser_download_url
' | head -n1)"
if [ ! "$KSU_KSUD_URL" ]; then
  ABORT "KernelSU-Next ksud asset not found for host architecture: $KSU_HOST_ARCH"
fi

KSU_MODULE_NAME="${KSU_KMI}_kernelsu.ko"
KSU_MODULE_URL="$(echo "$KSU_RELEASE_JSON" | jq -r --arg name "$KSU_MODULE_NAME" '
  .assets[]
  | select(.name == $name)
  | .browser_download_url
' | head -n1)"
if [ ! "$KSU_MODULE_URL" ]; then
  ABORT "KernelSU-Next module asset not found: $KSU_MODULE_NAME"
fi

KSU_KSUD="$TMP_DIR/ksud"
KSU_MODULE="$TMP_DIR/$KSU_MODULE_NAME"

LOG "- Download ksud"
curl -fL --retry 3 -o "$KSU_KSUD" "$KSU_KSUD_URL" \
  || ABORT "Failed to download KernelSU-Next ksud"
chmod +x "$KSU_KSUD"

LOG "- Download $KSU_MODULE_NAME"
curl -fL --retry 3 -o "$KSU_MODULE" "$KSU_MODULE_URL" \
  || ABORT "Failed to download KernelSU-Next module: $KSU_MODULE_NAME"

LOG "- Patching init_boot.img for KMI $KSU_KMI"
cp -f "$KSU_INIT_BOOT" "$TMP_DIR/init_boot.img" \
  || ABORT "Failed to copy init_boot.img"
(
  cd "$TMP_DIR" || exit 1
  "$KSU_KSUD" boot-patch -b init_boot.img --magiskboot "$MAGISKBOOT" --module "$KSU_MODULE" --kmi "$KSU_KMI"
) || ABORT "Failed to patch init_boot.img with KernelSU-Next"

KSU_PATCHED_INIT_BOOT="$(find "$TMP_DIR" -maxdepth 1 -type f \( -name "*patched*.img" -o -name "new-boot.img" \) -printf "%T@ %p\n" | sort -nr | head -n1 | cut -d " " -f 2-)"
if [ ! -f "$KSU_PATCHED_INIT_BOOT" ]; then
  KSU_PATCHED_INIT_BOOT="$(find "$TMP_DIR" -maxdepth 1 -type f -name "*.img" ! -name "init_boot.img" -printf "%T@ %p\n" | sort -nr | head -n1 | cut -d " " -f 2-)"
fi
if [ ! -f "$KSU_PATCHED_INIT_BOOT" ]; then
  ABORT "KernelSU-Next patched init_boot image was not generated"
fi

cp -f "$KSU_PATCHED_INIT_BOOT" "$KSU_INIT_BOOT" \
  || ABORT "Failed to replace init_boot.img with KernelSU-Next patched image"
rm -rf "$TMP_DIR"
LOG_STEP_OUT
