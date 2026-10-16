# SPDX-License-Identifier: GPL-3.0-or-later
# Device-local default; also accepts an exported environment override.
# Set the dm3q default to false to restore the previous ROM packaging.
if [[ "$TARGET_CODENAME" == "dm3q" ]]; then
    UNICA_ENABLE_NATIVE_NOW_NUDGE="${UNICA_ENABLE_NATIVE_NOW_NUDGE:-true}"
else
    UNICA_ENABLE_NATIVE_NOW_NUDGE="${UNICA_ENABLE_NATIVE_NOW_NUDGE:-false}"
fi
