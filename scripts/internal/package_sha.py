#!/usr/bin/env python3
# Copyright (c) 2026 Rezoss
# SPDX-License-Identifier: GPL-3.0-or-later
"""Opt-in download-corruption protection; not publisher authentication."""
import hashlib
import pathlib
import re
import sys
import zipfile

MANIFEST = 'unica/integrity/sha256.list'
WRAPPER = 'META-INF/com/google/android/update-binary'
# Avoid a cycle with the OTA signature, which itself covers this manifest.
EXCLUDED = {MANIFEST, WRAPPER, 'unica/ota/update.json'}
ROOT = pathlib.Path(__file__).resolve().parents[2]


def digest(stream):
    h = hashlib.sha256()
    while chunk := stream.read(1024 * 1024):
        h.update(chunk)
    return h.hexdigest()


def prepare(directory):
    wrapper = directory / WRAPPER
    if not (directory / (WRAPPER + '.unica')).is_file():
        raise ValueError('-sha requires the dm3q wrapped installer')
    # Always use the current wrapper; a copied baseline may carry an old digest.
    wrapper.write_bytes((ROOT / 'target/dm3q/installer/update-binary-wrapper.sh').read_bytes())
    wrapper.chmod(0o755)
    rows = []
    for path in sorted(directory.rglob('*')):
        if not path.is_file():
            continue
        name = path.relative_to(directory).as_posix()
        if name in EXCLUDED:
            continue
        # unzip treats wildcard characters as patterns; forbid ambiguous names.
        if not re.fullmatch(r'[A-Za-z0-9_./+@= -]+', name):
            raise ValueError(f'Unsupported integrity entry name: {name!r}')
        with path.open('rb') as stream:
            rows.append(f'{digest(stream)}  {name}\n')
    if not rows:
        raise ValueError('Empty integrity manifest')
    manifest = directory / MANIFEST
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(''.join(rows))
    expected = hashlib.sha256(manifest.read_bytes()).hexdigest()
    text = wrapper.read_text()
    if text.count('INTEGRITY_SHA256=""') != 1:
        raise ValueError('Missing wrapper integrity switch')
    wrapper.write_text(text.replace('INTEGRITY_SHA256=""', f'INTEGRITY_SHA256="{expected}"'))


def finish(archive):
    with zipfile.ZipFile(archive) as z:
        names = z.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate archive entries')
        bad = z.testzip()
        if bad:
            raise ValueError(f'ZIP CRC failure: {bad}')
        manifest = z.read(MANIFEST)
        wrapper = z.read(WRAPPER).decode()
        expected = hashlib.sha256(manifest).hexdigest()
        if f'INTEGRITY_SHA256="{expected}"' not in wrapper:
            raise ValueError('Installer/manifest checksum mismatch')
        covered = set()
        for row in manifest.decode().splitlines():
            checksum, name = row.split('  ', 1)
            if name in covered:
                raise ValueError('Duplicate manifest entry')
            covered.add(name)
            with z.open(name) as stream:
                if digest(stream) != checksum:
                    raise ValueError(f'Payload checksum mismatch: {name}')
        expected_names = {n for n in names if not n.endswith('/')} - EXCLUDED
        # signapk may add the certificate after full-ROM manifest generation.
        if covered - expected_names or (expected_names - covered) - {'META-INF/com/android/otacert'}:
            raise ValueError('Incomplete integrity manifest')
    with archive.open('rb') as stream:
        checksum = digest(stream)
    pathlib.Path(str(archive) + '.sha256').write_text(f'{checksum}  {archive.name}\n')
    print(f'ZIP integrity verified; checksum: {archive}.sha256')


if __name__ == '__main__':
    {'prepare': prepare, 'finish': finish}[sys.argv[1]](pathlib.Path(sys.argv[2]))
