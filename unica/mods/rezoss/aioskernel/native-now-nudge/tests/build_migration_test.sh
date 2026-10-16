#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
: "${SRC_DIR:?Source buildenv.sh dm3q first}"
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}; TOOLS=$SDK/build-tools/37.0.0
WORK=$(mktemp -d /tmp/v73-migration-test.XXXXXX)
mkdir -p "$WORK/classes" "$WORK/dex"
python3 - "$ROOT/../../../local-summary-service/java/io/mesalabs/unica/localsummary/QwenEngine.java" "$WORK/MigratedQwenEngine.java" <<'PY'
from pathlib import Path
import sys
source=Path(sys.argv[1]).read_text()
source=source.replace('package io.mesalabs.unica.localsummary;', 'package io.mesalabs.rezoss.v73test;').replace('public final class QwenEngine {', 'public final class MigratedQwenEngine {')
Path(sys.argv[2]).write_text(source)
PY
javac -source 8 -target 8 -Xlint:-options -classpath "$SDK/platforms/android-37.0/android.jar" -d "$WORK/classes" \
    "$ROOT/V73MigrationTest.java" "$WORK/MigratedQwenEngine.java" "$ROOT/../../../local-summary-service/java/io/mesalabs/unica/localsummary/QwenEngine.java"
# Only the test belongs in the APK. QwenEngine links to the installed bridge.
jar cf "$WORK/test.jar" -C "$WORK/classes" io/mesalabs/rezoss/v73test
"$TOOLS/d8" --min-api 30 --lib "$SDK/platforms/android-37.0/android.jar" --output "$WORK/dex" "$WORK/test.jar"
"$TOOLS/aapt2" link --manifest "$ROOT/V73MigrationManifest.xml" -I "$SDK/platforms/android-37.0/android.jar" -o "$WORK/base.apk"
zip -q -j "$WORK/base.apk" "$WORK/dex/classes.dex"
CERT=aosp
if ${ROM_IS_OFFICIAL:-false}; then CERT=unica; fi
"$TOOLS/apksigner" sign --key "$SRC_DIR/security/${CERT}_platform.pk8" --cert "$SRC_DIR/security/${CERT}_platform.x509.pem" --out "${1:?Output APK required}" "$WORK/base.apk"
"$TOOLS/apksigner" verify "${1}"
