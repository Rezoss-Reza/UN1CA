#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Package existing validated ARM64 tools; never overwrite an existing ZIP.
set -euo pipefail
DFE_REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
DFE_ZIP_OUTPUT="${1:?Usage: build-standalone.sh OUTPUT_ZIP}"
python3 - "$DFE_REPO_ROOT" "$DFE_ZIP_OUTPUT" <<'PY'
import hashlib
from pathlib import Path
import sys
import zipfile
root, output = Path(sys.argv[1]), Path(sys.argv[2])
payload = root / 'target/dm3q/installer/root'
files = {
    'META-INF/com/google/android/update-binary': root / 'scripts/dfe/standalone-update-binary.sh',
    'unica/dfe/dfe-maintain.sh': payload / 'unica/dfe/dfe-maintain.sh',
}
for tool in ('busybox', 'dfe-mapper', 'extract.erofs64', 'mkfs.erofs', 'lpdump'):
    files['unica/dfe/tools/' + tool] = payload / 'unica/dfe/tools' / tool
for path in files.values():
    if not path.is_file():
        raise SystemExit(f'Missing payload: {path}')
instructions = '''Standalone vendor-only DFE for Samsung dm3q / Galaxy S23 Ultra

For other Samsung ROMs on dm3q with one unsuffixed EROFS vendor inside super.
Not a universal Android DFE ZIP. Verified-boot enforcement, encrypted/ambiguous
existing data, unfamiliar layouts, dependent mappings, and oversized rebuilt
vendor images are not supported. No partition resizing or super rebuild.

Install ROM and kernel first, reboot recovery, then flash this ZIP last.
Data must be mountable and already unencrypted or freshly formatted.
This ZIP never formats or decrypts existing encrypted data.
After success, reboot recovery before additional ZIPs or mounting vendor.
If vendor restore fails, reflash the ROM before booting Android.
Keep a backup: interruption during the vendor write can prevent boot.

Failure log: /tmp/unica-dfe.log (pull it before rebooting recovery).
Failure workspace/original vendor: /data/local/tmp/dfework (preserved).
Preserve or move a failed workspace before retrying; it is never overwritten.

Optional read-only preflight from a recovery shell:
unzip -p ZIP_PATH META-INF/com/google/android/update-binary > /tmp/dfe-check.sh
sh /tmp/dfe-check.sh 3 '' ZIP_PATH --check
This does not apply DFE or prove successful repacking/boot.

The ZIP is unsigned. Disable ZIP signature verification if recovery requires it.
The included checksums verify file integrity, not publisher authenticity.
'''
output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(output, 'x', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    checksums = []
    for name, path in files.items():
        data = path.read_bytes()
        info = zipfile.ZipInfo(name)
        info.create_system = 3
        info.external_attr = (0o100755 << 16)
        info.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(info, data)
        checksums.append(hashlib.sha256(data).hexdigest() + '  ' + name)
    z.writestr('META-INF/com/google/android/updater-script', '# Standalone shell installer; update-binary performs all work.\n')
    z.writestr('INSTRUCTIONS.txt', instructions)
    z.writestr('SHA256SUMS', '\n'.join(checksums) + '\n')
with zipfile.ZipFile(output) as z:
    if z.testzip() is not None:
        raise SystemExit('ZIP integrity failed')
    for name, path in files.items():
        assert z.read(name) == path.read_bytes(), name
sha = hashlib.sha256(output.read_bytes()).hexdigest()
output.with_suffix(output.suffix + '.sha256').write_text(sha + '  ' + output.name + '\n')
print(output.resolve())
print(f'{output.stat().st_size} bytes; SHA-256 {sha}')
PY
