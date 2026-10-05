#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
src_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
output_dir="${1:?Usage: build_adapter.sh /tmp/new-output-directory}"
compiler="${HORIZON_CC:-$HOME/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang}"
[[ ! -e "$output_dir" ]] || { echo "Refusing existing output directory" >&2; exit 1; }
mkdir -p "$output_dir"
"$compiler" -std=c11 -O2 -Wall -Wextra -Werror -fPIC -shared \
    "$src_dir/../native/horizon_core.c" "$src_dir/../native/horizon_stream.c" \
    -lm -Wl,-z,defs -Wl,-soname,libunica_horizon_core.so \
    -o "$output_dir/libunica_horizon_core.so"
for name in adapter_trace adapter_mock adapter_pipeline_mock; do
    "$compiler" -O2 -Wall -Wextra -Werror "$src_dir/$name.c" \
        -L"$output_dir" -lunica_horizon_core -lm -Wl,-rpath,'$ORIGIN' \
        -o "$output_dir/$name"
done
"$compiler" -std=c11 -O2 -Wall -Wextra -Werror "$src_dir/../native/test_stream.c" \
    -L"$output_dir" -lunica_horizon_core -lm -Wl,-rpath,'$ORIGIN' \
    -o "$output_dir/test_stream"
