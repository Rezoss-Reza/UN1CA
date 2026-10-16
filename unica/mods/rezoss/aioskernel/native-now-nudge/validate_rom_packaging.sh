#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Exercise real packaging helpers in /tmp; never touch out/target or a phone.
set -e
: "${SRC_DIR:?Source buildenv.sh dm3q first}"
cd "$SRC_DIR"
source scripts/utils/common_utils.sh
source scripts/utils/build_utils.sh
export WORK_DIR=$(mktemp -d /tmp/nudge-rom-package.XXXXXX)
export TMP_DIR=$WORK_DIR/tmp
MODPATH=$SRC_DIR/unica/mods/rezoss
mkdir -p "$WORK_DIR/system/system" "$WORK_DIR/configs" "$TMP_DIR"
mkdir -p "$WORK_DIR/system/system/etc/selinux"
cp "$FW_DIR/SM-S918B_EUX/system/system/etc/selinux/plat_file_contexts" "$WORK_DIR/system/system/etc/selinux/plat_file_contexts"
touch "$WORK_DIR/system/system/build.prop" "$WORK_DIR/configs/fs_config-system" "$WORK_DIR/configs/file_context-system"
source "$MODPATH/aioskernel/native-now-nudge/config.sh"
[[ $UNICA_ENABLE_NATIVE_NOW_NUDGE == true ]]
source "$MODPATH/aioskernel/native-now-nudge/integrate_rom.sh"
python3 - "$WORK_DIR" <<'PY'
from pathlib import Path
import sys,hashlib,zipfile
w=Path(sys.argv[1]);s=w/'system/system';fs=(w/'configs/fs_config-system').read_text();fc=(w/'configs/file_context-system').read_text()
a=s/'priv-app/AIOSKernelService/AIOSKernelService.apk'
with zipfile.ZipFile(a) as z:
 for n in z.namelist():
  if n.endswith('.dex'): assert z.read(n)[:8] in [b'dex\n039\0',b'dex\n035\0'],n
 for n in z.namelist():
  if n.startswith('lib/arm64-v8a/') and n.endswith('.so'):
   assert z.read(n)==(s/'priv-app/AIOSKernelService/lib/arm64'/Path(n).name).read_bytes()
assert 'system/priv-app/AIOSKernelService/lib/arm64/libnudge_runner.so 0 0 755' in fs
assert 'libnudge_runner\\.so u:object_r:system_file:s0' in fc
assert not (s/'priv-app/OfflineLanguageModel').exists()
assert 'ro.unica.now_nudge_v73=1' in (s/'build.prop').read_text()
for name,expected in [('hybrid_llama_qnn.pte','59e727d3174d84cd198a9707c2e52e9866e1c9fe0db63039474f32c71cdb009e'),('tokenizer.json','aeb13307a71acd8fe81861d94ad54ab689df773318809eed3cbe794b4492dae4')]:
 assert hashlib.file_digest((s/'etc/unica/now-nudge-v73'/name).open('rb'),'sha256').hexdigest()==expected
print('ROM packaging fixture passed:',w)
PY
