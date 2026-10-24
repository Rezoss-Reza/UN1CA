#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../../../.." && pwd)
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ANDROID_JAR="${ANDROID_JAR:-$SDK/platforms/android-37.0/android.jar}"
D8="${D8:-$SDK/build-tools/37.0.0/d8}"
APKTOOL="${APKTOOL:-$ROOT/out/tools/bin/apktool}"
SDHMS_APK="${SDHMS_APK:-$ROOT/out/fw/SM-S918B_EUX/system/system/priv-app/SamsungDeviceHealthManagerService/SamsungDeviceHealthManagerService.apk}"
python3 "$HERE/verify-input.py" "$SDHMS_APK"
WORK=$(mktemp -d /tmp/unica-thermal-build.XXXXXX)
mkdir -p "$WORK/stubs" "$WORK/classes" "$WORK/dex"
mapfile -t STUBS < <(find "$HERE/stubs" -name '*.java' -type f | sort)
mapfile -t SOURCES < <(find "$HERE/java" -name '*.java' -type f | sort)
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -cp "$ANDROID_JAR" -d "$WORK/stubs" "${STUBS[@]}"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -cp "$WORK/stubs:$ANDROID_JAR" -d "$WORK/classes" "${SOURCES[@]}"
jar cf "$WORK/classes.jar" -C "$WORK/classes" .
jar cf "$WORK/stubs.jar" -C "$WORK/stubs" .
"$D8" --min-api 35 --lib "$ANDROID_JAR" --classpath "$WORK/stubs.jar" --output "$WORK/dex" "$WORK/classes.jar"
baksmali disassemble --api 35 "$WORK/dex/classes.dex" -o "$WORK/smali"
smali assemble --api 35 "$WORK/smali" -o "$WORK/verified.dex"
"$APKTOOL" d -f --no-debug-info "$SDHMS_APK" -o "$WORK/sdhms" > "$WORK/apktool.log" 2>&1
python3 "$HERE/generate-patches.py" "$WORK/smali" "$WORK/sdhms"
echo "Compiler output retained at $WORK"
