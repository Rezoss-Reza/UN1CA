#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Only additive GPU classes; manifest/resources use the Settings overlay."""
from pathlib import Path
import datetime
import shutil
import sys

output = Path(__file__).resolve().parent.parent / 'smali/system/priv-app/SecSettings/SecSettings.apk/0005-Add-Advanced-GPU-Tuning.patch'
parts = ['From 0000000000000000000000000000000000000000 Mon Sep 17 00:00:00 2001\n'
         'From: UN1CA <unica@local>\nSubject: [PATCH] Add native Advanced GPU Tuning trial\n\n'
         'Generated from settings/gpu/java by gpu/build.sh.\n\n']
base = Path(sys.argv[1])
for source in sorted(base.rglob('*.smali')):
    if 'io/mesalabs/unica/gpu/' not in source.as_posix():
        continue
    path = 'smali_classes4/' + source.relative_to(base).as_posix()
    lines = source.read_text().splitlines(keepends=True)
    parts.append(f'diff --git a/{path} b/{path}\nnew file mode 100644\n--- /dev/null\n+++ b/{path}\n@@ -0,0 +1,{len(lines)} @@\n')
    parts.extend('+' + line for line in lines)
    parts.append('\n')
content = ''.join(parts)
if output.exists() and output.read_text() != content:
    shutil.copy2(output, str(output) + '.bak.' + datetime.datetime.now().strftime('%Y%m%d_%H%M%S_%f'))
output.write_text(content)
print(output)
