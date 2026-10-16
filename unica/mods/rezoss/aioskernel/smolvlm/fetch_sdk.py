#!/usr/bin/env python3
"""Fetch and selectively extract the SDK used by the isolated device proof."""
import hashlib
import pathlib
import shutil
import sys
import urllib.request
import zipfile

VERSION = '2.37.1.250807'
SHA256 = 'f491833df0ef75958c5d6af4b50df3d5e70541af578fe4c5f3a73b3b00eb7e33'
destination = pathlib.Path(sys.argv[1])
if destination.exists():
    raise SystemExit(f'Refusing existing SDK destination: {destination}')
archive = destination.with_suffix('.zip')
if not archive.exists():
    temporary = archive.with_suffix('.zip.part')
    url = f'https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/{VERSION}/v{VERSION}.zip'
    with temporary.open('xb') as out, urllib.request.urlopen(url, timeout=120) as source:
        shutil.copyfileobj(source, out, 1024 * 1024)
    temporary.rename(archive)
with archive.open('rb') as stream:
    if hashlib.file_digest(stream, 'sha256').hexdigest() != SHA256:
        raise SystemExit(f'SDK archive hash mismatch: {archive}')
destination.mkdir(parents=True)
with zipfile.ZipFile(archive) as contents:
    for item in contents.infolist():
        if item.is_dir():
            continue
        for marker in ('include/QNN/', 'lib/aarch64-android/', 'lib/hexagon-v73/unsigned/'):
            if marker in item.filename:
                relative = pathlib.PurePosixPath(item.filename[item.filename.index(marker):])
                if '..' in relative.parts:
                    raise SystemExit('Unexpected SDK archive path')
                target = destination / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                with contents.open(item) as source, target.open('xb') as out:
                    shutil.copyfileobj(source, out)
                break
print(destination)
