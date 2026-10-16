#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Native dm3q builds publish the model pack under the existing installable APK name.
set -euo pipefail
: "${SRC_DIR:?Source buildenv.sh dm3q first}"
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$ROOT/../aioskernel/native-now-nudge/config.sh"
if [[ "$UNICA_ENABLE_NATIVE_NOW_NUDGE" == true ]]; then
    exec bash "$ROOT/modelpack/build.sh" "$@"
else
    exec bash "$ROOT/build_legacy.sh" "$@"
fi
