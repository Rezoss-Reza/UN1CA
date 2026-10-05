#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Standalone device validation; never attaches to or modifies the camera provider."""
import argparse,json,shlex,subprocess
from pathlib import Path
ADB='/mnt/c/platform-tools/adb.exe'
def remote(cmd):
 r=subprocess.run([ADB,'shell','su -c '+shlex.quote(cmd)],capture_output=True,text=True)
 if r.returncode:raise RuntimeError(r.stdout+r.stderr)
 return r.stdout
p=argparse.ArgumentParser();p.add_argument('--build-dir',type=Path,required=True);a=p.parse_args()
for name in ('com.qti.node.eisv3.so','libunica_horizon_adapter.so','test_load_node','test_adapter'):
 subprocess.run([ADB,'push',str(a.build_dir/name),'/data/local/tmp/'+name],check=True,capture_output=True)
remote('chmod 755 /data/local/tmp/test_load_node /data/local/tmp/test_adapter')
# The test loader requires the exact fixed-layout trampolines from this patcher.
manifest=json.loads((a.build_dir/'com.qti.node.eisv3.json').read_text())
assert [r['stub'] for r in manifest['sites']]==[0x15c000+i*32 for i in range(6)]
libs=remote('ls /apex/com.android.vndk.v33/lib64').split()
config=remote('cat /linkerconfig/ld.config.txt')
for line in config.splitlines():
 if 'namespace.vndk.link.default.shared_libs = ' in line:libs+=line.split(' = ')[1].split(':')
# Original node imports, including vendor-side dependencies, from its manifest build.
import lief
libs+=list(lief.parse(str(a.build_dir/'com.qti.node.eisv3.so')).libraries)
print(remote('/data/local/tmp/test_load_node /data/local/tmp/com.qti.node.eisv3.so '+shlex.quote(':'.join(sorted(set(libs))))),end='')
for mode in (0,1,2):print(remote('/data/local/tmp/test_adapter '+str(mode)),end='')
