# SPDX-License-Identifier: GPL-3.0-or-later
"""Exercise module copy, old work trees, real filesystem images and signed ZIPs."""
from pathlib import Path
import importlib.util
import os
import shutil
import stat
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module
payload = load('payload', ROOT / 'scripts/internal/rom_payload.py')
sha = load('sha', ROOT / 'scripts/internal/package_sha.py')
with tempfile.TemporaryDirectory(prefix='unica-payload-test.') as tmp:
    work = Path(tmp); source = work / 'source'; init = source / 'system/etc/init'; init.mkdir(parents=True)
    (init / 'unica_gpu.rc').write_text('real service\n'); (init / 'unica_gpu.rc').chmod(0o755)
    for name in ('unica_gpu.rc.bak', 'unica_gpu.rc.bak.20261018', '.hidden.bak'):
        (init / name).write_text('old service\n')
    (init / 'not.bakery').write_text('keep\n'); (init / 'saved.bak').mkdir(exist_ok=True) if not (init / 'saved.bak').exists() else None
    (init / 'saved.bak/inside').write_text('local backup directory\n')
    (init / 'readonly').mkdir(); (init / 'readonly/file').write_text('keep readonly mode\n'); (init / 'readonly').chmod(0o555)
    (init / 'link').symlink_to('unica_gpu.rc')
    contexts = source / 'system/etc/selinux/plat_file_contexts'; contexts.parent.mkdir(parents=True); contexts.write_text('/.* u:object_r:system_file:s0\n')
    # A split binary inside a backup directory must never be reconstructed.
    (init / 'saved.bak/test.00').write_bytes(b'one'); (init / 'saved.bak/test.01').write_bytes(b'two')
    script = r'''
set -e
export SRC_DIR="$1" WORK_DIR="$2" TARGET_OS_BUILD_SYSTEM_EXT_PARTITION=true
mkdir -p "$WORK_DIR/configs"
source "$SRC_DIR/scripts/utils/common_utils.sh"
ADD_TO_WORK_DIR "$3" system . 0 0 755 u:object_r:system_file:s0
ADD_TO_WORK_DIR "$3" system etc/init/unica_gpu.rc.bak 0 0 755 u:object_r:system_file:s0
'''
    modules = work / 'modules'; (modules / 'saved.bak').mkdir(parents=True)
    (modules / 'saved.bak/module.prop').write_text('name=Backup module\nauthor=Test\n')
    (modules / 'saved.bak/customize.sh').write_text('exit 77\n')
    subprocess.run(['bash', str(ROOT / 'scripts/internal/apply_modules.sh'), str(modules)], env={**os.environ, 'SRC_DIR': str(ROOT)}, check=True, stdout=subprocess.DEVNULL)
    target = work / 'target'
    subprocess.run(['bash', '-c', script, 'test', str(ROOT), str(target), str(source)], check=True, stdout=subprocess.DEVNULL)
    assert list(payload.backups(target)) == []
    assert 'unica_gpu.rc.bak' not in (target / 'configs/fs_config-system').read_text()
    assert (target / 'system/system/etc/init/unica_gpu.rc').read_text() == 'real service\n'
    # Preserve cp's overwrite semantics even for equal-size fixed-timestamp blobs.
    original = (init / 'unica_gpu.rc').stat()
    (init / 'unica_gpu.rc').write_text('next service\n')
    os.utime(init / 'unica_gpu.rc', ns=(original.st_atime_ns, original.st_mtime_ns))
    subprocess.run(['bash', '-c', script, 'test', str(ROOT), str(target), str(source)], check=True, stdout=subprocess.DEVNULL)
    assert (target / 'system/system/etc/init/unica_gpu.rc').read_text() == 'next service\n'
    # Old generated outputs retain their local backups, but the image view excludes them.
    view = payload.prepare(source); assert view != source
    assert list(payload.backups(view)) == [] and len(list(payload.backups(source))) == 4
    assert stat.S_IMODE((view / 'system/etc/init/unica_gpu.rc').stat().st_mode) == 0o755
    assert os.readlink(view / 'system/etc/init/link') == 'unica_gpu.rc'
    for fs in ('erofs', 'ext4'):
        image = work / (fs + '.img')
        if fs == 'erofs':
            subprocess.run(['mkfs.erofs', str(image), str(view)], check=True, stdout=subprocess.DEVNULL)
            listing = subprocess.check_output(['dump.erofs', '--ls', '--path=/system/etc/init', str(image)], text=True)
        else:
            subprocess.run(['mke2fs', '-q', '-t', 'ext4', '-b', '4096', '-d', str(view), '-F', str(image), '4096'], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            listing = subprocess.check_output(['debugfs', '-R', 'ls -l /system/etc/init', str(image)], text=True, stderr=subprocess.DEVNULL)
        assert 'unica_gpu.rc.bak' not in listing and 'saved.bak' not in listing and 'unica_gpu.rc' in listing
    assert stat.S_IMODE((view / 'system/etc/init/readonly').stat().st_mode) == 0o555
    payload.clean(view); assert source.is_dir()
    assert stat.S_IMODE((init / 'readonly').stat().st_mode) == 0o555
    try: payload.clean(source)
    except ValueError: pass
    else: raise AssertionError('clean accepted source tree')
    package = work / 'package'; wrapper = package / (sha.WRAPPER + '.unica'); wrapper.parent.mkdir(parents=True); wrapper.write_text('installer\n')
    (package / 'payload').write_text('data\n'); (package / 'payload.bak.1').write_text('backup\n')
    payload.quarantine(package); sha.prepare(package)
    archive = work / 'rom.zip'
    subprocess.run(['7z', 'a', '-tzip', str(archive), '.', '-xr!*.bak', '-xr!*.bak.*'], cwd=package, check=True, stdout=subprocess.DEVNULL)
    sha.finish(archive)
    with zipfile.ZipFile(archive) as z: assert not any(payload.backup_name(Path(n).name) for n in z.namelist())
    # Clean trees are returned unchanged, avoiding a full copy on normal builds.
    assert payload.prepare(target) == target.resolve()
print('PASS module/split-file exclusion, metadata, source preservation, modes/symlinks, ext4/EROFS and SHA ZIP integration')
