#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
from pathlib import Path
import importlib.util
import sys
import tempfile
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
root = Path(__file__).resolve().parents[1]
settings = root.parents[1] / 'settings/SecSettings.apk'
android = '{http://schemas.android.com/apk/res/android}'
menu = settings / 'res/xml/unica_top_settings.xml'
tree = ET.parse(menu)
entries = list(tree.getroot())
keys = [e.get(android + 'key') for e in entries]
position = keys.index('unica_power_schedule')
assert keys[position-1] == 'unica_battery' and keys[position+1] == 'unica_gpu'
intent = entries[position].find('intent')
assert intent.get(android+'targetPackage') == 'io.mesalabs.rezoss.poweron'
assert intent.get(android+'targetClass') == 'io.mesalabs.rezoss.poweron.PowerOnActivity'
resources = ET.parse(settings / 'res/values/unica_power_schedule.xml').getroot()
names = {e.get('name') for e in resources}
assert {'unica_power_schedule_title','unica_power_schedule_summary'} <= names
manifest = ET.parse(root / 'AndroidManifest.xml').getroot()
permissions = {e.get(android+'name') for e in manifest.findall('uses-permission')}
assert {'android.permission.REBOOT','android.permission.USE_EXACT_ALARM',
        'android.permission.RECEIVE_BOOT_COMPLETED','android.permission.WAKE_LOCK'} <= permissions
activity = manifest.find('application/activity')
assert activity.get(android+'permission') == 'android.permission.REBOOT'
assert not manifest.findall('.//category[@'+android+'name="android.intent.category.LAUNCHER"]')
receiver = manifest.find('application/receiver')
assert receiver.get(android+'exported') == 'false' and receiver.get(android+'directBootAware') == 'true'
spec = importlib.util.spec_from_file_location('menu_installer', root / 'install-settings-menu.py')
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)
with tempfile.TemporaryDirectory(prefix='rezoss-power-menu-',dir='/tmp') as temp:
    path = Path(temp) / 'menu.xml'
    path.write_bytes(menu.read_bytes())
    installer.configure(path, True)
    assert path.read_bytes() == menu.read_bytes()
    installer.configure(path, False)
    assert 'unica_power_schedule' not in path.read_text()
    remaining = [e.get(android+'key') for e in ET.parse(path).getroot()]
    assert remaining == keys[:position] + keys[position+1:]
    installer.configure(path, False)
print('PASS: Settings placement, resources, protected component, hidden launcher, disabled-mod menu')
