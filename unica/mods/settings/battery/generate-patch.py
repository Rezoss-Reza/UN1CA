#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
from pathlib import Path
import datetime
import shutil
import sys
root = Path(__file__).resolve().parent.parent
output = root / 'smali/system/priv-app/SecSettings/SecSettings.apk/0003-Add-Battery-Saving-controls.patch'
parts = ['From 0000000000000000000000000000000000000000 Mon Sep 17 00:00:00 2001\n'
         'From: UN1CA <unica@local>\nSubject: [PATCH] Add reversible Battery Saving controls\n\n'
         'Generated from settings/battery/java by build.sh.\n\n']
for source in sorted(Path(sys.argv[1]).rglob('*.smali')):
    # Quick Tiles already supplies the shared D8 LambdaMethod annotation.
    if 'io/mesalabs/unica/battery/' not in source.as_posix():
        continue
    path = 'smali_classes4/' + source.relative_to(sys.argv[1]).as_posix()
    lines = source.read_text().splitlines(keepends=True)
    parts.append(f'diff --git a/{path} b/{path}\nnew file mode 100644\n--- /dev/null\n+++ b/{path}\n@@ -0,0 +1,{len(lines)} @@\n')
    parts.extend('+' + line for line in lines)
    parts.append('\n')
# Declare the signature permissions for DeviceConfig writes and reversible app
# disabling. Settings already holds REBOOT for the confirmed restart flow.
parts.append('diff --git a/AndroidManifest.xml b/AndroidManifest.xml\n'
             '--- a/AndroidManifest.xml\n+++ b/AndroidManifest.xml\n'
              '@@ -1,4 +1,6 @@\n'
             '     <uses-permission android:name="android.permission.KILL_UID"/>\n'
             '     <uses-permission android:name="android.permission.ACCESS_SHORTCUTS"/>\n'
             '     <uses-permission android:name="com.android.alarm.permission.SET_ALARM"/>\n'
             '+    <uses-permission android:name="android.permission.WRITE_DEVICE_CONFIG"/>\n'
             '+    <uses-permission android:name="android.permission.CHANGE_COMPONENT_ENABLED_STATE"/>\n'
             ' </manifest>\n')
content = ''.join(parts)
if output.exists() and output.read_text() != content:
    shutil.copy2(output, str(output) + '.bak.' + datetime.datetime.now().strftime('%Y%m%d_%H%M%S_%f'))
output.write_text(content)
print(output)
