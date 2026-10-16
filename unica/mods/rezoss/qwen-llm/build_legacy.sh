#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
: "${SRC_DIR:?Source buildenv.sh dm3q first}"
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
OUT="${1:-$SRC_DIR/out/archived/QWEN_LLM/QWEN_LLM_rezoss.apk}"
MODEL_DIR="${QWEN_MODEL_DIR:-$SRC_DIR/out/archived/QWEN_LLM/model}"
MODEL=qwen2.5-1.5b-instruct-q4_k_m.gguf
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
TOOLS="$SDK/build-tools/37.0.0"
WORK=$(mktemp -d /tmp/rezoss-user-engine.XXXXXX)
trap 'rm -rf "$WORK"' EXIT
[[ -f "$MODEL_DIR/$MODEL" ]] || { echo "Run fetch-model.sh $MODEL_DIR first" >&2; exit 1; }
printf '%s  %s\n' 6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e "$MODEL_DIR/$MODEL" | sha256sum -c -
[[ $(stat -c %s "$MODEL_DIR/$MODEL") -le 3000000000 ]]
mkdir -p "$WORK/classes" "$WORK/dex" "$WORK/assets" "$WORK/lib/arm64-v8a"
mapfile -t SOURCES < <(find "$ROOT/java" -name '*.java' -type f | sort)
javac -source 8 -target 8 -Xlint:-options -classpath "$SDK/platforms/android-37.0/android.jar" -d "$WORK/classes" "${SOURCES[@]}"
jar cf "$WORK/classes.jar" -C "$WORK/classes" .
"$TOOLS/d8" --min-api 35 --lib "$SDK/platforms/android-37.0/android.jar" --output "$WORK/dex" "$WORK/classes.jar"
"$TOOLS/aapt2" link --manifest "$ROOT/AndroidManifest.xml" -I "$SDK/platforms/android-37.0/android.jar" -o "$WORK/base.apk"
cp "$WORK/dex/classes.dex" "$WORK/"
cp "$ROOT/../local-summary-service/system/bin/unica-summary-llama-server" "$WORK/lib/arm64-v8a/librezoss_llama.so"
ln "$MODEL_DIR/$MODEL" "$WORK/assets/$MODEL" 2>/dev/null || cp "$MODEL_DIR/$MODEL" "$WORK/assets/$MODEL"
cp "$MODEL_DIR/QWEN-LICENSE" "$WORK/assets/"
(
    cd "$WORK"
    zip -q -0 base.apk classes.dex lib/arm64-v8a/librezoss_llama.so "assets/$MODEL" assets/QWEN-LICENSE
)
"$TOOLS/zipalign" -P 16 -f 4 "$WORK/base.apk" "$WORK/aligned.apk"
CERT=aosp
if ${ROM_IS_OFFICIAL:-false}; then CERT=unica; fi
"$TOOLS/apksigner" sign --key "$SRC_DIR/security/${CERT}_platform.pk8" \
    --cert "$SRC_DIR/security/${CERT}_platform.x509.pem" --out "$WORK/signed.apk" "$WORK/aligned.apk"
"$TOOLS/apksigner" verify --verbose "$WORK/signed.apk"
"$TOOLS/zipalign" -c -P 16 4 "$WORK/signed.apk"
[[ $(stat -c %s "$WORK/signed.apk") -le 3000000000 ]]
mkdir -p "$(dirname -- "$OUT")"
if [[ -e "$OUT" ]]; then cp -a "$OUT" "$OUT.bak.$(date +%Y%m%d_%H%M%S)"; fi
mv "$WORK/signed.apk" "$OUT"
APK_SHA256_LINE=$(sha256sum "$OUT")
APK_SHA256=${APK_SHA256_LINE%% *}
APK_SIZE=$(stat -c %s "$OUT")
VERSION_CODE=$(grep -o "android:versionCode=\"[^\"]*\"" "$ROOT/AndroidManifest.xml" | head -n1)
VERSION_CODE=${VERSION_CODE#android:versionCode=\"}
VERSION_CODE=${VERSION_CODE%\"}
VERSION_NAME=$(grep -o "android:versionName=\"[^\"]*\"" "$ROOT/AndroidManifest.xml" | head -n1)
VERSION_NAME=${VERSION_NAME#android:versionName=\"}
VERSION_NAME=${VERSION_NAME%\"}
GIT_COMMIT=$(git -C "$SRC_DIR" rev-parse --short=12 HEAD 2>/dev/null || true)
VERSION_FILE="$OUT.version"
{
    printf "package=io.mesalabs.rezoss.qwen\n"
    printf "versionCode=%s\n" "$VERSION_CODE"
    printf "versionName=%s\n" "$VERSION_NAME"
    printf "apk=%s\n" "$OUT"
    printf "sizeBytes=%s\n" "$APK_SIZE"
    printf "sha256=%s\n" "$APK_SHA256"
    printf "model=%s\n" "$MODEL"
    printf "modelSha256=%s\n" "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"
    printf "builtAtUtc=%s\n" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    [[ -n "$GIT_COMMIT" ]] && printf "sourceCommit=%s\n" "$GIT_COMMIT"
} > "$VERSION_FILE"
printf "%s  %s\n" "$APK_SHA256" "$OUT"
printf "version metadata: %s\n" "$VERSION_FILE"
