#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
[ "$BOOTMODE" = true ] || abort "Install from your manager in booted Android."
[ "$ARCH" = arm64 ] || abort "Requires arm64."
[ "${API:-0}" -ge 35 ] || abort "Requires Android API 35 or newer."
[ "$(getprop ro.soc.model)" = SM8550 ] || abort "This test is pinned to SM8550."
hash_of() { sha256sum "$1" 2>/dev/null | cut -d ' ' -f 1; }
case "$(hash_of /system/lib64/libmediacontextanalyzer.so)" in
    bedfc253a4c577d6917f2a79412c1c98d7c43cf5b2101406de9e970e526fabd7|3bb6c3e63d2108e86f6c213c77280577e1040ec99f47d48f18d7da5d4ddcce07) ;;
    *) abort "Unsupported MCA library. Private ABI bridge installation stopped." ;;
esac
if [ -e /system/lib64/libauto-reframing-arm64-v8a.so ]; then
    [ "$(hash_of /system/lib64/libauto-reframing-arm64-v8a.so)" = e8921a043f2050d69d11f906820a400054c835bebd0f743bff7a33405c42f835 ] || abort "Unsupported auto-reframing library."
fi
for other in /data/adb/modules/* /data/adb/modules_update/*; do
    [ -d "$other" ] || continue
    [ "${other##*/}" = unica_fancam_bridge_test ] && continue
    [ -f "$other/disable" ] && continue
    [ -f "$other/remove" ] && continue
    for lib in libmediacontextanalyzer.so libfancam_bridge.so libauto-reframing-arm64-v8a.so; do
        [ ! -e "$other/system/lib64/$lib" ] || abort "Conflicting module ${other##*/}: $lib. Disable it, reboot, retry."
    done
    [ ! -e "$other/system/etc/linker.config.pb" ] || abort "Conflicting linker overlay in ${other##*/}. Disable it, reboot, retry."
    for model in Locator Reid; do
        [ ! -e "$other/system/etc/mediacontextanalyzer/$model.dlc" ] || abort "Disable the older DLC test module ${other##*/}, reboot, then retry."
    done
done
(cd "$MODPATH" && sha256sum -c payload.sha256) || abort "Payload verification failed."
[ -s /system/etc/linker.config.pb ] || abort "Missing live linker.config.pb."
cp /system/etc/linker.config.pb "$MODPATH/linker.config.original.pb" || abort "Cannot back up linker configuration."
cp "$MODPATH/linker.config.original.pb" "$MODPATH/system/etc/linker.config.pb" || abort "Cannot create linker overlay."
# Protobuf field 4: repeated shared library string. Append only absent entries.
if ! strings "$MODPATH/system/etc/linker.config.pb" | grep -Fxq libauto-reframing-arm64-v8a.so; then
    printf '\042\036libauto-reframing-arm64-v8a.so' >> "$MODPATH/system/etc/linker.config.pb" || abort "Cannot add decoder library entry."
fi
if ! strings "$MODPATH/system/etc/linker.config.pb" | grep -Fxq libfancam_bridge.so; then
    printf '\042\023libfancam_bridge.so' >> "$MODPATH/system/etc/linker.config.pb" || abort "Cannot add bridge library entry."
fi
ui_print "- SYNTHETIC TEST: video pixels are ignored."
ui_print "- Full Gallery tracking/export is unverified; media apps may crash."
ui_print "- Includes the tested decoder and clean S24U DLC files (synthetic mode bypasses their graphs)."
set_perm_recursive "$MODPATH/system" 0 0 0755 0644
set_perm_recursive "$MODPATH/system/lib64" 0 0 0755 0644 u:object_r:system_lib_file:s0
set_perm "$MODPATH/probe/mca_probe" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
ui_print "- Reboot, then test My FanCam on a short disposable video."
ui_print "- To restore: disable/remove this module and reboot."
