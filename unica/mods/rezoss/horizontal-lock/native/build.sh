#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
src_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
output_dir="${1:?Usage: build.sh /tmp/output-directory}"
compiler="${HORIZON_CC:-$HOME/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang}"
mkdir -p "$output_dir"
for name in libunica_horizon_core.so test_core; do
    [[ ! -e "$output_dir/$name" ]] || { echo "Refusing to overwrite $output_dir/$name" >&2; exit 1; }
done
"$compiler" -std=c11 -O2 -Wall -Wextra -Werror -fPIC -shared \
    "$src_dir/horizon_core.c" -lm -Wl,-z,defs -Wl,-soname,libunica_horizon_core.so \
    -o "$output_dir/libunica_horizon_core.so"
"$compiler" -std=c11 -O2 -Wall -Wextra -Werror \
    "$src_dir/test_core.c" -L"$output_dir" -lunica_horizon_core -lm \
    -Wl,-rpath,'$ORIGIN' -o "$output_dir/test_core"
