# Copyright (c) 2025 Salvo Giangreco
# SPDX-License-Identifier: GPL-3.0-or-later

# [
source "$SRC_DIR/scripts/utils/common_utils.sh"
# ]

# GET_DISK_USAGE <file>
# Returns the size in bytes of the supplied file.
GET_DISK_USAGE()
{
    _CHECK_NON_EMPTY_PARAM "FILE" "$1" || return 1

    local FILE="$1"

    if [ ! -e "$FILE" ]; then
        LOGE "File not found: ${FILE//$SRC_DIR\//}"
        return 1
    fi

    local SIZE
    # https://android.googlesource.com/platform/build/+/refs/tags/android-15.0.0_r1/tools/releasetools/build_image.py#63
    SIZE="$(du -b -k -s "$FILE" | cut -f 1)"

    bc -l <<< "$SIZE * 1024"
}

# GET_IMAGE_SIZE <file>
# Returns the size in bytes of the supplied image.
GET_IMAGE_SIZE()
{
    _CHECK_NON_EMPTY_PARAM "FILE" "$1" || return 1

    local FILE="$1"

    if [ ! -f "$FILE" ]; then
        LOGE "File not found: ${FILE//$SRC_DIR\//}"
        return 1
    fi

    if IS_SPARSE_IMAGE "$FILE"; then
        local BLOCK_SIZE
        local BLOCKS
        BLOCK_SIZE="$(printf "%d" "0x$(READ_BYTES_AT "$FILE" "12" "4")")"
        BLOCKS="$(printf "%d" "0x$(READ_BYTES_AT "$FILE" "16" "4")")"

        bc -l <<< "$BLOCKS * $BLOCK_SIZE"
    else
        GET_DISK_USAGE "$FILE"
    fi
}

# [
DEPENDENCIES=(
    "7z" "awk" "basename" "bc" "brotli" "cat" "clang" "cmake"
    "cp" "cpio" "curl" "cut" "cwebp" "dd" "dirname" "du" "ffmpeg"
    "file" "getfattr" "git" "grep" "head" "java" "jq" "ln"
    "lz4" "make" "md5sum" "mkdir" "mount" "mv" "perl" "protoc"
    "python3" "rm" "rsync" "sed" "sha1sum" "sort" "split" "stat"
    "sudo" "tail" "tar" "touch" "tr" "truncate" "umount" "unzip"
    "wc" "whoami" "xargs" "xxd" "zip" "zstd"
)
MISSING=()
for d in "${DEPENDENCIES[@]}"; do
    if ! type "$d" &> /dev/null; then
        MISSING+=("$d")
    fi
done
if [ "${#MISSING[@]}" -ne 0 ]; then
    echo -e '\033[1;31m'"The following dependencies are missing from your system:"'\033[0;31m' >&2
    printf '%s ' "${MISSING[@]}" >&2
    echo -e '\033[0m' >&2
    return 1
fi
unset DEPENDENCIES MISSING

if ! "$SRC_DIR/external/make.sh" --check-tools; then
    LOG_STEP_IN true "Building required tools..."
    "$SRC_DIR/external/make.sh" || return 1
    LOG_STEP_OUT
fi
# ]

# GET_RELEASE_ZIP_NAME <version> <device> <firmware suffix>
GET_RELEASE_ZIP_NAME()
{
    local VERSION="$1"
    local DEVICE="$2"
    local SUFFIX="$3"
    local COMMIT
    local RELEASE
    local ORIGINAL_VERSION="$VERSION"
    local SIGN=""

    VERSION="${VERSION%-dirty}"
    COMMIT="${VERSION##*-}"
    RELEASE="${VERSION%-*}"
    if [[ "$RELEASE" =~ ^(OneUI[^_]+)_([^_]+)_UnOfficial_by_Rezoss[+]*$ ]]; then
        echo "$(TZ=Europe/Paris date +%d%m%y-%H%M%S)_${COMMIT}_${BASH_REMATCH[1]}-${SUFFIX}_UN1CA-${BASH_REMATCH[2]}_Rezoss_${DEVICE}.zip"
    else
        # Preserve naming for releases using a different version schema.
        if ! $DEBUG || $ROM_IS_OFFICIAL; then
            SIGN="-sign"
        fi
        echo "UN1CA_${ORIGINAL_VERSION}_$(date +%d%m%y-%H%M%S)_${DEVICE}${SIGN}_${SUFFIX}.zip"
    fi
}
