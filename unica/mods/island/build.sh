#!/usr/bin/env bash
# Copyright (c) 2026 UN1CA contributors
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ANDROID_JAR="${ANDROID_JAR:-$SDK/platforms/android-37.0/android.jar}"
D8="${D8:-$SDK/build-tools/37.0.0/d8}"
BASE=${1:?Usage: build.sh clean-SystemUI-decode clean-Settings-decode}
SETTINGS=${2:?Supply a clean current SecSettings decode}
WORK=$(mktemp -d /tmp/unica-island-source-build.XXXXXX)
mkdir -p "$WORK/classes" "$WORK/stubs" "$WORK/dex"
(cd "$HERE/upstream" && sha256sum -c SHA256SUMS > /dev/null)
python3 "$HERE/prepare-source.py" "$WORK/java"
mapfile -t STUBS < <(find "$HERE/stubs" -type f -name '*.java' | sort)
javac -encoding UTF-8 -source 17 -target 17 -Xlint:-options -cp "$ANDROID_JAR" -d "$WORK/stubs" "${STUBS[@]}"
mapfile -t SOURCES < <(find "$WORK/java" -type f -name '*.java' | sort)
javac -encoding UTF-8 -source 17 -target 17 -Xlint:-options -cp "$WORK/stubs:$ANDROID_JAR" -d "$WORK/classes" "${SOURCES[@]}"
jar cf "$WORK/stubs.jar" -C "$WORK/stubs" .
jar cf "$WORK/classes.jar" -C "$WORK/classes" .
"$D8" --min-api 35 --lib "$ANDROID_JAR" --classpath "$WORK/stubs.jar" --output "$WORK/dex" "$WORK/classes.jar"
baksmali disassemble --api 37 "$WORK/dex/classes.dex" -o "$WORK/smali"
python3 "$HERE/generate-patches.py" "$WORK" "$BASE" "$SETTINGS"
echo "Compiler output retained at $WORK"
