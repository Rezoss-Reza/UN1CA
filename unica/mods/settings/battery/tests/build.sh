#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ANDROID_JAR="${ANDROID_JAR:-$SDK/platforms/android-37.0/android.jar}"
D8="${D8:-$SDK/build-tools/37.0.0/d8}"
WORK=$(mktemp -d /tmp/unica-battery-tests.XXXXXX)
mkdir -p "$WORK/classes" "$WORK/dex"
javac -source 8 -target 8 -Xlint:-options -cp "$ANDROID_JAR" -d "$WORK/classes" "$HERE/../java/io/mesalabs/unica/battery/Profile.java" "$HERE/../java/io/mesalabs/unica/battery/BatteryEngine.java" "$HERE/../java/io/mesalabs/unica/battery/BatteryTransport.java" "$HERE/BatteryTests.java"
jar cf "$WORK/tests.jar" -C "$WORK/classes" .
"$D8" --min-api 35 --lib "$ANDROID_JAR" --output "$WORK/dex" "$WORK/tests.jar"
jar cf "$WORK/battery-tests.jar" -C "$WORK/dex" classes.dex
echo "Tests: $WORK/battery-tests.jar"
echo 'Run with app_process io.mesalabs.unica.battery.BatteryTests (no root needed; power writes simulated).'
