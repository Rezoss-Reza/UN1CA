#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Build only in a new scratch directory. No ROM output or firmware is modified.
set -euo pipefail
: "${SRC_DIR:?Source buildenv.sh dm3q first}"
: "${QNN_SDK_ROOT:?Point to an extracted QAIRT SDK with QNN headers and V73 libraries}"
WORK=${1:?Usage: build_runner.sh NEW_WORK_DIRECTORY}
[[ ! -e "$WORK" ]] || { echo "Refusing existing work directory: $WORK" >&2; exit 1; }
mkdir -p "$WORK"
WORK=$(realpath "$WORK")
NDK=${ANDROID_NDK_ROOT:-$HOME/Android/Sdk/ndk/28.2.13676358}
REV=d874339b8438c4bb7e3e185159e5714e5e04cdc8
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
git clone --filter=blob:none https://github.com/pytorch/executorch.git "$WORK/executorch"
git -C "$WORK/executorch" checkout --detach "$REV"
patch --dry-run --fuzz=0 -p1 -d "$WORK/executorch" < "$ROOT/legacy-context-header.patch"
patch --fuzz=0 -p1 -d "$WORK/executorch" < "$ROOT/legacy-context-header.patch"
patch --dry-run --fuzz=0 -p1 -d "$WORK/executorch" < "$ROOT/legacy-mask-rank.patch"
patch --fuzz=0 -p1 -d "$WORK/executorch" < "$ROOT/legacy-mask-rank.patch"
patch --dry-run --fuzz=0 -p1 -d "$WORK/executorch" < "$ROOT/legacy-float-io.patch"
patch --fuzz=0 -p1 -d "$WORK/executorch" < "$ROOT/legacy-float-io.patch"
git -C "$WORK/executorch" submodule update --init --depth 1 --jobs 3 \
    third-party/flatbuffers third-party/flatcc third-party/gflags third-party/json \
    third-party/pybind11 shim backends/xnnpack/third-party/FXdiv \
    backends/xnnpack/third-party/cpuinfo backends/xnnpack/third-party/pthreadpool
git -C "$WORK/executorch" submodule update --init --recursive --depth 1 --jobs 3 extension/llm/tokenizers
python3 -m venv "$WORK/venv"
"$WORK/venv/bin/pip" install torch==2.14.1 --index-url https://download.pytorch.org/whl/cpu
"$WORK/venv/bin/pip" install numpy==2.5.4 pillow==12.3.0 pyyaml ruamel.yaml packaging
cmake -S "$WORK/executorch" -B "$WORK/build-android" -G Ninja \
    -DCMAKE_INSTALL_PREFIX="$WORK/install" -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 \
    -DEXECUTORCH_BUILD_QNN=ON -DEXECUTORCH_BUILD_XNNPACK=OFF \
    -DEXECUTORCH_BUILD_DEVTOOLS=ON -DEXECUTORCH_BUILD_EXTENSION_LLM=ON \
    -DEXECUTORCH_BUILD_EXTENSION_LLM_RUNNER=ON -DEXECUTORCH_BUILD_EXTENSION_MODULE=ON \
    -DEXECUTORCH_BUILD_EXTENSION_DATA_LOADER=ON -DEXECUTORCH_BUILD_EXTENSION_FLAT_TENSOR=ON \
    -DEXECUTORCH_BUILD_EXTENSION_NAMED_DATA_MAP=ON -DEXECUTORCH_BUILD_EXTENSION_TENSOR=ON \
    -DEXECUTORCH_ENABLE_EVENT_TRACER=ON -DEXECUTORCH_ENABLE_LOGGING=ON \
    -DEXECUTORCH_BUILD_KERNELS_QUANTIZED=ON -DQNN_SDK_ROOT="$QNN_SDK_ROOT" \
    -DPYTHON_EXECUTABLE="$WORK/venv/bin/python" -DSUPPORT_REGEX_LOOKAHEAD=ON
cmake --build "$WORK/build-android" --target install -j "${BUILD_JOBS:-8}"
cmake -S "$WORK/executorch/examples/qualcomm" -B "$WORK/build-examples" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DCMAKE_BUILD_TYPE=Release -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 \
    -DCMAKE_PREFIX_PATH="$WORK/install;$WORK/build-android/third-party/gflags" \
    -DSUPPORT_REGEX_LOOKAHEAD=ON -DBUILD_TESTING=OFF -DEXECUTORCH_ENABLE_LOGGING=ON \
    -DEXECUTORCH_BUILD_KERNELS_QUANTIZED=ON -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH \
    -DPYTHON_EXECUTABLE="$WORK/venv/bin/python"
cmake --build "$WORK/build-examples" --target qnn_multimodal_runner -j "${BUILD_JOBS:-8}"
