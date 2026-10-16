#!/usr/bin/env python3
# Copyright (c) 2026 Rezoss
# SPDX-License-Identifier: GPL-3.0-or-later
"""Recover sparse care blocks from full recovery ZIPs; reuse UN1CA block diff."""
import argparse
import os
import re
from pathlib import Path, PurePosixPath
import shutil
import struct
import subprocess
import tempfile
import zipfile
from datetime import datetime
from zoneinfo import ZoneInfo
import time
import sys

BLOCK = 4096
PARTITIONS = 'system vendor product system_ext odm vendor_dlkm odm_dlkm system_dlkm'.split()


def properties(text):
    return dict(line.split('=', 1) for line in text.splitlines() if '=' in line)


def ranges(text, size):
    values = [int(v) for v in text.split(',')]
    if values[0] != len(values) - 1 or values[0] % 2:
        raise ValueError('Malformed transfer ranges')
    pairs = list(zip(values[1::2], values[2::2]))
    if any(not 0 <= a < b <= size for a, b in pairs):
        raise ValueError('Transfer range outside partition')
    return pairs


def copy_bytes(source, target, length):
    while length:
        data = source.read(min(length, 1024 * 1024))
        if not data:
            raise ValueError('Truncated partition payload')
        target.write(data)
        length -= len(data)


