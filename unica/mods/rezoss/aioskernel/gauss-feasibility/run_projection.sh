#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Run an isolated graph; never installs an APK or edits system/vendor files.
set -euo pipefail
: "${QNN_SDK_ROOT:?Point to a matching QAIRT SDK with headers, Android libraries and V73 skel}"
WEIGHTS=${1:?Usage: run_projection.sh RECOVERED_U16 WEIGHT_SCALE NEW_OUTPUT_DIRECTORY}
SCALE=${2:?Provide the recovered QNN tensor scale}
OUTPUT=${3:?Provide a new output directory}
[[ ! -e "$OUTPUT" ]] || { echo "Refusing existing output: $OUTPUT" >&2; exit 1; }
[[ "$SCALE" =~ ^[0-9]+([.][0-9]+)?([eE][-+]?[0-9]+)?$ ]] || exit 2
[[ $(stat -c %s "$WEIGHTS") == 196608 ]] || { echo 'Expected a 3072x32 uint16 matrix' >&2; exit 2; }
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
NDK=${ANDROID_NDK_ROOT:-$HOME/Android/Sdk/ndk/28.2.13676358}
ADB=/mnt/c/platform-tools/adb.exe
mkdir -p "$OUTPUT"
OUTPUT=$(realpath "$OUTPUT")
WORK=$(mktemp -d /tmp/rezoss-gauss-projection.XXXXXX)
DEVICE=/data/local/tmp/$(basename "$WORK")
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android33-clang" \
    -std=c11 -O2 -Wall -Wextra -Werror -I "$QNN_SDK_ROOT/include/QNN" \
    "$ROOT/probe_lora_matmul.c" -ldl -lm -o "$WORK/probe-lora"
cp "$WEIGHTS" "$WORK/weight.u16"
"$ADB" shell mkdir -p "$DEVICE"
"$ADB" push "$WORK/probe-lora" "$WORK/weight.u16" \
    "$QNN_SDK_ROOT/lib/aarch64-android/libQnnHtp.so" \
    "$QNN_SDK_ROOT/lib/aarch64-android/libQnnHtpPrepare.so" \
    "$QNN_SDK_ROOT/lib/aarch64-android/libQnnHtpV73Stub.so" \
    "$QNN_SDK_ROOT/lib/hexagon-v73/unsigned/libQnnHtpV73Skel.so" "$DEVICE/"
STATUS=0
"$ADB" shell "cd $DEVICE && chmod 755 probe-lora && export LD_LIBRARY_PATH=\$PWD:/vendor/lib64 ADSP_LIBRARY_PATH=\$PWD && timeout 60 ./probe-lora weight.u16 $SCALE result.u16 > runner.txt 2>&1" || STATUS=$?
"$ADB" pull "$DEVICE/runner.txt" "$OUTPUT/runner.txt"
if "$ADB" pull "$DEVICE/result.u16" "$OUTPUT/result.u16"; then
    python3 "$ROOT/compare_projection.py" "$WEIGHTS" "$OUTPUT/result.u16" "$SCALE" > "$OUTPUT/comparison.json"
fi
printf '%s\n' "$STATUS" > "$OUTPUT/exit-status.txt"
echo "Evidence: $OUTPUT; device exit $STATUS (1 means the strict numerical check failed)"
exit "$STATUS"
