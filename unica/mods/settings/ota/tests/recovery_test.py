# SPDX-License-Identifier: GPL-3.0-or-later
"""Exercise real recovery hooks with isolated paths and mocked key input/reboot."""
from pathlib import Path
import tempfile,subprocess,os,re
ROOT=Path(__file__).resolve().parents[5]
original=(ROOT/'target/dm3q/installer/root/unica/ota/hooks.sh').read_text()
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp);(p/'system_root/system').mkdir(parents=True);(p/'data/misc/unica_ota/staged').mkdir(parents=True);(p/'cache/unica-ota').mkdir(parents=True);(p/'tmp/unica/ota').mkdir(parents=True)
 hooks=re.sub(r'(?<![A-Za-z0-9_/])/(?:system_root|system|data|cache|tmp)(?=/|\b)',lambda match:str(p)+match[0],original)
 (p/'hooks.sh').write_text(hooks)
 (p/'tmp/unica/ota/update.properties').write_text('device=dm3q\nbase_timestamp=100\ntarget_timestamp=200\n')
 def run(current,cancel):
  (p/'system_root/system/build.prop').write_text('ro.unica.timestamp='+current+'\n')
  helper=p/'tmp/unica/ota/cancel';helper.write_text('#!/bin/sh\nexit '+str(cancel)+'\n');helper.chmod(0o755)
  script='. "$HOOKS"; ui_print(){ echo "$@"; }; mount(){ echo "$1" >> "$MOUNT_TRACE"; }; umount(){ :; }; unzip(){ :; }; sync(){ :; }; ota_preflight'
  return subprocess.run(['bash','-c',script],env=dict(os.environ,HOOKS=str(p/'hooks.sh'),TMPDIR=str(p/'tmp'),ZIPFILE='fixture.zip',MOUNT_TRACE=str(p/'mount.trace')),capture_output=True,text=True)
 probe=run('100',0);assert probe.returncode==0,(probe.stdout,probe.stderr)
 assert (p/'mount.trace').read_text().splitlines()[0]==str(p/'system_root')
 missing=run('',0);assert missing.returncode!=0 and 'Cannot read installed ROM identity' in missing.stdout
 cancelled=run('100',2);assert cancelled.returncode!=0 and 'cancelled' in cancelled.stdout
 assert (p/'data/misc/unica_ota/staged/result').read_text().strip()=='Cancelled'
 assert run('100',3).returncode!=0
 wrong=run('99',0);assert wrong.returncode!=0 and 'full ROM' in wrong.stdout
 assert run('200',0).returncode==0 # retry after partially applied system
 def complete(status,auto):
  marker=p/'cache/unica-ota/auto';marker.unlink(missing_ok=True)
  if auto:marker.write_text('pending')
  script='. "$HOOKS"; ui_print(){ :; }; sync(){ :; }; reboot(){ echo REBOOT; }; ota_complete'
  return subprocess.run(['bash','-c',script],env=dict(os.environ,HOOKS=str(p/'hooks.sh'),STATUS=str(status),ZIPFILE=str(p/'data/misc/unica_ota/staged/update.zip')),capture_output=True,text=True)
 assert 'REBOOT' not in complete(1,True).stdout
 assert 'REBOOT' not in complete(0,False).stdout
 assert 'REBOOT' in complete(0,True).stdout
 print('PASS: base rejection, cancellation/input failure, partial retry, failure stays in recovery, success-only automatic reboot')
