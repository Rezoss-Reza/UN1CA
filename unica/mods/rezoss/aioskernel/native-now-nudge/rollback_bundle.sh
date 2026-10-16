#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ADB=/mnt/c/platform-tools/adb.exe
[[ -f "$ROOT/SmartSuggestions-before.apk" ]] || { echo 'Run the rollback copy inside the generated rollback snapshot' >&2; exit 1; }
"$ADB" shell settings put global rezoss_now_nudge_native 0
"$ADB" shell am force-stop com.samsung.android.smartsuggestions
"$ADB" shell am force-stop com.samsung.android.offline.languagemodel
"$ADB" install --no-incremental -r "$ROOT/SmartSuggestions-before.apk"
if [[ -f "$ROOT/AIOS-was-absent" ]]; then
    "$ADB" uninstall com.samsung.android.aioskernelservice
else "$ADB" install --no-incremental -r "$ROOT/AIOS-before.apk"; fi
for KEY in rezoss_now_nudge_native rezoss_aios_v73_backend; do
    VALUE=$(cat "$ROOT/$KEY")
    if [[ "$VALUE" == null ]]; then "$ADB" shell settings delete global "$KEY";
    else [[ "$VALUE" =~ ^[01]$ ]] || exit 1; "$ADB" shell settings put global "$KEY" "$VALUE"; fi
done
echo 'Restored previous APKs and route settings.'
