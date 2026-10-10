#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
from pathlib import Path
import sys,shutil,datetime
root=Path(__file__).resolve().parent.parent
output=root/'smali/system/priv-app/SecSettings/SecSettings.apk/0004-Add-OTA-Updater.patch'
parts=['From 0000000000000000000000000000000000000000 Mon Sep 17 00:00:00 2001\nFrom: UN1CA <unica@local>\nSubject: [PATCH] Add signed OTA updater\n\nGenerated from settings/ota/java.\n\n']
for p in sorted(Path(sys.argv[1]).rglob('*.smali')):
 if 'io/mesalabs/unica/ota/' not in p.as_posix(): continue
 path='smali_classes4/'+p.relative_to(sys.argv[1]).as_posix(); lines=p.read_text().splitlines(keepends=True)
 parts.append(f'diff --git a/{path} b/{path}\nnew file mode 100644\n--- /dev/null\n+++ b/{path}\n@@ -0,0 +1,{len(lines)} @@\n');parts.extend('+'+l for l in lines);parts.append('\n')
content=''.join(parts)
if output.exists() and output.read_text()!=content:shutil.copy2(output,str(output)+'.bak.'+datetime.datetime.now().strftime('%Y%m%d_%H%M%S'))
output.write_text(content);print(output)
