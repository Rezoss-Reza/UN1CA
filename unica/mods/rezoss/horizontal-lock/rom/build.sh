#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
src_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
output_dir="${1:?Usage: build.sh /tmp/fresh-output original-node.so}"
original_node="${2:?Original S23U EIS node required}"
compiler="${HORIZON_CC:-$HOME/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang}"
python="${HORIZON_PYTHON:-python3}"
[[ ! -e "$output_dir" ]] || { echo "Refusing existing output directory" >&2; exit 1; }
mkdir -p "$output_dir"
"$compiler" -std=c11 -O2 -Wall -Wextra -Werror -fPIC -fvisibility=hidden -mbranch-protection=standard -shared \
    "$src_dir/adapter.c" "$src_dir/../native/horizon_core.c" "$src_dir/../native/horizon_stream.c" \
    -lm -llog -Wl,-z,defs -Wl,-soname,libunica_horizon_adapter.so -o "$output_dir/libunica_horizon_adapter.so"
"$python" "$src_dir/patch_node.py" "$original_node" "$output_dir/com.qti.node.eisv3.so"
"$compiler" -std=c11 -D_DEFAULT_SOURCE -DHL_TEST -O2 -Wall -Wextra -Werror \
    "$src_dir/adapter.c" "$src_dir/test_adapter.c" \
    "$src_dir/../native/horizon_core.c" "$src_dir/../native/horizon_stream.c" \
    -lm -llog -o "$output_dir/test_adapter"
"$compiler" -O2 -Wall -Wextra -Werror "$src_dir/test_load_node.c" -ldl \
    -o "$output_dir/test_load_node"
