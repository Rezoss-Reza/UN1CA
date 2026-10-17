#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
import importlib.util
from pathlib import Path
import tempfile
import sys

sys.dont_write_bytecode = True

root = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('policy', root / 'install-policy.py')
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)
with tempfile.TemporaryDirectory(prefix='rezoss-poweron-policy-', dir='/tmp') as temp:
    work = Path(temp)
    plat = work / 'system/system/etc/selinux'
    plat.mkdir(parents=True)
    vendor = work / 'vendor/etc/selinux/vendor_sepolicy.cil'
    vendor.parent.mkdir(parents=True)
    (plat / 'plat_sepolicy.cil').write_text('; existing platform policy\n')
    identifier = plat / 'plat_sepolicy_and_mapping.sha256'
    identifier.write_text('old\n')
    original = '(type alarm_device)\n; system_app_33_0 rtc_device_33_0\n'
    vendor.write_text(original)
    policy.install(work)
    first, digest = vendor.read_bytes(), identifier.read_bytes()
    assert first.startswith(original.rstrip().encode())
    assert digest != b'old\n'
    policy.install(work)
    assert first == vendor.read_bytes() and digest == identifier.read_bytes()
    assert b'(0x6107)' in first and b'(0x7009)' in first
    assert b'0x700a' not in first and b'0x700f' not in first
    vendor.write_text('(type unsupported)\n')
    try:
        policy.install(work)
        raise AssertionError('Unsupported policy was accepted')
    except ValueError:
        pass
    assert vendor.read_text() == '(type unsupported)\n'
print('PASS: policy idempotence, identifier refresh, bounded commands, unsupported-policy rejection')
