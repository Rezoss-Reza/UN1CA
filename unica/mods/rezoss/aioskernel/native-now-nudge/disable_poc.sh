#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
ADB=/mnt/c/platform-tools/adb.exe
"$ADB" shell settings put global rezoss_now_nudge_native 0
"$ADB" shell am force-stop com.samsung.android.smartsuggestions
echo 'Now Nudge returned to the previous RezossAI/Qwen routing. Test model files remain available.'
