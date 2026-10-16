#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
: "${SRC_DIR:?Source buildenv.sh dm3q first}"
: "${QNN_SDK_ROOT:?Use the matching 2.37.1 runtime}"
WORK=${1:?Usage: build_backend.sh EXECUTORCH_WORK ORIGINAL_DECODE NEW_APK}
DECODED=${2:?Provide original AIOS decode}
OUTPUT=${3:?Provide new output path}
[[ ! -e "$OUTPUT" ]] || { echo 'Refusing existing output' >&2; exit 1; }
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}; TOOLS=$SDK/build-tools/37.0.0
TMP=$(mktemp -d /tmp/rezoss-nudge-apk.XXXXXX)
echo "Workspace: $TMP"
mkdir -p "$TMP/classes" "$TMP/dex" "$TMP/lib/arm64-v8a"
cp -a "$DECODED/smali" "$TMP/"
patch --dry-run --fuzz=0 -p1 -d "$TMP" < "$ROOT/backend/0001-Delegate-opt-in-text-backend.patch"
patch --fuzz=0 -p1 -d "$TMP" < "$ROOT/backend/0001-Delegate-opt-in-text-backend.patch"
java -Xmx2g -cp "$SRC_DIR/out/tools/bin/apktool.jar" com.android.tools.smali.smali.Main assemble --api 28 -j 4 "$TMP/smali" -o "$TMP/classes.dex"
mapfile -t SOURCES < <(find "$ROOT/backend/java" -type f -name '*.java' | sort)
javac -source 8 -target 8 -Xlint:-options -classpath "$SDK/platforms/android-37.0/android.jar" -d "$TMP/classes" "${SOURCES[@]}"
jar cf "$TMP/classes.jar" -C "$TMP/classes" .
"$TOOLS/d8" --min-api 30 --lib "$SDK/platforms/android-37.0/android.jar" --output "$TMP/dex" "$TMP/classes.jar"
cp "$TMP/dex/classes.dex" "$TMP/classes2.dex"
"$TOOLS/aapt2" link --manifest "$ROOT/backend/AndroidManifest.xml" -I "$SDK/platforms/android-37.0/android.jar" -o "$TMP/base.apk"
cp "$WORK/build-examples/oss_scripts/llama/qnn_llama_runner" "$TMP/lib/arm64-v8a/libnudge_runner.so"
"$SDK/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$TMP/lib/arm64-v8a/libnudge_runner.so"
cp "$WORK/install/lib/executorch/backends/qualcomm/libqnn_executorch_backend.so" "$TMP/lib/arm64-v8a/"
for LIB in libQnnHtp.so libQnnSystem.so libQnnHtpV73Stub.so; do cp "$QNN_SDK_ROOT/lib/aarch64-android/$LIB" "$TMP/lib/arm64-v8a/"; done
cp "$QNN_SDK_ROOT/lib/hexagon-v73/unsigned/libQnnHtpV73Skel.so" "$TMP/lib/arm64-v8a/"
(cd "$TMP"; zip -q -0 base.apk classes.dex classes2.dex lib/arm64-v8a/*.so)
"$TOOLS/zipalign" -P 16 -f 4 "$TMP/base.apk" "$TMP/aligned.apk"
"$TOOLS/apksigner" sign --key "$SRC_DIR/security/aosp_platform.pk8" --cert "$SRC_DIR/security/aosp_platform.x509.pem" --out "$OUTPUT" "$TMP/aligned.apk"
"$TOOLS/apksigner" verify "$OUTPUT"
"$TOOLS/zipalign" -c -P 16 4 "$OUTPUT"
unzip -tq "$OUTPUT"
sha256sum "$OUTPUT"
