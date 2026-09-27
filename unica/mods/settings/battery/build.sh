#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
SOURCE_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ANDROID_JAR="${ANDROID_JAR:-$SDK/platforms/android-37.0/android.jar}"
D8="${D8:-$SDK/build-tools/37.0.0/d8}"
WORK=$(mktemp -d /tmp/unica-battery-build.XXXXXX)
mkdir -p "$WORK/classes" "$WORK/dex" "$WORK/backend"
mapfile -t SOURCES < <(find "$SOURCE_DIR/java" -type f -name '*.java' | sort)
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -classpath "$ANDROID_JAR" -d "$WORK/classes" "${SOURCES[@]}"
jar cf "$WORK/classes.jar" -C "$WORK/classes" .
"$D8" --min-api 35 --lib "$ANDROID_JAR" --output "$WORK/dex" "$WORK/classes.jar"
baksmali disassemble --api 35 "$WORK/dex/classes.dex" -o "$WORK/smali"
python3 "$SOURCE_DIR/generate-patch.py" "$WORK/smali"
# Ship a small native init worker; Android-level controls run inside Settings.
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/28.2.13676358}"
CXX="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang++"
"$CXX" -std=c++17 -Os -static-libstdc++ -fPIE -pie -Wl,-z,relro,-z,now -Wl,--strip-all \
    "$SOURCE_DIR/native/kernel.cpp" -o "$SOURCE_DIR/../system/bin/unica-battery"
smali assemble --api 35 "$WORK/smali" -o "$WORK/verified.dex"
echo "Compiler output retained at $WORK"
