#!/usr/bin/env bash
# Copyright (c) 2026 Rezoss
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail
if [[ $# != 3 ]] && [[ $# != 1 || ( "$1" != --auto && "$1" != --build-auto ) ]]; then
    echo "Usage: build_release_ota <old-release.zip> <new-release.zip> <output.zip>" >&2
    echo "       build_release_ota --auto" >&2
    echo "       build_release_ota --build-auto" >&2
    exit 1
fi
: "${SRC_DIR:?Source buildenv.sh dm3q first}"
: "${TARGET_CODENAME:?Source buildenv.sh dm3q first}"
[[ "$TARGET_CODENAME" == dm3q ]] || { echo 'Currently supported: dm3q' >&2; exit 1; }
[[ "${TARGET_CACHE_PARTITION_SIZE:-none}" =~ ^[1-9][0-9]*$ ]] || {
    echo 'Configure TARGET_CACHE_PARTITION_SIZE and source buildenv.sh dm3q again' >&2
    exit 1
}
if [[ $# == 1 ]]; then
    operation=auto
    [[ "$1" != --build-auto ]] || operation=build-auto
    python3 "$SRC_DIR/scripts/internal/release_zip_ota.py" "$operation" "$OUT_DIR"
else
    python3 "$SRC_DIR/scripts/internal/release_zip_ota.py" build "$@"
fi
