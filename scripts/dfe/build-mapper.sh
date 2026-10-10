#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Build a fully static ARM64 recovery tool using the Android NDK.
set -euo pipefail
DFE_REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
DFE_NDK_ROOT="${ANDROID_NDK_HOME:-$HOME/android-ndk-r27}"
DFE_COMPILER="$DFE_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang"
DFE_OUTPUT="${1:?Usage: build-mapper.sh OUTPUT_PATH}"
"$DFE_COMPILER" -std=c11 -O2 -static -fno-emulated-tls -Wall -Wextra -Werror \
    -fstack-protector-strong -D_FORTIFY_SOURCE=2 -Wl,-z,relro,-z,now \
    "$DFE_REPO_ROOT/scripts/dfe/dfe-mapper.c" -o "$DFE_OUTPUT"
"$DFE_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" "$DFE_OUTPUT"
