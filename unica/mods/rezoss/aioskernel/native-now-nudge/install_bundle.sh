#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Reversible data-APK proof of concept; no system partition or boot image writes.
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ADB=/mnt/c/platform-tools/adb.exe
(cd "$ROOT"; sha256sum -c SHA256SUMS)
MODEL=$("$ADB" shell getprop ro.product.model | tr -d '\r')
[[ "$MODEL" == SM-S918B ]] || { echo "Expected tested SM-S918B, found $MODEL" >&2; exit 1; }
"$ADB" shell su -c id | tr -d '\r' | rg -q 'uid=0' || { echo 'Root required for private model provisioning' >&2; exit 1; }
OFFLINE_CODE=$("$ADB" shell dumpsys package com.samsung.android.offline.languagemodel | tr -d '\r' | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -n1)
[[ "$OFFLINE_CODE" == 400124010 ]] || { echo "Requires tested full OfflineLanguageModel 4.0.01.24; found $OFFLINE_CODE" >&2; exit 1; }
if [[ ${1:-} == --check ]]; then echo 'Bundle hashes and tested-device prerequisites passed'; exit 0; fi
[[ $# == 0 ]] || { echo 'Usage: install_bundle.sh [--check]' >&2; exit 1; }
BACKUP=$(mktemp -d /tmp/rezoss-nudge-rollback.XXXXXX)
for KEY in rezoss_now_nudge_native rezoss_aios_v73_backend; do "$ADB" shell settings get global "$KEY" | tr -d '\r' > "$BACKUP/$KEY"; done
SMART_PATH=$("$ADB" shell pm path com.samsung.android.smartsuggestions | tr -d '\r' | sed -n 's/^package://p' | head -n1)
[[ -n "$SMART_PATH" ]] || { echo 'SmartSuggestions missing' >&2; exit 1; }
"$ADB" pull "$SMART_PATH" "$BACKUP/SmartSuggestions-before.apk"
AIOS_PATH=$("$ADB" shell pm path com.samsung.android.aioskernelservice | tr -d '\r' | sed -n 's/^package://p' | head -n1)
if [[ -n "$AIOS_PATH" ]]; then
    CODE=$("$ADB" shell dumpsys package com.samsung.android.aioskernelservice | tr -d '\r' | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -n1)
    [[ "$CODE" == 142801204 ]] || { echo "Existing AIOS is outside this POC; refusing to replace version $CODE" >&2; exit 1; }
    "$ADB" pull "$AIOS_PATH" "$BACKUP/AIOS-before.apk"
else touch "$BACKUP/AIOS-was-absent"; fi
cp "$ROOT/rollback_bundle.sh" "$BACKUP/rollback_bundle.sh"
echo "Rollback snapshot: $BACKUP"
"$ADB" shell settings put global rezoss_now_nudge_native 0
"$ADB" shell settings put global rezoss_aios_v73_backend 1
"$ADB" shell am force-stop com.samsung.android.offline.languagemodel
"$ADB" install --no-incremental -r "$ROOT/AIOS-V73.apk"
"$ADB" install --no-incremental -r "$ROOT/SmartSuggestions-V73.apk"
"$ADB" shell mkdir -p /data/local/tmp/rezoss-nudge-install
"$ADB" push "$ROOT/hybrid_llama_qnn.pte" "$ROOT/tokenizer.json" /data/local/tmp/rezoss-nudge-install/
"$ADB" push "$ROOT/provision_models.sh" /data/local/tmp/rezoss-nudge-install/provision_models.sh
"$ADB" shell su -c '"sh /data/local/tmp/rezoss-nudge-install/provision_models.sh"'
"$ADB" shell settings put global rezoss_now_nudge_native 1
"$ADB" shell am force-stop com.samsung.android.offline.languagemodel
"$ADB" shell am force-stop com.samsung.android.smartsuggestions
echo "Enabled V73 reply POC. Rollback: bash $BACKUP/rollback_bundle.sh"
