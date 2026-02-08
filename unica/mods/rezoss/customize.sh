ADD_TO_WORK_DIR "$MODPATH" "system" "." 0 0 755 "u:object_r:system_file:s0"

DELETE_FROM_WORK_DIR "system" "system/priv-app/SmartManager_v5"
DELETE_FROM_WORK_DIR "system" "system/app/SmartManager_v6_DeviceSecurity"
DELETE_FROM_WORK_DIR "system" "system/etc/permissions/privapp-permissions-com.samsung.android.lool.xml"
DELETE_FROM_WORK_DIR "system" "system/etc/permissions/signature-permissions-com.samsung.android.lool.xml"
DELETE_FROM_WORK_DIR "system" "system/etc/permissions/privapp-permissions-com.samsung.android.sm.devicesecurity_v6.xml"

SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_SECURITY_CONFIG_DEVICEMONITOR_PACKAGE_NAME" "com.samsung.android.sm.devicesecurity.tcm"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_SMARTMANAGER_CONFIG_PACKAGE_NAME" "com.samsung.android.sm_cn"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_GALLERY_CONFIG_AI_EXPANSION" "AI_Timelapse,singletake.hidt.support.on,singletake.capture.support.off,singletake.video_res.config.fhd,singletake"

# Mod from AstroROM https://github.com/SameerAlSahab
# Scamsung only added this bomb in Galaxy A23

# Add entries in floating feature
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_BATTERY_SUPPORT_BSOH_SETTINGS" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_BATTERY_SUPPORT_SBP_INFO_SETTINGS" "TRUE"

BOMB_MODEL="SM-A236B"
PLANT_MODEL="SM-S918B"


find "$APKTOOL_DIR/system/priv-app/SecSettings/SecSettings.apk/" -type f -name "*.smali" | while read -r smali; do
    if grep -q "$BOMB_MODEL" "$smali"; then
        
        # Replace bomb / plant 
        sed -i "s/$BOMB_MODEL/$PLANT_MODEL/g" "$smali"
    fi
done

#JustForMe
NOW_BUILD="UN1CA 3.0.6 Built by Rezoss on $(GET_PROP "system" "ro.build.PDA")"
SET_PROP "system" "ro.build.display.id" "${NOW_BUILD}"
