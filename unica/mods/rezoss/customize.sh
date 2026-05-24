LOG_STEP_IN "- Rezoss experimental mods"
ADD_TO_WORK_DIR "$MODPATH" "system" "." 0 0 755 "u:object_r:system_file:s0"

DELETE_FROM_WORK_DIR "system" "system/priv-app/SmartManager_v5"
DELETE_FROM_WORK_DIR "system" "system/app/SmartManager_v6_DeviceSecurity"
DELETE_FROM_WORK_DIR "system" "system/etc/permissions/privapp-permissions-com.samsung.android.lool.xml"
DELETE_FROM_WORK_DIR "system" "system/etc/permissions/signature-permissions-com.samsung.android.lool.xml"
DELETE_FROM_WORK_DIR "system" "system/etc/permissions/privapp-permissions-com.samsung.android.sm.devicesecurity_v6.xml"

SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_SECURITY_CONFIG_DEVICEMONITOR_PACKAGE_NAME" "com.samsung.android.sm.devicesecurity.tcm"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_SMARTMANAGER_CONFIG_PACKAGE_NAME" "com.samsung.android.sm_cn"

SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_CONFIG_VENDOR_LIB_INFO" "de_flicker.arcsoft.v1,ai_isp.samsung.v1,food.samsung.v1,beauty.samsung.v4,facial_restoration.arcsoft.v1,face_landmark.arcsoft.v2_1,facial_attribute.samsung.v1,human_tracking_hand.arcsoft.v4,fr_tracking.arcsoft.v1,ai_clear_zoom.arcsoft.v2,hybridhdr.arcsoft.v1,aebhdr.arcsoft.v1,super_night.mpi.v2,aimode.samsung.v3,super_resolution_tetra.samsung.v1,image_codec.samsung.v2,single_bokeh.samsung.v2,dual_bokeh.samsung.v2,image_enhance.arcsoft.v1,stereo_photo.samsung.v1,smart_scan.samsung.v2,swuwdc.arcsoft.v1,selfie_correction.samsung.v2,event_detection.samsung.v2,pro_single_rgb.mpi.v1,localtm.samsung.v1_1,mid_highres.samsung.v1" 
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_ACCESSIBILITY_SUPPORT_AI_CORE_IMAGE_DESCRIPTION" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_GENAI_CONFIG_FOUNDATION_MODEL" "3B"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_GENAI_CONFIG_LLM_VERSION" "0.70"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_DOCUMENTSCAN_SOLUTIONS" "AI_DEWARPING,SHADOW_REMOVAL,DEBLUR,OBJECT_REMOVAL,COLOR_ENHANCE,TEXT_REFLECTION_REMOVAL,MOIRE_REMOVAL,DOGEAR_REMOVAL,SCANNER_FILTER,DEFAULT_COLOR_FILTER,MAGNET"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_AUTO_EXPOSURE_LIMIT" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_MOTIONPHOTO_AUTO_TRIM_MODE" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_MOTIONPHOTO_SOUND_TIMING" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_CAMERA_SUPPORT_MOTIONPHOTO_SOUND_TYPE" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_GALLERY_CONFIG_LIVEFOCUS_EFFECT_DUAL_BOKEH" "BLUR,EFFECT,BIGBOKEH,PORTRAIT,RELIGHT,REFOCUS,LIGHT360,GENERIC_REFOCUS,GENERIC_REEDIT"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_GRAPHICS_SUPPORT_REDUCE_FLASH_LIGHT" "TRUE"
SET_FLOATING_FEATURE_CONFIG "SEC_FLOATING_FEATURE_VIDEO_CONFIG_VIDEO_CLIPPING_MODE" "NPU,unifiedclipper"


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

# Enable Notification Priority/Summary for dm-series devices behind Samsung's m-series gate
LOG "- Patch services.jar AI notification priority/summary model gate"
APPLY_PATCH "system" "system/framework/services.jar" \
    "$MODPATH/notification-priority/services.jar/0001-Allow-dm1q-dm2q-dm3q-AI-notification-priority.patch"

LOG "- Patch SettingsProvider notification priority/summary defaults"
APPLY_PATCH "system" "system/priv-app/SettingsProvider/SettingsProvider.apk" \
    "$MODPATH/notification-priority/SettingsProvider.apk/0001-Enable-notification-priority-default.patch"

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

find "$APKTOOL_DIR" -type f -name "*.orig" -delete
