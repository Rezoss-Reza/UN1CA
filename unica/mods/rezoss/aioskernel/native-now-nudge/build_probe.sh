#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
: "${SRC_DIR:?Source buildenv.sh dm3q first}"
OUTPUT=${1:?Provide new APK path}
[[ ! -e "$OUTPUT" ]] || exit 1
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}; TOOLS=$SDK/build-tools/37.0.0
TMP=$(mktemp -d /tmp/rezoss-nudge-probe.XXXXXX)
mkdir -p "$TMP/classes" "$TMP/dex"
mapfile -t SOURCES < <(find "$ROOT/probe/java" -type f -name '*.java' | sort)
javac -source 8 -target 8 -Xlint:-options -classpath "$SDK/platforms/android-37.0/android.jar" -d "$TMP/classes" "${SOURCES[@]}"
jar cf "$TMP/classes.jar" -C "$TMP/classes" .
"$TOOLS/d8" --min-api 30 --lib "$SDK/platforms/android-37.0/android.jar" --output "$TMP/dex" "$TMP/classes.jar"
"$TOOLS/aapt2" link --manifest "$ROOT/probe/AndroidManifest.xml" -I "$SDK/platforms/android-37.0/android.jar" -o "$TMP/base.apk"
(cd "$TMP/dex"; zip -q "$TMP/base.apk" classes.dex)
"$TOOLS/zipalign" -f 4 "$TMP/base.apk" "$TMP/aligned.apk"
"$TOOLS/apksigner" sign --key "$SRC_DIR/security/aosp_platform.pk8" --cert "$SRC_DIR/security/aosp_platform.x509.pem" --out "$OUTPUT" "$TMP/aligned.apk"
"$TOOLS/apksigner" verify "$OUTPUT"