def reconstruct(z, partition, size, directory):
    """Only new/zero ranges are known. Erase and holes remain DONT_CARE.

    Treating erased blocks as zero source data would create false verification
    failures on real devices. Partition length comes from dynamic operations,
    not the transfer header's count of blocks written.
    """
    if size % BLOCK:
        raise ValueError('Partition size is not block aligned')
    commands = z.read(f'{partition}.transfer.list').decode().splitlines()
    if len(commands) < 4 or commands[0] != '4' or commands[2:4] != ['0', '0']:
        raise ValueError('Only full version-4, zero-stash release ZIPs are supported')
    payloads = [n for n in (f'{partition}.new.dat', f'{partition}.new.dat.br') if n in z.namelist()]
    if len(payloads) != 1:
        raise ValueError('Expected exactly one new.dat payload')
    raw = directory / f'{partition}.raw'
    dat = directory / f'{partition}.dat'
    with z.open(payloads[0]) as source, dat.open('wb') as target:
        shutil.copyfileobj(source, target)
    if payloads[0].endswith('.br'):
        decoded = directory / f'{partition}.decoded'
        subprocess.run(['brotli', '-d', '-o', str(decoded), str(dat)], check=True)
        dat.unlink()
        dat = decoded
    care = []
    with raw.open('w+b') as image, dat.open('rb') as data:
        image.truncate(size)
        for command in commands[4:]:
            op, text = command.split()
            if op not in ('new', 'zero', 'erase'):
                raise ValueError(f'Not a full release transfer list: {op}')
            for a, b in ranges(text, size // BLOCK):
                if op == 'erase':
                    continue
                care.append((a, b, op))
                if op == 'new':
                    image.seek(a * BLOCK)
                    copy_bytes(data, image, (b - a) * BLOCK)
        if data.read(1):
            raise ValueError('Unused bytes in partition payload')
    dat.unlink()
    chunks = []
    end = 0
    for a, b, op in sorted(care):
        if a < end:
            raise ValueError('Overlapping written ranges')
        if a > end:
            chunks.append((end, a, 'hole'))
        # Limit RAW chunks to fit Android sparse uint32 chunk length.
        for start in range(a, b, 262144):
            chunks.append((start, min(start + 262144, b), op))
        end = b
    if end < size // BLOCK:
        chunks.append((end, size // BLOCK, 'hole'))
    with (directory / f'{partition}.img').open('wb') as image, raw.open('rb') as source:
        image.write(struct.pack('<I4H4I', 0xED26FF3A, 1, 0, 28, 12, BLOCK,
                                size // BLOCK, len(chunks), 0))
        for a, b, op in chunks:
            kind = {'new': 0xCAC1, 'zero': 0xCAC2, 'hole': 0xCAC3}[op]
            length = (b - a) * BLOCK if op == 'new' else 4 if op == 'zero' else 0
            image.write(struct.pack('<2H2I', kind, 0, b - a, 12 + length))
            if op == 'new':
                source.seek(a * BLOCK)
                copy_bytes(source, image, length)
            elif op == 'zero':
                image.write(b'\0' * 4)
    raw.unlink()
    # No per-file maps survive release packaging. SparseImage uses __DATA;
    # block matching remains available, but file-aware matching is lost.
    (directory / f'{partition}.map').touch()


def convert(release, directory):
    with zipfile.ZipFile(release) as z:
        names = z.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate ZIP members')
        info = properties(z.read('build_info.txt').decode())
        meta = properties(z.read('META-INF/com/android/metadata').decode())
        if any(not re.fullmatch(r'[A-Za-z0-9 ._:/;+=@-]*', value)
               for value in (*info.values(), *meta.values())):
            raise ValueError('Unsafe build metadata characters')
        if info['device'] != 'dm3q' or info.get('incremental') != '0' or meta.get('ota-type') != 'BLOCK':
            raise ValueError('Expected a full dm3q BLOCK release')
        group = None
        sizes = {}
        for line in z.read('dynamic_partitions_op_list').decode().splitlines():
            fields = line.split()
            if not fields or fields[0].startswith('#'):
                continue
            if fields[0] == 'add_group':
                if group or len(fields) != 3:
                    raise ValueError('Multiple dynamic groups unsupported')
                group = fields[1:]
            elif fields[0] == 'resize':
                if len(fields) != 3 or fields[1] in sizes or int(fields[2]) <= 0:
                    raise ValueError('Invalid partition resize operation')
                sizes[fields[1]] = int(fields[2])
            elif fields[0] not in ('remove_all_groups', 'add'):
                raise ValueError('Expected full-release dynamic operations')
        if not group or any(p not in PARTITIONS for p in sizes):
            raise ValueError('Unsupported dynamic partition layout')
        if not re.fullmatch(r'[A-Za-z0-9_]+', group[0]) or sum(sizes.values()) > int(group[1]):
            raise ValueError('Invalid or overflowing dynamic group')
        for p, size in sizes.items():
            print(f'Reconstructing {release.name}: {p}', flush=True)
            reconstruct(z, p, size, directory)
        if 'system' not in sizes:
            raise ValueError('Missing system partition')
        script = z.read('META-INF/com/google/android/updater-script').decode()
        ui = re.search(r'ui_print\("One UI version: (\d+)\.(\d+)(?:\.(\d+))?"\);', script)
        name = re.search(r'ui_print\("UN1CA [^"\n]+ for ([A-Za-z0-9 ._-]+)"\);', script)
        if not ui or not name:
            raise ValueError('Unsupported release header')
        info['oneui_version'] = str(int(ui[1]) * 10000 + int(ui[2]) * 100 + int(ui[3] or 0))
        info['name'] = name[1]
        info['target_fingerprint'] = meta['post-build']
        info.update(os_version=meta['post-sdk-level'], build_incremental=meta['post-build-incremental'],
                    build_date=meta['post-timestamp'], security_patch=meta['post-security-patch-level'],
                    source_fingerprint=meta['post-build'], use_dynamic_partitions='true',
                    super_partition_group=group[0])
        info[f'super_{group[0]}_group_size'] = group[1]
        for name in ('boot.img', 'dt.img', 'dtbo.img', 'init_boot.img', 'vendor_boot.img', 'unsparse_super_empty.img'):
            if name in names:
                with z.open(name) as source, (directory / name).open('wb') as target:
                    shutil.copyfileobj(source, target)
        (directory / 'build_info.txt').write_text(''.join(f'{k}={v}\n' for k, v in info.items()))
    output = directory.parent / f'{directory.name}.zip'
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_STORED, allowZip64=True) as z:
        for path in directory.iterdir():
            z.write(path, path.name)
    shutil.rmtree(directory)
    return output


def installer(release, directory):
    with zipfile.ZipFile(release) as z:
        script = z.read('META-INF/com/google/android/updater-script').decode()
        marker = 'set_progress(1.000000);'
        if script.count(marker) != 1:
            raise ValueError('Unsupported release installer ending')
        for name in z.namelist():
            if name.startswith('unica/integrity/') or name.endswith('/') or name in ('META-INF/com/google/android/updater-script', 'build_info.txt'):
                continue
            if name.startswith('META-INF/') and not name.startswith('META-INF/com/google/android/'):
                continue
            if name == 'dynamic_partitions_op_list' or name.endswith(('.img', '.transfer.list', '.new.dat', '.new.dat.br', '.patch.dat')):
                continue
            path = PurePosixPath(name)
            if path.is_absolute() or '..' in path.parts:
                raise ValueError('Unsafe installer member')
            destination = directory / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            with z.open(name) as source, destination.open('wb') as target:
                shutil.copyfileobj(source, target)
            destination.chmod((z.getinfo(name).external_attr >> 16) & 0o777 or 0o644)
        with (directory / 'META-INF/com/google/android/updater-script').open('a') as target:
            target.write('\n' + script.split(marker)[1])


def release_paths(directory):
    # Keep older releases usable as baselines alongside the new date-first names.
    return set(directory.glob('UN1CA_*.zip')) | set(directory.glob('*_UN1CA-*_Rezoss_*.zip'))


def scan_releases(directory):
    releases = []
    for path in release_paths(directory):
        if not path.is_file():
            continue
        try:
            with zipfile.ZipFile(path) as z:
                info = properties(z.read('build_info.txt').decode())
                meta = properties(z.read('META-INF/com/android/metadata').decode())
                if info.get('device') != 'dm3q' or info.get('incremental') != '0' or meta.get('ota-type') != 'BLOCK':
                    continue
                if 'system.transfer.list' not in z.namelist():
                    continue
                releases.append((int(info['timestamp']), path))
        except (OSError, ValueError, KeyError, UnicodeError, zipfile.BadZipFile) as error:
            raise ValueError(f'Cannot inspect release {path.name}: {error}') from error
    return sorted(releases, key=lambda item: item[0], reverse=True)


def select_releases(directory):
    releases = scan_releases(directory)
    if len(releases) < 2:
        raise ValueError('Need at least two full dm3q ROM ZIP releases in out/; build the new release with make_rom -r first')
    if any(sum(timestamp == selected[0] for timestamp, _ in releases) != 1
           for selected in releases[:2]):
        raise ValueError('Ambiguous release timestamps; use build_release_ota OLD NEW OUTPUT explicitly')
    return releases[1][1], releases[0][1]


def release_commit(release):
    with zipfile.ZipFile(release) as archive:
        version = properties(archive.read('build_info.txt').decode()).get('version', '')
    commit = re.search(r'-([0-9a-fA-F]{7,40})(?:-dirty)?$', version)
    if not commit:
        raise ValueError(f'Cannot determine release commit from build_info.txt version in {release.name}')
    return commit.group(1).lower()


def automatic_output(directory, old, new):
    base_commit, new_commit = release_commit(old), release_commit(new)
    stamp = datetime.now(ZoneInfo('Europe/Paris')).strftime('%d%m%y-%H%M%S')
    return directory / f'OTA_{stamp}_{base_commit}-{new_commit}_UN1CA_OneUI9_Rezoss.zip'


def automatic(directory):
    old, new = select_releases(directory)
    output = automatic_output(directory, old, new)
    print(f'Old release: {old}\nNew release: {new}\nOTA output:  {output}', flush=True)
    build(old, new, output)


def build_automatic(directory):
    directory = directory.resolve()
    releases = scan_releases(directory)
    if not releases:
        raise ValueError('Need one existing full dm3q ROM ZIP in out/ as the OTA baseline')
    timestamp, old = releases[0]
    if sum(stamp == timestamp for stamp, _ in releases) != 1:
        raise ValueError('Ambiguous newest release timestamp; choose the baseline explicitly')
    release_commit(old)  # Reject an unknown baseline commit before rebuilding.
    now = int(time.time())
    if now <= timestamp:
        raise ValueError('Baseline timestamp is not older than current time; retry when the clock has advanced')
    previous = set(release_paths(directory))
    env = dict(os.environ, ROM_BUILD_TIMESTAMP=str(now))
    print(f'OTA baseline: {old}\nBuilding fresh full release (make_rom -f -r)...', flush=True)
    subprocess.run([str(Path(env['SRC_DIR']) / 'scripts/make_rom.sh'), '-f', '-r']
                   + (['-sha'] if env.get('UNICA_PACKAGE_SHA') == 'true' else []), env=env, check=True)
    created = [path for _, path in scan_releases(directory) if path not in previous]
    if len(created) != 1:
        raise ValueError('Expected exactly one newly created full release; full ZIPs are retained, OTA generation stopped')
    new = created[0]
    output = automatic_output(directory, old, new)
    print(f'Old release: {old}\nNew release: {new}\nOTA output:  {output}', flush=True)
    build(old, new, output)


def build(old, new, output):
    old, new, output = (p.resolve() for p in (old, new, output))
    if output.exists() or output.suffix != '.zip' or not output.parent.is_dir():
        raise ValueError('Output must be a new .zip path in an existing directory')
    if old == new:
        raise ValueError('Source and target must be different release files')
    with zipfile.ZipFile(old) as a, zipfile.ZipFile(new) as b:
        old_info = properties(a.read('build_info.txt').decode())
        new_info = properties(b.read('build_info.txt').decode())
        if int(new_info['timestamp']) <= int(old_info['timestamp']):
            raise ValueError('Target release timestamp must be newer than source')
    with tempfile.TemporaryDirectory(prefix='unica-release-ota-') as temporary:
        root = Path(temporary)
        inputs = []
        for label, release in (('source', old), ('target', new)):
            directory = root / label
            directory.mkdir()
            inputs.append(convert(release, directory))
        # Vendor is rebuilt by DFE after a full install: never depend on its
        # pre-DFE source blocks, even if both release ZIP vendor images match.
        env = dict(os.environ, UNICA_OTA_TMP_DIR=str(root / 'package'),
                   UNICA_OTA_RELEASE_INSTALLER=str(new), UNICA_OTA_FULL_VENDOR='true')
        result = root / 'update.zip'
        subprocess.run([str(Path(env['SRC_DIR']) / 'scripts/internal/build_incremental_ota_zip.sh'),
                        *(str(p) for p in inputs), str(result)], env=env, check=True)
        with zipfile.ZipFile(result) as z:
            bad = z.testzip()
            if bad:
                raise ValueError(f'ZIP integrity failure: {bad}')
        checksum = None
        if os.environ.get('UNICA_PACKAGE_SHA') == 'true':
            checksum = Path(str(result) + '.sha256').read_text().split()[0]
        # Publish a complete file atomically without replacing any existing ZIP.
        staged = None
        try:
            with tempfile.NamedTemporaryFile(dir=output.parent, prefix='.unica-ota-', delete=False) as target:
                staged = Path(target.name)
                with result.open('rb') as source:
                    shutil.copyfileobj(source, target)
            staged.chmod(0o644)
            os.link(staged, output)
        finally:
            if staged is not None:
                staged.unlink()
    if checksum is not None:
        Path(str(output) + '.sha256').write_text(f'{checksum}  {output.name}\n')
    print(f'Created {output}')
    subprocess.run([sys.executable, str(Path(__file__).with_name('ota_metadata.py')),
                    'sidecar', str(output)], check=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('build', 'installer', 'auto', 'build-auto'))
    parser.add_argument('paths', nargs='+', type=Path)
    args = parser.parse_args()
    try:
        if args.operation == 'build' and len(args.paths) == 3:
            build(*args.paths)
        elif args.operation == 'installer' and len(args.paths) == 2:
            installer(*args.paths)
        elif args.operation == 'auto' and len(args.paths) == 1:
            automatic(args.paths[0])
        elif args.operation == 'build-auto' and len(args.paths) == 1:
            build_automatic(args.paths[0])
        else:
            parser.error('build takes OLD NEW OUTPUT; installer takes RELEASE DIRECTORY')
    except (ValueError, KeyError, IndexError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        parser.exit(1, f'OTA error: {error}\n')
