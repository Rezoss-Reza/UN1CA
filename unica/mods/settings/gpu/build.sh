#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../../../.." && pwd)
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ANDROID_JAR="${ANDROID_JAR:-$SDK/platforms/android-37.0/android.jar}"
D8="${D8:-$SDK/build-tools/37.0.0/d8}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/28.2.13676358}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
WORK=$(mktemp -d /tmp/unica-gpu-build.XXXXXX)
AVB="$ROOT/external/android-tools/vendor/avb/libavb"
"$HERE/verify-firmware.sh" "${GPU_VENDOR_BOOT:-$ROOT/out/fw/SM-S918B_EUX/kernel/vendor_boot.img}" \
    "${GPU_DRIVER:-$ROOT/out/fw/SM-S918B_EUX/vendor_dlkm/lib/modules/msm_kgsl.ko}"
mkdir -p "$WORK/classes" "$WORK/dex" "$HERE/../system/etc/unica/gpu"
mapfile -t SOURCES < <(find "$HERE/java" -type f -name '*.java' | sort)
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -classpath "$ANDROID_JAR" -d "$WORK/classes" "${SOURCES[@]}"
jar cf "$WORK/classes.jar" -C "$WORK/classes" .
"$D8" --min-api 35 --lib "$ANDROID_JAR" --output "$WORK/dex" "$WORK/classes.jar"
baksmali disassemble --api 35 "$WORK/dex/classes.dex" -o "$WORK/smali"
python3 "$HERE/generate-patch.py" "$WORK/smali"
smali assemble --api 35 "$WORK/smali" -o "$WORK/verified.dex"
"$TOOLCHAIN/aarch64-linux-android35-clang" -Os -DAVB_COMPILATION -I"$AVB" -I"$AVB/crypto" -c "$AVB/crypto/sha256_impl.c" -o "$WORK/sha.o"
for variant in worker recovery; do
    EXTRA=()
    DEST="$HERE/../system/bin/unica-gpu"
    if [[ "$variant" == recovery ]]; then
        EXTRA=(-DGPU_RECOVERY)
        DEST="$HERE/../system/etc/unica/gpu/restore"
    fi
    "$TOOLCHAIN/aarch64-linux-android35-clang++" -std=c++17 -Os -static -DAVB_COMPILATION \
        -I"$AVB" -I"$AVB/crypto" -Wall -Wextra -Werror -Wno-unused-function \
        -Wl,-z,relro,-z,now -Wl,--strip-all "${EXTRA[@]}" \
        "$HERE/native/worker.cpp" "$WORK/sha.o" -o "$WORK/$variant"
    if [[ -f "$DEST" ]] && ! cmp -s "$WORK/$variant" "$DEST"; then
        cp -p "$DEST" "$DEST.bak.$(date +%Y%m%d_%H%M%S)"
    fi
    install -m 0755 "$WORK/$variant" "$DEST"
done
install -m 0644 "$HERE/NOTICE.txt" "$HERE/../system/etc/unica/gpu/NOTICE.txt"
echo "GPU compiler output retained at $WORK"
