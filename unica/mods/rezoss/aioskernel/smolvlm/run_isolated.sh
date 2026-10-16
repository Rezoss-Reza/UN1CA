#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
: "${QNN_SDK_ROOT:?Point to the extracted 2.37.1 SDK used to build the runner}"
WORK=${1:?Usage: run_isolated.sh RUNNER_WORK_DIRECTORY IMAGE_FILE}
IMAGE=${2:?Provide a JPEG or PNG test image}
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ADB=/mnt/c/platform-tools/adb.exe
DEVICE=/data/local/tmp/rezoss-smolvlm-test
RUN=$(mktemp -d /tmp/rezoss-vision-run.XXXXXX)
RUN_NAME=$(basename "$RUN")
"$WORK/venv/bin/python" "$ROOT/fetch_models.py" "$WORK/models"
"$WORK/venv/bin/python" "$ROOT/prepare_image.py" "$IMAGE" "$RUN/image.raw"
printf '%s\n' "$DEVICE/$RUN_NAME/image.raw" > "$RUN/inputs.txt"
"$ADB" shell mkdir -p "$DEVICE/$RUN_NAME"
"$ADB" push "$WORK/models/hybrid_llama_qnn.pte" "$WORK/models/text_embedding_qnn.pte" \
    "$WORK/models/vision_encoder_qnn.pte" "$WORK/models/tokenizer.json" "$DEVICE/"
"$ADB" push "$WORK/build-examples/oss_scripts/llama/qnn_multimodal_runner" \
    "$WORK/install/lib/executorch/backends/qualcomm/libqnn_executorch_backend.so" \
    "$QNN_SDK_ROOT/lib/aarch64-android/libQnnHtp.so" \
    "$QNN_SDK_ROOT/lib/aarch64-android/libQnnSystem.so" \
    "$QNN_SDK_ROOT/lib/aarch64-android/libQnnHtpV73Stub.so" \
    "$QNN_SDK_ROOT/lib/hexagon-v73/unsigned/libQnnHtpV73Skel.so" "$DEVICE/"
"$ADB" push "$RUN/image.raw" "$RUN/inputs.txt" "$DEVICE/$RUN_NAME/"
"$ADB" shell "cd $DEVICE && chmod 755 qnn_multimodal_runner && export LD_LIBRARY_PATH=\$PWD:/vendor/lib64 ADSP_LIBRARY_PATH=\$PWD REZOSS_LEGACY_FLOAT_IO=1 && timeout 120 ./qnn_multimodal_runner --tok_embedding_path text_embedding_qnn.pte --encoder_path vision_encoder_qnn.pte --decoder_path hybrid_llama_qnn.pte --tokenizer_path tokenizer.json --decoder_model_version smolvlm --image_path $RUN_NAME/inputs.txt --prompt 'Describe the image in one sentence.' --seq_len 192 --temperature 0 --output_path $RUN_NAME/output.txt --performance_output_path $RUN_NAME/performance.txt > $RUN_NAME/runner.txt 2>&1"
"$ADB" pull "$DEVICE/$RUN_NAME/." "$RUN/"
cat "$RUN/output.txt"
printf '\nEvidence: %s\n' "$RUN"
