#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")" && pwd)
WORK=${1:?Usage: compile.sh NEW_TEMP_DIRECTORY}
[[ ! -e "$WORK" ]] || { echo "Output already exists; choose a new temporary directory" >&2; exit 2; }
mkdir -p "$WORK/classes" "$WORK/dex"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
mapfile -t SOURCES < <(find "$ROOT/java" "$ROOT/stubs" -name '*.java' -type f | sort)
javac -source 8 -target 8 -Xlint:-options -cp "$SDK/platforms/android-37.0/android.jar" -d "$WORK/classes" "${SOURCES[@]}"
# Package only our code, never Samsung compile stubs.
mkdir -p "$WORK/package/io/mesalabs/unica/lockscreen"
for source in "$ROOT/java/io/mesalabs/unica/lockscreen/"*.java; do
    name=$(basename "$source" .java)
    cp "$WORK/classes/io/mesalabs/unica/lockscreen/$name.class" "$WORK/package/io/mesalabs/unica/lockscreen/"
    for inner in "$WORK/classes/io/mesalabs/unica/lockscreen/$name"\$*.class; do
        [[ -e "$inner" ]] && cp "$inner" "$WORK/package/io/mesalabs/unica/lockscreen/"
    done
done
jar cf "$WORK/controls.jar" -C "$WORK/package" io
"$SDK/build-tools/37.0.0/d8" --min-api 36 --lib "$SDK/platforms/android-37.0/android.jar" --output "$WORK/dex" "$WORK/controls.jar"
baksmali disassemble --api 35 "$WORK/dex/classes.dex" -o "$WORK/smali"
