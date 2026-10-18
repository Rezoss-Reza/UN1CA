#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Apply the policy installer to temporary copies, then check idempotence."""
from pathlib import Path
import shutil
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

root, temp = map(Path, sys.argv[1:])
source = root / 'out/target/dm3q/work_dir'
work = temp / 'policy'
for partition in ('system/system', 'vendor', 'system_ext', 'product', 'odm'):
    src = source / partition / 'etc/selinux'
    if src.is_dir():
        shutil.copytree(src, work / partition / 'etc/selinux')
installer = root / 'unica/mods/settings/gpu/install-policy.py'
subprocess.run([sys.executable, str(installer), str(work)], check=True)
first = {p.relative_to(work): p.read_bytes() for p in work.rglob('*') if p.is_file()}
subprocess.run([sys.executable, str(installer), str(work)], check=True)
assert first == {p.relative_to(work): p.read_bytes() for p in work.rglob('*') if p.is_file()}, 'Policy installer not idempotent'
policy = (work / 'system/system/etc/selinux/plat_sepolicy.cil').read_text()
assert '(allow unica_gpu unica_gpu_block (blk_file (read write open getattr ioctl)))' in policy
assert '(allow unica_gpu unica_gpu_vbmeta (blk_file (read open getattr)))' in policy
assert '(allow unica_gpu unica_gpu_backup (dir (create search read open getattr write add_name remove_name rmdir)))' in policy
assert '(allow unica_gpu unica_gpu_backup (file (create read write open getattr setattr rename unlink lock link)))' in policy
assert '(allow unica_gpu unica_gpu_archive (dir (create search read open getattr write add_name remove_name)))' in policy
assert '(allow unica_gpu unica_gpu_archive (file (create read write open getattr setattr rename unlink)))' in policy
assert '(allow system_app unica_gpu_archive ' not in policy
assert '/data/misc/unica_gpu/archives(/.*)? u:object_r:unica_gpu_archive:s0' in (work / 'system/system/etc/selinux/plat_file_contexts').read_text()
assert 'mkdir /data/misc/unica_gpu/archives 0750 root root' in (root / 'unica/mods/settings/system/etc/init/unica_gpu.rc').read_text()
assert '(allow unica_gpu emmcblk_device ' not in policy
assert '(allow unica_gpu block_device (blk_file ' not in policy
assert '(allow system_app unica_gpu_block ' not in policy
assert '(allow unica_gpu self (capability ' not in policy

ns = '{http://schemas.android.com/apk/res/android}'
xml = ET.parse(root / 'unica/mods/settings/SecSettings.apk/res/xml/unica_top_settings.xml').getroot()
keys = [node.attrib.get(ns + 'key') for node in xml]
# Scheduled power-on now sits in this group as well; preserve its placement.
assert keys.index('unica_battery') < keys.index('unica_gpu')
assert all(k is not None for k in keys[keys.index('unica_battery'):keys.index('unica_gpu') + 1])
assert '<string name="unica_gpu_title">Advanced GPU Tuning (Konabess)</string>' in (root / 'unica/mods/settings/SecSettings.apk/res/values/unica_gpu.xml').read_text()
assert 'system/bin/unica-gpu 0 0 755 capabilities=0x0' in (root / 'unica/mods/settings/fs_config-system').read_text()
assert '/system/bin/unica-gpu u:object_r:unica_gpu_exec:s0' in (root / 'unica/mods/settings/file_context-system').read_text()
assert 'system/etc/unica/gpu/restore 0 0 755 capabilities=0x0' in (root / 'unica/mods/settings/fs_config-system').read_text()
print('PASS policy idempotence, specific partition access and exact Settings menu placement')

# Use the repository-compatible compiler used for existing OTA policy checks.
compiler = Path(os.environ.get('SECILC') or shutil.which('secilc') or '/tmp/unica-ota-selinux-build/secilc-check')
if not compiler.is_file():
    raise SystemExit('Set SECILC to a compatible compiler before completing policy validation')
plat = work / 'system/system/etc/selinux'
version = (work / 'vendor/etc/selinux/plat_sepolicy_vers.txt').read_text().strip()
inputs = [plat / 'plat_sepolicy.cil', plat / 'mapping' / (version + '.cil'),
          work / 'system_ext/etc/selinux/system_ext_sepolicy.cil',
          work / 'system_ext/etc/selinux/mapping' / (version + '.cil'),
          work / 'product/etc/selinux/product_sepolicy.cil',
          work / 'product/etc/selinux/mapping' / (version + '.cil'),
          work / 'vendor/etc/selinux/plat_pub_versioned.cil',
          work / 'vendor/etc/selinux/vendor_sepolicy.cil']
odm = work / 'odm/etc/selinux/odm_sepolicy.cil'
if odm.exists():
    inputs.append(odm)
subprocess.run([str(compiler), '-m', '-M', 'true', '-G', '-N', '-c', '30',
                '-o', str(temp / 'policy.bin'), '-f', str(temp / 'file_contexts.bin'),
                *map(str, inputs)], check=True)
print('PASS combined Android runtime SELinux compilation (-N, like init)')
