#!/usr/bin/env python3
# Copyright (c) 2026 Rezoss
# SPDX-License-Identifier: GPL-3.0-or-later
"""Package the checked ROM backend as a removable hotfix for already-flashed builds."""
from pathlib import Path
import argparse
import subprocess
import zipfile

root = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('output', type=Path)
args = parser.parse_args()
subprocess.run(['sha256sum', '--check', '--status', 'source.sha256'], cwd=root, check=True)
subprocess.run(['sha256sum', '--check', '--status', '../payload.sha256'], cwd=root / 'payload', check=True)
args.output.parent.mkdir(parents=True, exist_ok=True)
# Never silently overwrite an existing installable artifact.
with zipfile.ZipFile(args.output, 'x', zipfile.ZIP_DEFLATED) as archive:
    for directory in (root / 'runtime-hotfix', root / 'payload'):
        for path in sorted(directory.rglob('*')):
            if path.is_file():
                archive.write(path, str(path.relative_to(directory)))
print(args.output)
