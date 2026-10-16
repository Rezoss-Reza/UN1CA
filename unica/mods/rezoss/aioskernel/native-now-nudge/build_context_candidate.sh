#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Offline incremental candidate from the exact installed timeout-fix APK.
set -euo pipefail
: "${SRC_DIR:?Source buildenv.sh dm3q first}"
APK=${1:?Usage: build_context_candidate.sh TIMEOUT_FIX_APK NEW_OUTPUT_APK}
OUTPUT=${2:?Provide a new output APK path}
[[ ! -e "$OUTPUT" ]] || { echo "Refusing existing output: $OUTPUT" >&2; exit 1; }
printf '%s  %s\n' 5b19ba585a73a7ee158524591c652d72018c0c5c9b8b88584c211914561a0540 "$APK" | sha256sum -c -
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
WORK=$(mktemp -d /tmp/nudge-context-candidate.XXXXXX)
echo "Candidate workspace: $WORK"
unzip -p "$APK" classes15.dex > "$WORK/original15.dex"
java -cp "$SRC_DIR/out/tools/bin/apktool.jar" com.android.tools.smali.baksmali.Main \
    disassemble --api 28 "$WORK/original15.dex" -o "$WORK/smali_classes15"
patch --dry-run --fuzz=0 -p1 -d "$WORK" < "$ROOT/0005-Opt-in-photo-and-calendar-parameters.patch"
patch --fuzz=0 -p1 -d "$WORK" < "$ROOT/0005-Opt-in-photo-and-calendar-parameters.patch"
patch --dry-run --fuzz=0 -p1 -d "$WORK" < "$ROOT/0006-Opt-in-WhatsApp-keyboard-recall-actions.patch"
patch --fuzz=0 -p1 -d "$WORK" < "$ROOT/0006-Opt-in-WhatsApp-keyboard-recall-actions.patch"
bash "$ROOT/build_client_helper.sh" "$WORK"
for INDEX in 15 17; do
    java -Xmx2g -cp "$SRC_DIR/out/tools/bin/apktool.jar" com.android.tools.smali.smali.Main \
        assemble --api 28 -j 4 "$WORK/smali_classes$INDEX" -o "$WORK/classes$INDEX.dex"
done
python3 - "$APK" "$WORK" <<'PY'
import hashlib, pathlib, struct, sys, zipfile, zlib
apk, work = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
replacements = {f'classes{i}.dex': (work / f'classes{i}.dex').read_bytes() for i in (15, 17)}
for name, data in replacements.items():
    assert data[:8] == b'dex\n039\0', name
    assert len(data) == struct.unpack_from('<I', data, 32)[0], name
    assert hashlib.sha1(data[32:]).digest() == data[12:32], name
    assert zlib.adler32(data[12:]) == struct.unpack_from('<I', data, 8)[0], name
with zipfile.ZipFile(apk) as src, zipfile.ZipFile(work / 'unsigned.apk', 'x') as dst:
    assert all(name in src.namelist() for name in replacements)
    for item in src.infolist():
        if item.filename.startswith('META-INF/') and item.filename.upper().endswith(('.RSA', '.DSA', '.EC', '.SF', 'MANIFEST.MF')):
            continue
        dst.writestr(item, replacements.get(item.filename, src.read(item)))
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
echo 'Offline candidate; context actions require explicit settings opt-in.'
