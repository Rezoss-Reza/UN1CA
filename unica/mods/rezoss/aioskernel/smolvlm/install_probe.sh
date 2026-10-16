#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Install only the isolated test package and provision its private model files.
set -euo pipefail
WORK=${1:?Usage: install_probe.sh RUNNER_WORK_DIRECTORY APK_FILE}
APK=${2:?Provide the signed companion APK}
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ADB=/mnt/c/platform-tools/adb.exe
STAGE=/data/local/tmp/rezoss-smolvlm-test
"$WORK/venv/bin/python" "$ROOT/fetch_models.py" "$WORK/models"
"$ADB" install -r --user 0 "$APK"
"$ADB" shell mkdir -p "$STAGE"
"$ADB" push "$WORK/models/hybrid_llama_qnn.pte" "$WORK/models/text_embedding_qnn.pte" \
    "$WORK/models/vision_encoder_qnn.pte" "$WORK/models/tokenizer.json" "$STAGE/"
PROVISION=$(mktemp /tmp/rezoss-vision-provision.XXXXXX)
cat > "$PROVISION" <<'SH'
set -eu
app=/data/user/0/io.mesalabs.rezoss.vision
stage=/data/local/tmp/rezoss-smolvlm-test
app_uid=$(stat -c %u "$app")
app_gid=$(stat -c %g "$app")
mkdir -p "$app/files/models"
chown "$app_uid:$app_gid" "$app/files"
for file in hybrid_llama_qnn.pte text_embedding_qnn.pte vision_encoder_qnn.pte tokenizer.json; do
    cp "$stage/$file" "$app/files/models/$file"
done
chown -R "$app_uid:$app_gid" "$app/files/models"
restorecon -RF "$app/files"
SH
"$ADB" shell su -c sh < "$PROVISION"
echo 'Installed io.mesalabs.rezoss.vision for user 0; Samsung packages were not replaced.'
