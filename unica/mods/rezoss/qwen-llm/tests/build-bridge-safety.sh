#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
: "${SRC_DIR:?Source buildenv.sh first}"
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
TOOLS="$SDK/build-tools/37.0.0"
WORK=$(mktemp -d /tmp/rezoss-engine-test.XXXXXX)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/classes" "$WORK/dex"
javac -source 8 -target 8 -Xlint:-options -classpath "$SDK/platforms/android-37.0/android.jar" -d "$WORK/classes" \
    "$ROOT/BridgeSafetyTest.java" \
    "$ROOT/../../local-summary-service/java/io/mesalabs/unica/localsummary/QwenEngine.java" \
    "$ROOT/../../local-summary-service/client-java/io/mesalabs/rezoss/ai/ReplyFormat.java"
jar cf "$WORK/classes.jar" -C "$WORK/classes" io/mesalabs/rezoss/qwentest
"$TOOLS/d8" --min-api 35 --lib "$SDK/platforms/android-37.0/android.jar" --output "$WORK/dex" "$WORK/classes.jar"
"$TOOLS/aapt2" link --manifest "$ROOT/BridgeSafetyManifest.xml" -I "$SDK/platforms/android-37.0/android.jar" -o "$WORK/base.apk"
zip -q -j "$WORK/base.apk" "$WORK/dex/classes.dex"
CERT=aosp
if ${ROM_IS_OFFICIAL:-false}; then CERT=unica; fi
"$TOOLS/apksigner" sign --key "$SRC_DIR/security/${CERT}_platform.pk8" \
    --cert "$SRC_DIR/security/${CERT}_platform.x509.pem" --out "${1:?Output APK required}" "$WORK/base.apk"
