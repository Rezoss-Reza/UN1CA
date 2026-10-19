#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Keep local backups outside ROM images and signed installer manifests."""
from pathlib import Path
import os
import shutil
import stat
import subprocess
import sys
import tempfile


def backup_name(name):
    return name.endswith('.bak') or '.bak.' in name


def backups(root):
    for parent, dirs, files in os.walk(root, followlinks=False):
        for name in list(dirs):
            if backup_name(name):
                dirs.remove(name)
                yield Path(parent) / name
        for name in files:
            if backup_name(name):
                yield Path(parent) / name


def prepare(root):
    root = root.resolve(strict=True)
    if next(backups(root), None) is None:
        return root
    dest = Path(tempfile.mkdtemp(prefix='unica-rom-payload.'))
    try:
        subprocess.run(['rsync', '-aHAX', '--exclude=*.bak', '--exclude=*.bak.*',
                        '--', str(root) + '/', str(dest) + '/'], check=True)
        assert next(backups(dest), None) is None
    except BaseException:
        clean(dest)
        raise
    return dest


def clean(root):
    # Only remove a temporary view made by prepare(), never caller input trees.
    if root.parent.resolve() != Path(tempfile.gettempdir()).resolve() or not root.name.startswith('unica-rom-payload.') or root.is_symlink():
        raise ValueError('Refusing to remove a non-temporary ROM input')
    # Runtime read-only directory modes are retained in the image view. Make
    # only our temporary directories writable before removing that view.
    for parent, _, _ in os.walk(root, followlinks=False):
        os.chmod(parent, stat.S_IMODE(os.stat(parent).st_mode) | 0o700)
    shutil.rmtree(root)


def quarantine(root):
    found = list(backups(root))
    if not found:
        return
    dest = Path(tempfile.mkdtemp(prefix='unica-excluded-backups.'))
    for path in found:
        out = dest / path.relative_to(root)
        out.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(path), str(out))
    print(f'Excluded {len(found)} local backups; preserved at {dest}', file=sys.stderr)


if __name__ == '__main__':
    command, path = sys.argv[1:]
    root = Path(path)
    if command == 'prepare':
        print(prepare(root))
    elif command == 'clean':
        clean(root)
    elif command == 'quarantine':
        quarantine(root)
    else:
        raise SystemExit('Unknown ROM payload operation')
