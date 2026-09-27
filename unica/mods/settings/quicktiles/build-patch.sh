#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
SOURCE_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ANDROID_JAR="${ANDROID_JAR:-$SDK/platforms/android-37.0/android.jar}"
D8="${D8:-$SDK/build-tools/37.0.0/d8}"
WORK=$(mktemp -d /tmp/unica-quicktiles-build.XXXXXX)
mkdir -p "$WORK/classes" "$WORK/dex"
mapfile -t SOURCES < <(find "$SOURCE_DIR/java" -type f -name '*.java' | sort)
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -classpath "$ANDROID_JAR" -d "$WORK/classes" "${SOURCES[@]}"
javac -encoding UTF-8 -classpath "$ANDROID_JAR:$WORK/classes" -d "$WORK/tests" "$SOURCE_DIR/tests/TileConfigTest.java"
java -classpath "$WORK/tests:$WORK/classes:$ANDROID_JAR" io.mesalabs.unica.quicktiles.TileConfigTest
jar cf "$WORK/classes.jar" -C "$WORK/classes" .
"$D8" --min-api 35 --lib "$ANDROID_JAR" --output "$WORK/dex" "$WORK/classes.jar"
baksmali disassemble --api 35 "$WORK/dex/classes.dex" -o "$WORK/smali"
python3 "$SOURCE_DIR/generate-patch.py" "$WORK/smali"
echo "Compiler output retained at $WORK"
