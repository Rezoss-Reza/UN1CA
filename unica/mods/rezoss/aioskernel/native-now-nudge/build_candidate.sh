#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Offline candidate only. Never installs an APK or changes the live route.
set -euo pipefail
: "${SRC_DIR:?Source buildenv.sh dm3q first}"
APK=${1:?Usage: build_candidate.sh EXACT_INSTALLED_APK DECODED_DIRECTORY NEW_OUTPUT_APK}
DECODED=${2:?Provide the exact APK decode}
OUTPUT=${3:?Provide a new output APK path}
[[ ! -e "$OUTPUT" ]] || { echo "Refusing existing output: $OUTPUT" >&2; exit 1; }
printf '%s  %s\n' e6b01c6350f64bdcf0cbdea2288b4698d06284711cbfe65f109c0efd461bbfe4 "$APK" | sha256sum -c -
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
WORK=$(mktemp -d /tmp/rezoss-native-nudge-candidate.XXXXXX)
echo "Candidate workspace: $WORK"
cp -a "$DECODED/smali_classes14" "$DECODED/smali_classes15" "$DECODED/smali_classes16" "$WORK/"
patch --dry-run --fuzz=0 -p1 -d "$WORK" < "$ROOT/0001-Opt-in-original-Samsung-reply-route.patch"
patch --fuzz=0 -p1 -d "$WORK" < "$ROOT/0001-Opt-in-original-Samsung-reply-route.patch"
patch --dry-run --fuzz=0 -p1 -d "$WORK" < "$ROOT/0002-Opt-in-notification-text-replies-and-timeout.patch"
patch --fuzz=0 -p1 -d "$WORK" < "$ROOT/0002-Opt-in-notification-text-replies-and-timeout.patch"
patch --dry-run --fuzz=0 -p1 -d "$WORK" < "$ROOT/0003-Deliver-generated-notification-reply-actions.patch"
patch --fuzz=0 -p1 -d "$WORK" < "$ROOT/0003-Deliver-generated-notification-reply-actions.patch"
patch --dry-run --fuzz=0 -p1 -d "$WORK" < "$ROOT/0004-Allow-native-reply-to-complete-before-release.patch"
patch --fuzz=0 -p1 -d "$WORK" < "$ROOT/0004-Allow-native-reply-to-complete-before-release.patch"
patch --dry-run --fuzz=0 -p1 -d "$WORK" < "$ROOT/0005-Opt-in-photo-and-calendar-parameters.patch"
patch --fuzz=0 -p1 -d "$WORK" < "$ROOT/0005-Opt-in-photo-and-calendar-parameters.patch"
patch --dry-run --fuzz=0 -p1 -d "$WORK" < "$ROOT/0006-Opt-in-WhatsApp-keyboard-recall-actions.patch"
patch --fuzz=0 -p1 -d "$WORK" < "$ROOT/0006-Opt-in-WhatsApp-keyboard-recall-actions.patch"
# Preserve original DEX 039. API 35 emits DEX 041 and broke runtime loading
# when mixed with the original independent DEX 039 files on this ROM.
for INDEX in 14 15 16; do
    java -Xmx2g -cp "$SRC_DIR/out/tools/bin/apktool.jar" com.android.tools.smali.smali.Main \
        assemble --api 28 -j 4 "$WORK/smali_classes$INDEX" -o "$WORK/classes$INDEX.dex"
done
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
TOOLS=$SDK/build-tools/37.0.0
mkdir -p "$WORK/client-classes" "$WORK/client-dex"
javac -source 8 -target 8 -Xlint:-options -classpath "$SDK/platforms/android-37.0/android.jar" -d "$WORK/client-classes" "$ROOT"/client-java/io/mesalabs/rezoss/ai/*.java
jar cf "$WORK/client.jar" -C "$WORK/client-classes" .
"$TOOLS/d8" --min-api 28 --lib "$SDK/platforms/android-37.0/android.jar" --output "$WORK/client-dex" "$WORK/client.jar"
cp "$WORK/client-dex/classes.dex" "$WORK/classes17.dex"
python3 - "$APK" "$WORK" <<'PY'
import hashlib, pathlib, struct, sys, zipfile, zlib
apk, work = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
replacements = {f'classes{i}.dex': (work / f'classes{i}.dex').read_bytes() for i in (14, 15, 16, 17)}
for name, data in replacements.items():
    assert data[:4] == b'dex\n' and len(data) == struct.unpack_from('<I', data, 32)[0], name
    assert hashlib.sha1(data[32:]).digest() == data[12:32], name
    assert zlib.adler32(data[12:]) == struct.unpack_from('<I', data, 8)[0], name
with zipfile.ZipFile(apk) as src, zipfile.ZipFile(work / 'unsigned.apk', 'x') as dst:
    assert 'classes17.dex' not in src.namelist(), 'Unexpected existing helper DEX'
    for item in src.infolist():
        if item.filename.startswith('META-INF/') and item.filename.upper().endswith(('.RSA', '.DSA', '.EC', '.SF', 'MANIFEST.MF')):
            continue
        dst.writestr(item, replacements.get(item.filename, src.read(item)))
    dst.writestr('classes17.dex', replacements['classes17.dex'])
PY
TOOLS=${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/37.0.0
"$TOOLS/zipalign" -P 16 -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
CERT=aosp
if ${ROM_IS_OFFICIAL:-false}; then CERT=unica; fi
"$TOOLS/apksigner" sign --key "$SRC_DIR/security/${CERT}_platform.pk8" \
    --cert "$SRC_DIR/security/${CERT}_platform.x509.pem" --out "$OUTPUT" "$WORK/aligned.apk"
"$TOOLS/apksigner" verify --verbose "$OUTPUT"
"$TOOLS/zipalign" -c -P 16 4 "$OUTPUT"
unzip -tq "$OUTPUT"
sha256sum "$OUTPUT"
echo 'Offline candidate only; native route remains disabled by default.'
