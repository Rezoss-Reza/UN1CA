#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Build the MLC Android runtime/model library into a caller-selected directory.
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
: "${MLC_LLM_SOURCE_DIR:?Set MLC_LLM_SOURCE_DIR to an initialized MLC-LLM checkout}"
command -v mlc_llm >/dev/null || { echo "mlc_llm is not installed in this environment" >&2; exit 1; }
[[ -d "$MLC_LLM_SOURCE_DIR/3rdparty/tvm" ]] || { echo "MLC_LLM_SOURCE_DIR is missing 3rdparty/tvm" >&2; exit 1; }

OUTPUT=${1:-$ROOT/dist}
[[ ! -e "$OUTPUT" ]] || { echo "Output already exists: $OUTPUT" >&2; exit 1; }
WORK=$(mktemp -d "${TMPDIR:-/tmp}/rezoss-mlc-package.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/project"
cp "$ROOT/mlc-package-config.json" "$WORK/project/mlc-package-config.json"
(
    cd "$WORK/project"
    export MLC_LLM_SOURCE_DIR
    export TVM_SOURCE_DIR="$MLC_LLM_SOURCE_DIR/3rdparty/tvm"
    mlc_llm package
)
[[ -f "$WORK/project/dist/lib/mlc4j/output/tvm4j_core.jar" ]] || {
    echo "MLC packaging did not produce tvm4j_core.jar" >&2; exit 1;
}
[[ -f "$WORK/project/dist/lib/mlc4j/src/main/assets/mlc-app-config.json" ]] || {
    echo "MLC packaging did not produce mlc-app-config.json" >&2; exit 1;
}
[[ -d "$WORK/project/dist/lib/mlc4j/output/arm64-v8a" ]] || {
    echo "MLC packaging did not produce arm64-v8a runtime libraries" >&2; exit 1;
}
mkdir -p "$(dirname -- "$OUTPUT")"
mv "$WORK/project/dist" "$OUTPUT"
echo "MLC Android package written to $OUTPUT"
