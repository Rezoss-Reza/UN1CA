#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Offline asset and real-current-output merge/rollback-contract verification."""
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import sys
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parents[1]
ROOT = HERE.parents[2]
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('portrom_prepare', HERE / 'prepare.py')
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inventory(path):
    return {p.relative_to(path).as_posix(): digest(p) for p in path.rglob('*') if p.is_file()}


manifest = json.loads((HERE / 'assets/manifest.json').read_text())
assert Counter(r['kind'] for r in manifest['files']) == {
    'replacement': 194, 'addition': 102, 'boot': 2}
assets = {r['path']: r['sha256'] for r in manifest['files']}
assert inventory(HERE / 'assets/audio') == assets
work = ROOT / 'out/target/dm3q/work_dir'
original = work / 'system/system/media/audio'
before = inventory(original)
stage = Path(tempfile.mkdtemp(prefix='unica-portrom-test-', dir='/tmp'))
prepare.prepare(work, stage)
merged = stage / 'system/system/etc/unica/portrom-audio/audio'
result = inventory(merged)
expected = dict(before)
expected.update(assets)
expected[prepare.MARKER] = hashlib.sha256(b'PortROM V1.0.2\n').hexdigest()
assert result == expected, 'Missing, changed, or unexpected merged files'
assert inventory(original) == before, 'Original ROM audio was modified'
assert prepare.MARKER not in before
assert (merged / 'ui/PowerOn.ogg').read_bytes() != (original / 'ui/PowerOn.ogg').read_bytes()
assert (merged / 'ui/PowerOff.ogg').is_file()
try:
    prepare.prepare(work, stage)
    raise AssertionError('Existing staging output should be rejected')
except ValueError:
    pass

# Placement is checked against the real existing Settings layout.
xml = ROOT / 'unica/mods/settings/SecSettings.apk/res/xml/unica_ui_settings.xml'
android = '{http://schemas.android.com/apk/res/android}'
rows = list(ET.parse(xml).getroot())
keys = [row.get(android + 'key') for row in rows]
assert keys.index('unica_portrom_audio') == keys.index('unica_boot_sound') + 1
resources = ET.parse(ROOT / 'unica/mods/settings/SecSettings.apk/res/values/unica_portrom_audio.xml')
assert len(resources.findall('string')) == 6
rc = (HERE / 'system/etc/init/unica_portrom_audio.rc').read_text()
assert '\non property:' not in rc, 'A toggle must not mount live'
assert '\non load_persist_props_action && property:persist.sys.unica.portrom_audio=true\n' in rc
assert 'mount none /system/etc/unica/portrom-audio/audio /system/media/audio bind' in rc
assert not (HERE / 'system.prop').exists(), 'Default should remain OFF'
print(f'PASS: 298 asset hashes; merged {len(before)} original files without input changes;')
print('      power mapping, existing-output refusal, Settings placement, boot-only mount.')
print('Staging retained at', stage)
