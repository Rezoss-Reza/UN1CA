#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Build maintained Java against the compile-only Xposed API.
set -euo pipefail

MOD_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd -- "$MOD_DIR/../../../.." && pwd)"
API_JAR="$MOD_DIR/compile-only/xposed-api-82.jar"
SDK_DIR="${ANDROID_HOME:-$HOME/Android/Sdk}"
D8="${D8:-$SDK_DIR/build-tools/37.0.0/d8}"
OUTPUT="${1:-$REPO_DIR/out/archived/mosey-gms-hook/MoseyGmsHook-1.5.apk}"
ANDROID_JAR="${ANDROID_JAR:-$HOME/Android/Sdk/platforms/android-37.0/android.jar}"
AAPT2="${AAPT2:-aapt2}"
APKSIGNER="${APKSIGNER:-apksigner}"
CERT="$REPO_DIR/security/aosp_testkey.x509.pem"
KEY="$REPO_DIR/security/aosp_testkey.pk8"

[[ $# -le 1 ]] || { echo "Usage: $0 [output.apk]" >&2; exit 2; }
[[ ! -e "$OUTPUT" ]] || { echo "Refusing to overwrite $OUTPUT" >&2; exit 1; }
for tool in "$AAPT2" "$APKSIGNER" "$D8" python3 javac jar; do
    command -v "$tool" >/dev/null || { echo "Missing tool: $tool" >&2; exit 1; }
done
for file in "$API_JAR" "$ANDROID_JAR" "$CERT" "$KEY"; do
    [[ -f "$file" ]] || { echo "Missing input: $file" >&2; exit 1; }
done

BUILD_DIR="$(mktemp -d /tmp/mosey-gms-hook.XXXXXXXX)"
trap 'printf "Build workspace retained: %s\n" "$BUILD_DIR"' EXIT

# Pin the compile-only API and the matching installed-app signing certificate.
python3 - "$API_JAR" "$CERT" <<'PY'
import base64, hashlib, pathlib, sys
expected = "f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25"
if hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest() != expected:
    raise SystemExit("Unexpected compile-only Xposed API")
pem = pathlib.Path(sys.argv[2]).read_text()
der = base64.b64decode("".join(line for line in pem.splitlines() if not line.startswith("---")))
if hashlib.sha256(der).hexdigest() != "a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc":
    raise SystemExit("Signing certificate does not match the installed baseline")
PY

mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/dex"
mapfile -t JAVA_SOURCES < <(find "$MOD_DIR/java" -type f -name '*.java' | sort)
javac --release 8 -classpath "$ANDROID_JAR:$API_JAR" \
    -d "$BUILD_DIR/classes" "${JAVA_SOURCES[@]}"
jar cf "$BUILD_DIR/hook.jar" -C "$BUILD_DIR/classes" .
"$D8" --min-api 26 --lib "$ANDROID_JAR" --classpath "$API_JAR" \
    --output "$BUILD_DIR/dex" "$BUILD_DIR/hook.jar"
"$AAPT2" link --manifest "$MOD_DIR/AndroidManifest.xml" \
    -I "$ANDROID_JAR" -o "$BUILD_DIR/unsigned.apk"

python3 - "$MOD_DIR" "$BUILD_DIR" <<'PYAPK'
import pathlib, sys, zipfile
mod, build = map(pathlib.Path, sys.argv[1:])
with zipfile.ZipFile(build / "unsigned.apk", "a") as dst:
    dst.write(build / "dex/classes.dex", "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
    dst.write(mod / "assets/xposed_init", "assets/xposed_init", compress_type=zipfile.ZIP_DEFLATED)
PYAPK

"$APKSIGNER" sign --key "$KEY" --cert "$CERT" \
    --out "$BUILD_DIR/signed.apk" "$BUILD_DIR/unsigned.apk"
"$APKSIGNER" verify --verbose "$BUILD_DIR/signed.apk"

mkdir -p -- "$(dirname -- "$OUTPUT")"
# noclobber also protects against a concurrent build creating the output.
(set -o noclobber; cat "$BUILD_DIR/signed.apk" > "$OUTPUT")
printf 'Built: %s\n' "$OUTPUT"
