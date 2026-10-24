#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate new Settings classes as a patch; never ship compile-only stubs."""
from datetime import datetime
from pathlib import Path
import shutil
import sys

here = Path(__file__).resolve().parent
source = Path(sys.argv[1])
output = (here.parent / 'settings/smali/system/priv-app/SecSettings/SecSettings.apk'
          / '0009-Add-PortROM-audio-toggle.patch')
content = ('From 0000000000000000000000000000000000000000 Mon Sep 17 00:00:00 2001\n'
           'From: UN1CA <unica@local>\nSubject: [PATCH] Add reboot-selected PortROM audio switch\n\n'
           'Generated from portrom-audio/java by portrom-audio/build.sh.\n\n')
files = sorted((source / 'io/mesalabs/unica/portrom').glob('*.smali'))
if not files:
    raise SystemExit('Compiled PortROM Settings controller missing')
for file in files:
    name = 'smali_classes4/' + file.relative_to(source).as_posix()
    lines = file.read_text().splitlines(keepends=True)
    content += (f'diff --git a/{name} b/{name}\nnew file mode 100644\n'
                f'--- /dev/null\n+++ b/{name}\n@@ -0,0 +1,{len(lines)} @@\n')
    content += ''.join('+' + line for line in lines) + '\n'
output.parent.mkdir(parents=True, exist_ok=True)
if output.exists() and output.read_text() != content:
    shutil.copy2(output, str(output) + '.bak.' + datetime.now().strftime('%Y%m%d_%H%M%S_%f'))
output.write_text(content)
print(output)
