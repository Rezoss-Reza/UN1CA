#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate the additive Settings patch; never edit decoded firmware smali."""
from pathlib import Path
import datetime
import difflib
import shutil
import sys

root = Path(__file__).resolve().parent.parent
output = root / 'smali/system/priv-app/SecSettings/SecSettings.apk/0002-Add-eight-configurable-UN1CA-quick-tiles.patch'
sections = ['From 0000000000000000000000000000000000000000 Mon Sep 17 00:00:00 2001\n'
            'From: UN1CA <unica@local>\n'
            'Subject: [PATCH] Add eight configurable UN1CA quick tiles\n\n'
            'Generated from settings/quicktiles/java by build-patch.sh.\n'
            'Adds tile services and routes their long press through the existing Settings gateway.\n\n']

def addition(path, content):
    lines = content.splitlines(keepends=True)
    sections.append(f'diff --git a/{path} b/{path}\nnew file mode 100644\n--- /dev/null\n+++ b/{path}\n@@ -0,0 +1,{len(lines)} @@\n')
    sections.extend('+' + line for line in lines)
    sections.append('\n')

for source in sorted(Path(sys.argv[1]).rglob('*.smali')):
    addition('smali_classes4/' + source.relative_to(sys.argv[1]).as_posix(), source.read_text())

package = 'io.mesalabs.unica.quicktiles'
manifest = [f'        <activity android:name="{package}.ConfigActivity" android:exported="false" android:label="@string/unica_quick_tiles_title"/>\n',
            f'        <activity android:name="{package}.ShortcutActivity" android:exported="false" android:excludeFromRecents="true"/>\n']
for i in range(1, 9):
    manifest += [f'        <service android:name="{package}.QuickTile$Slot{i}" android:exported="true" android:permission="android.permission.BIND_QUICK_SETTINGS_TILE" android:label="UN1CA Tile {i}" android:icon="@drawable/unica_qt_app">\n',
                 '            <intent-filter>\n',
                 '                <action android:name="android.service.quicksettings.action.QS_TILE"/>\n',
                 '            </intent-filter>\n',
                 '        </service>\n']
prefix = (root / 'SecSettings.apk/AndroidManifest.xml').read_text().splitlines(keepends=True)[-3:]
old = prefix + ['    </application>\n', '</manifest>\n']
new = prefix + manifest + ['    </application>\n', '    <uses-permission android:name="android.permission.KILL_UID"/>\n', '    <uses-permission android:name="android.permission.ACCESS_SHORTCUTS"/>\n', '    <uses-permission android:name="com.android.alarm.permission.SET_ALARM"/>\n', '</manifest>\n']
sections.append('diff --git a/AndroidManifest.xml b/AndroidManifest.xml\n')
sections.extend(difflib.unified_diff(old, new, fromfile='a/AndroidManifest.xml', tofile='b/AndroidManifest.xml'))
gateway = 'smali_classes2/com/android/settings/qstile/QSTileLongPressGatewayActivity.smali'
old_gateway = """.method public final onCreate(Landroid/os/Bundle;)V
    .locals 6

    invoke-super {p0, p1}, Landroidx/fragment/app/FragmentActivity;->onCreate(Landroid/os/Bundle;)V

    invoke-virtual {p0}, Landroid/app/Activity;->getIntent()Landroid/content/Intent;

    move-result-object p1

    const-string v0, "android.intent.extra.COMPONENT_NAME"
"""
hook = """    invoke-static {p0}, Lio/mesalabs/unica/quicktiles/TileConfig;->routeLongPress(Landroid/app/Activity;)Z

    move-result v0

    if-eqz v0, :cond_unica_quick_tile_stock

    return-void

    :cond_unica_quick_tile_stock
"""
new_gateway = old_gateway.replace('    invoke-virtual {p0}', hook + '    invoke-virtual {p0}')
sections.append(f'diff --git a/{gateway} b/{gateway}\n')
sections.extend(difflib.unified_diff(old_gateway.splitlines(keepends=True), new_gateway.splitlines(keepends=True), fromfile='a/' + gateway, tofile='b/' + gateway, n=5))
content = ''.join(sections)
if output.exists() and output.read_text() != content:
    shutil.copy2(output, str(output) + '.bak.' + datetime.datetime.now().strftime('%Y%m%d_%H%M%S_%f'))
output.write_text(content)
print(output)
