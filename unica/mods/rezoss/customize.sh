LOG_STEP_IN "- Rezoss experimental mods"
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
NOW_BUILD="UN1CA-ROM built by Rezoss on $(GET_PROP "system" "ro.build.PDA")"
SET_PROP "system" "ro.build.display.id" "${NOW_BUILD}"

# ADD_TO_WORK_DIR "dm3qxxx" "system" "system/app/SamsungCalendar/SamsungCalendar.apk" 0 0 644 "u:object_r:system_file:s0"
# ADD_TO_WORK_DIR "dm3qxxx" "system" "system/app/SamsungClock/SamsungClock.apk" 0 0 644 "u:object_r:system_file:s0"
ADD_TO_WORK_DIR "dm3qxxx" "system" "system/app/SamsungSans/SamsungSans.apk" 0 0 644 "u:object_r:system_file:s0"
#ADD_TO_WORK_DIR "dm3qxxx" "system" "system/app/SamsungWeather/SamsungWeather.apk" 0 0 644 "u:object_r:system_file:s0"
#ADD_TO_WORK_DIR "dm3qxxx" "system" "system/app/VisionIntelligence3.7/VisionIntelligence3.7.apk" 0 0 644 "u:object_r:system_file:s0"
ADD_TO_WORK_DIR "dm3qxxx" "system" "system/app/VisionModel-Stub/VisionModel-Stub.apk" 0 0 644 "u:object_r:system_file:s0"

# ADD_TO_WORK_DIR "dm3qxxx" "system" "system/priv-app/BudsUniteManager/BudsUniteManager.apk" 0 0 644 "u:object_r:system_file:s0"
# ADD_TO_WORK_DIR "dm3qxxx" "system" "system/priv-app/CallAssistant/CallAssistant.apk" 0 0 644 "u:object_r:system_file:s0"
# ADD_TO_WORK_DIR "dm3qxxx" "system" "system/priv-app/CallBGProvider/CallBGProvider.apk" 0 0 644 "u:object_r:system_file:s0"
# ADD_TO_WORK_DIR "dm3qxxx" "system" "system/priv-app/CallLogBackup/CallLogBackup.apk" 0 0 644 "u:object_r:system_file:s0"
ADD_TO_WORK_DIR "dm3qxxx" "system" "system/priv-app/OfflineLanguageModel_stub/OfflineLanguageModel_stub.apk" 0 0 644 "u:object_r:system_file:s0"
# ADD_TO_WORK_DIR "dm3qxxx" "system" "system/priv-app/Routines/Routines.apk" 0 0 644 "u:object_r:system_file:s0"
# ADD_TO_WORK_DIR "dm3qxxx" "system" "system/priv-app/SamsungContacts/SamsungContacts.apk" 0 0 644 "u:object_r:system_file:s0"
# ADD_TO_WORK_DIR "dm3qxxx" "system" "system/priv-app/SamsungDialer/SamsungDialer.apk" 0 0 644 "u:object_r:system_file:s0"
# ADD_TO_WORK_DIR "dm3qxxx" "system" "system/priv-app/SamsungGallery2018/SamsungGallery2018.apk" 0 0 644 "u:object_r:system_file:s0"
# ADD_TO_WORK_DIR "dm3qxxx" "system" "system/priv-app/SamsungInCallUI/SamsungInCallUI.apk" 0 0 644 "u:object_r:system_file:s0"
# ADD_TO_WORK_DIR "dm3qxxx" "system" "system/priv-app/SamsungMessages/SamsungMessages.apk" 0 0 644 "u:object_r:system_file:s0"
# ADD_TO_WORK_DIR "dm3qxxx" "system" "system/priv-app/SecMyFiles2020/SecMyFiles2020.apk" 0 0 644 "u:object_r:system_file:s0"
# ADD_TO_WORK_DIR "dm3qxxx" "system" "system/priv-app/SmartCallProvider/SmartCallProvider.apk" 0 0 644 "u:object_r:system_file:s0"
# ADD_TO_WORK_DIR "dm3qxxx" "system" "system/priv-app/TelephonyUI/TelephonyUI.apk" 0 0 644 "u:object_r:system_file:s0"
# ADD_TO_WORK_DIR "dm3qxxx" "system" "system/priv-app/wallpaper-res/wallpaper-res.apk" 0 0 644 "u:object_r:system_file:s0"
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
rm -rf "$TMP_DIR"
cd "$SRC_DIR"
LOG_STEP_OUT