#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Optional signed V73 asset package. Contains no CPU engine or inference service.
set -euo pipefail
: "${SRC_DIR:?Source buildenv.sh dm3q first}"
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
OUTPUT=${1:-$SRC_DIR/out/archived/QWEN_LLM/QWEN_LLM_rezoss.apk}
PAYLOAD=$ROOT/../../aioskernel/native-now-nudge/payload
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}; TOOLS=$SDK/build-tools/37.0.0
WORK=$(mktemp -d /tmp/rezoss-v73-modelpack.XXXXXX)
echo "Model package workspace: $WORK"
python3 "$ROOT/../../aioskernel/native-now-nudge/verify_payload.py" "$PAYLOAD"
mkdir -p "$WORK/classes" "$WORK/dex"
javac -source 8 -target 8 -Xlint:-options -classpath "$SDK/platforms/android-37.0/android.jar" \
    -d "$WORK/classes" "$ROOT"/java/io/mesalabs/rezoss/qwen/*.java
jar cf "$WORK/classes.jar" -C "$WORK/classes" .
"$TOOLS/d8" --min-api 30 --lib "$SDK/platforms/android-37.0/android.jar" --output "$WORK/dex" "$WORK/classes.jar"
"$TOOLS/aapt2" link --manifest "$ROOT/AndroidManifest.xml" -I "$SDK/platforms/android-37.0/android.jar" -o "$WORK/base.apk"
python3 - "$WORK" "$PAYLOAD" <<'PY'
from pathlib import Path
import json, shutil, sys, zipfile
work, payload = map(Path, sys.argv[1:])
contents = json.loads((payload/'CONTENTS.json').read_text())
prefix = 'system/etc/unica/now-nudge-v73/'
info = {'contract':'qwen3-0.6b-sm8550-v73-1024-v1'}
with zipfile.ZipFile(work/'base.apk', 'a', compression=zipfile.ZIP_STORED, allowZip64=True) as z:
    z.write(work/'dex/classes.dex', 'classes.dex')
    for filename, key in [('hybrid_llama_qnn.pte','model'), ('tokenizer.json','tokenizer')]:
        source = payload/prefix/filename
        parts = [source] if source.is_file() else sorted(source.parent.glob(filename+'.[0-9][0-9]'))
        with z.open('assets/v73/'+filename, 'w') as dst:
            for part in parts:
                with part.open('rb') as src: shutil.copyfileobj(src, dst, 1024*1024)
        info[key+'_size'] = contents[prefix+filename]['size']
        info[key+'_sha256'] = contents[prefix+filename]['sha256']
    z.writestr('assets/v73/model-info.json', json.dumps(info, sort_keys=True))
PY
"$TOOLS/zipalign" -P 16 -f 4 "$WORK/base.apk" "$WORK/aligned.apk"
CERT=aosp
if ${ROM_IS_OFFICIAL:-false}; then CERT=unica; fi
"$TOOLS/apksigner" sign --key "$SRC_DIR/security/${CERT}_platform.pk8" --cert "$SRC_DIR/security/${CERT}_platform.x509.pem" \
    --out "$WORK/signed.apk" "$WORK/aligned.apk"
"$TOOLS/apksigner" verify --verbose "$WORK/signed.apk"
"$TOOLS/zipalign" -c -P 16 4 "$WORK/signed.apk"
unzip -tq "$WORK/signed.apk"
mkdir -p "$(dirname -- "$OUTPUT")"
if [[ -e "$OUTPUT" ]]; then
    BACKUP="$OUTPUT.bak.$(date +%Y%m%d_%H%M%S)"
    [[ ! -e "$BACKUP" ]] || { echo "Backup exists: $BACKUP" >&2; exit 1; }
    cp -a "$OUTPUT" "$BACKUP"
    [[ ! -e "$OUTPUT.version" ]] || cp -a "$OUTPUT.version" "$BACKUP.version"
fi
mv "$WORK/signed.apk" "$OUTPUT"
python3 - "$OUTPUT" "$PAYLOAD" <<'PY'
from pathlib import Path
import datetime, hashlib, json, sys
apk, payload = map(Path, sys.argv[1:])
contents = json.loads((payload/'CONTENTS.json').read_text())
model = contents['system/etc/unica/now-nudge-v73/hybrid_llama_qnn.pte']
fields = {'package':'io.mesalabs.rezoss.qwen.v73','versionCode':'5','versionName':'2.0-v73-modelpack',
          'apk':str(apk),'sizeBytes':str(apk.stat().st_size),'sha256':hashlib.file_digest(apk.open('rb'),'sha256').hexdigest(),
          'model':'Qwen3-0.6B-V73','modelSha256':model['sha256'],
          'contract':'qwen3-0.6b-sm8550-v73-1024-v1','builtAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
Path(str(apk)+'.version').write_text(''.join(f'{k}={v}\n' for k,v in fields.items()))
PY
sha256sum "$OUTPUT"
echo 'package=io.mesalabs.rezoss.qwen.v73 versionCode=5 model=Qwen3-0.6B-V73 assets-only'
