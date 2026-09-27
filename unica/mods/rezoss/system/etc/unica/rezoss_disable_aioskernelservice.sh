#!/system/bin/sh
# Keep AIOSKernelService off by default while allowing SamsungAiCore to stay available.

disable_pkg()
{
    local pkg="$1"
    cmd package disable-user --user 0 "$pkg" >/dev/null 2>&1 || \
        pm disable-user --user 0 "$pkg" >/dev/null 2>&1 || \
        true
}

enable_pkg()
{
    local pkg="$1"
    cmd package enable "$pkg" >/dev/null 2>&1 || \
        pm enable "$pkg" >/dev/null 2>&1 || \
        true
}

if [ "$(getprop persist.sys.unica.enable_aioskernelservice 2>/dev/null)" != "1" ]; then
    disable_pkg "com.samsung.android.aioskernelservice"
fi

if [ "$(getprop persist.sys.unica.disable_samsung_aicore 2>/dev/null)" = "1" ] || \
   [ "$(getprop persist.sys.unica.samsung_aicore.disable 2>/dev/null)" = "1" ]; then
    disable_pkg "com.samsung.android.aicore"
else
    enable_pkg "com.samsung.android.aicore"
fi
