#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
: "${SRC_DIR:?Source buildenv.sh dm3q first}"
: "${QNN_SDK_ROOT:?Point to the runtime matching the model export}"
WORK=${1:?Usage: build_bridge.sh RUNNER_WORK_DIRECTORY NEW_APK_PATH}
OUTPUT=${2:?Provide a new output APK path}
[[ ! -e "$OUTPUT" ]] || { echo "Refusing existing APK: $OUTPUT" >&2; exit 1; }
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
NDK=${ANDROID_NDK_ROOT:-$SDK/ndk/28.2.13676358}
TOOLS=$SDK/build-tools/37.0.0
TMP=$(mktemp -d /tmp/rezoss-vision-apk.XXXXXX)
echo "APK assembly directory: $TMP"
mkdir -p "$TMP/classes" "$TMP/dex" "$TMP/lib/arm64-v8a" "$TMP/assets"
mapfile -t SOURCES < <(find "$ROOT/bridge/java" -name '*.java' -type f | sort)
javac -source 8 -target 8 -Xlint:-options -classpath "$SDK/platforms/android-37.0/android.jar" -d "$TMP/classes" "${SOURCES[@]}"
jar cf "$TMP/classes.jar" -C "$TMP/classes" .
"$TOOLS/d8" --min-api 30 --lib "$SDK/platforms/android-37.0/android.jar" --output "$TMP/dex" "$TMP/classes.jar"
"$TOOLS/aapt2" link --manifest "$ROOT/bridge/AndroidManifest.xml" -I "$SDK/platforms/android-37.0/android.jar" -o "$TMP/base.apk"
ORIGINAL=$SRC_DIR/prebuilts/samsung/m3qxxx/system/priv-app/AIOSKernelService/AIOSKernelService.apk
printf '%s  %s\n' fca2f25b65a4db919b162d02decd6dba8b6d4eafc95bc43b36c5a1c606a19112 "$ORIGINAL" | sha256sum -c -
unzip -p "$ORIGINAL" classes.dex > "$TMP/classes.dex"
cp "$TMP/dex/classes.dex" "$TMP/classes2.dex"
cp "$WORK/build-examples/oss_scripts/llama/qnn_multimodal_runner" "$TMP/lib/arm64-v8a/libsmol_runner.so"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$TMP/lib/arm64-v8a/libsmol_runner.so"
cp "$WORK/install/lib/executorch/backends/qualcomm/libqnn_executorch_backend.so" "$TMP/lib/arm64-v8a/"
for LIB in libQnnHtp.so libQnnSystem.so libQnnHtpV73Stub.so; do
    cp "$QNN_SDK_ROOT/lib/aarch64-android/$LIB" "$TMP/lib/arm64-v8a/"
done
cp "$QNN_SDK_ROOT/lib/hexagon-v73/unsigned/libQnnHtpV73Skel.so" "$TMP/lib/arm64-v8a/"
cp "$WORK/inputs/cat.jpg" "$WORK/inputs/bus.jpg" "$TMP/assets/"
(
    cd "$TMP"
    zip -q -0 base.apk classes.dex classes2.dex lib/arm64-v8a/*.so assets/*.jpg
)
"$TOOLS/zipalign" -P 16 -f 4 "$TMP/base.apk" "$TMP/aligned.apk"
# An isolated test identity: no platform key or system package replacement.
KEYSTORE=${VISION_TEST_KEYSTORE:-$WORK/vision-test.jks}
if [[ ! -f "$KEYSTORE" ]]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias vision-test -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Rezoss Vision Isolated Test'
fi
"$TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out "$OUTPUT" "$TMP/aligned.apk"
"$TOOLS/apksigner" verify --verbose "$OUTPUT"
"$TOOLS/zipalign" -c -P 16 4 "$OUTPUT"
unzip -tq "$OUTPUT"
sha256sum "$OUTPUT"
