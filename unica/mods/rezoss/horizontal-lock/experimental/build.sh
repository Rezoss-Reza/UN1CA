#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
src_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
output_dir="${1:?Usage: build.sh /tmp/output-directory}"
compiler="${HORIZON_CC:-$HOME/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang}"
mkdir -p "$output_dir"
for name in frame_trace mock; do
    [[ ! -e "$output_dir/$name" ]] || { echo "Refusing to overwrite $output_dir/$name" >&2; exit 1; }
    "$compiler" -O2 -Wall -Wextra -Werror "$src_dir/$name.c" -o "$output_dir/$name"
done
