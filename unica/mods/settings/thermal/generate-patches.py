#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate patch-only smali integration; compile-only stubs are never shipped."""
from pathlib import Path
import datetime
import difflib
import shutil
import sys

here = Path(__file__).resolve().parent
smali, base = map(Path, sys.argv[1:])

def header(subject):
    return ('From 0000000000000000000000000000000000000000 Mon Sep 17 00:00:00 2001\n'
            'From: UN1CA <unica@local>\nSubject: [PATCH] ' + subject + '\n\n'
            'Generated from settings/thermal/java by thermal/build.sh.\n\n')

def new_classes(prefix, dex):
    result = ''
    files = sorted((smali / 'io/mesalabs/unica/thermal').glob(prefix + '*.smali'))
    if not files:
        raise SystemExit('Missing compiled classes: ' + prefix)
    for source in files:
        name = dex + '/' + source.relative_to(smali).as_posix()
        lines = source.read_text().splitlines(keepends=True)
        result += (f'diff --git a/{name} b/{name}\nnew file mode 100644\n'
                   f'--- /dev/null\n+++ b/{name}\n@@ -0,0 +1,{len(lines)} @@\n')
        result += ''.join('+' + line for line in lines) + '\n'
    return result

def diff(name, old, new):
    return (f'diff --git a/{name} b/{name}\n' + ''.join(difflib.unified_diff(
        old.splitlines(keepends=True), new.splitlines(keepends=True),
        fromfile='a/' + name, tofile='b/' + name, n=3)))

def write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and path.read_text() != content:
        shutil.copy2(path, str(path) + '.bak.' + datetime.datetime.now().strftime('%Y%m%d_%H%M%S_%f'))
    path.write_text(content)
    print(path)

write(here.parent / 'smali/system/priv-app/SecSettings/SecSettings.apk/0006-Add-Relax-SDHMS-toggle.patch',
      header('Add acknowledged Relax SDHMS switch and heat warning')
      + new_classes('RelaxSdhmsPreferenceController', 'smali_classes4'))

manifest = (base / 'AndroidManifest.xml').read_text()
if 'android:persistent="true"' not in manifest or 'android:process="com.sec.android.sdhms"' not in manifest:
    raise SystemExit('SDHMS persistent process contract changed')
receiver = ('        <receiver android:name="io.mesalabs.unica.thermal.RelaxPolicyReceiver" '
            'android:exported="true" android:permission="android.permission.WRITE_SECURE_SETTINGS"/>\n')
new_manifest = manifest.replace('    </application>', receiver + '    </application>')
name = 'smali/com/sec/android/sdhms/settings/k.smali'
old = (base / name).read_text()
anchor = '    :goto_3\n    if-eqz p2, :cond_6\n'
if old.count(anchor) != 1:
    raise SystemExit('SDHMS policy completion hook changed')
new = old.replace(anchor, '    :goto_3\n'
                  '    invoke-static {p2}, Lio/mesalabs/unica/thermal/RelaxPolicyReceiver;->policyParsed(Ljava/lang/String;)V\n\n'
                  '    if-eqz p2, :cond_6\n')
write(here / 'patches/0001-Add-protected-SDHMS-policy-switch.patch',
      header('Add protected marker control and report policy initialization')
      + new_classes('RelaxPolicyReceiver', 'smali')
      + diff('AndroidManifest.xml', manifest, new_manifest) + diff(name, old, new))
