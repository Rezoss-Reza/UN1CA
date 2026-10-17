#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Compile source firmware CIL in /tmp, with Android init's -N runtime flag.

Use --strict to also check neverallows. Samsung's shipped cross-version CIL
already has neverallow conflicts; compare baseline and modified diagnostics.
Requires the Android-capable secilc/libsepol supporting nlmsg permissionx.
"""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('firmware', type=Path)
parser.add_argument('secilc')
parser.add_argument('--strict', action='store_true')
parser.add_argument('--baseline', action='store_true')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
work = Path(tempfile.mkdtemp(prefix='rezoss-poweron-compile-', dir='/tmp'))
for relative in ('system/system/etc/selinux', 'system_ext/etc/selinux',
                 'product/etc/selinux', 'vendor/etc/selinux'):
    shutil.copytree(args.firmware / relative, work / relative)
if not args.baseline:
    subprocess.run(['python3', str(root / 'install-policy.py'), str(work)], check=True)
plat = work / 'system/system/etc/selinux'
version = (work / 'vendor/etc/selinux/plat_sepolicy_vers.txt').read_text().strip()
files = [plat / 'plat_sepolicy.cil', plat / f'mapping/{version}.cil']
for path in (plat / f'mapping/{version}.compat.cil',
             work / 'system_ext/etc/selinux/system_ext_sepolicy.cil',
             work / f'system_ext/etc/selinux/mapping/{version}.cil',
             work / 'product/etc/selinux/product_sepolicy.cil',
             work / f'product/etc/selinux/mapping/{version}.cil',
             work / 'vendor/etc/selinux/plat_pub_versioned.cil',
             work / 'vendor/etc/selinux/vendor_sepolicy.cil'):
    if path.is_file():
        files.append(path)
command = [args.secilc, '-m', '-M', 'true', '-G', '-c', '33',
           '-o', str(work / 'policy'), '-f', str(work / 'file_contexts')]
if not args.strict:
    command.append('-N')
print(f'Policy compilation workspace: {work}', flush=True)
with (work / 'compiler.log').open('w') as log:
    result = subprocess.run(command + [str(p) for p in files], stdout=log, stderr=log)
print(f'Compiler exit: {result.returncode}; complete diagnostics: {work / "compiler.log"}')
raise SystemExit(result.returncode)
