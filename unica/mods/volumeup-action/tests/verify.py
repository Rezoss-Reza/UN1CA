#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Validate against copies of the exact ROM policy, leaving out/target untouched."""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile

mod = Path(__file__).resolve().parents[1]
source = Path(sys.argv[1]).resolve()
with tempfile.TemporaryDirectory(prefix='unica-volumeup-test-', dir='/tmp') as tmp:
    temp = Path(tmp)
    subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror',
                    str(mod / 'tests/hold_test.cpp'), '-o', str(temp / 'hold-test')], check=True)
    subprocess.run([str(temp / 'hold-test')], check=True)
    print('PASS hold threshold, release/reset, and initially held key', flush=True)
    for part in ('system/system', 'system_ext', 'product', 'vendor', 'odm'):
        folder = source / part / 'etc/selinux'
        if folder.is_dir():
            shutil.copytree(folder, temp / part / 'etc/selinux')
    installer = [sys.executable, str(mod / 'install-policy.py'), str(temp)]
    subprocess.run(installer, check=True)
    snapshot = {p: p.read_bytes() for p in temp.rglob('*') if p.is_file()}
    subprocess.run(installer, check=True)
    assert all(p.read_bytes() == data for p, data in snapshot.items())
    print('PASS policy installer idempotence', flush=True)
    policy_text = (temp / 'system/system/etc/selinux/plat_sepolicy.cil').read_text()
    assert '(allow unica_volumeup unica_volumeup_request_prop (property_service (set)))' in policy_text
    assert '(allow unica_volumeup powerctl_prop' not in policy_text
    assert '(allow unica_volumeup unica_volumeup (capability (sys_boot)))' not in policy_text
    contexts = (temp / 'system/system/etc/selinux/plat_property_contexts').read_text()
    assert 'sys.unica.volumeup.recovery u:object_r:unica_volumeup_request_prop:s0 exact bool' in contexts
    rc = (mod / 'payload/system/etc/init/unica_volumeup.rc').read_text()
    assert 'on property:sys.unica.volumeup.recovery=1\n    setprop sys.powerctl reboot,recovery' in rc
    print('PASS fixed recovery request wiring and restricted policy', flush=True)
    version = (temp / 'vendor/etc/selinux/plat_sepolicy_vers.txt').read_text().strip()
    paths = [temp / 'system/system/etc/selinux/plat_sepolicy.cil',
             temp / f'system/system/etc/selinux/mapping/{version}.cil']
    for part in ('system_ext', 'product'):
        paths += [temp / f'{part}/etc/selinux/{part}_sepolicy.cil',
                  temp / f'{part}/etc/selinux/mapping/{version}.cil']
    paths += [temp / 'vendor/etc/selinux/plat_pub_versioned.cil',
              temp / 'vendor/etc/selinux/vendor_sepolicy.cil']
    odm = temp / 'odm/etc/selinux/odm_sepolicy.cil'
    if odm.is_file(): paths.append(odm)
    compiler = os.environ.get('SECILC') or shutil.which('secilc')
    if not compiler:
        raise SystemExit('Set SECILC to an Android-compatible secilc compiler')
    subprocess.run([compiler, '-m', '-M', 'true', '-G', '-N', '-c', '30',
                    '-o', str(temp / 'policy.bin'), '-f', str(temp / 'file_contexts'),
                    *map(str, paths)], check=True)
    print('PASS combined ROM policy compilation (runtime settings; neverallow disabled)', flush=True)
