#!/usr/bin/env python3
# Copyright (c) 2026 Rezoss
# SPDX-License-Identifier: GPL-3.0-or-later
"""Host integration checks. Never loads a keybox or connects to a device."""
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

MOD = Path(__file__).resolve().parents[1]
REPO = MOD.parents[2]
spec = importlib.util.spec_from_file_location('teesim_policy', MOD / 'install_policy.py')
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class IntegrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='teesim-test-', dir='/tmp')
        self.root = Path(self.temp.name)
        self.work = self.root / 'work'
        self.selinux = self.work / 'system_ext/etc/selinux'
        self.selinux.mkdir(parents=True)
        for name in ('system_ext_sepolicy.cil', 'system_ext_file_contexts',
                     'system_ext_property_contexts'):
            (self.selinux / name).write_text('# original\n' if not name.endswith('.cil') else '; original\n')
        (self.work / 'configs').mkdir()
        for name in ('fs_config-system', 'file_context-system'):
            (self.work / 'configs' / name).touch()
        parents = ['system', 'system/bin', 'system/lib64', 'system/framework',
                   'system/etc', 'system/etc/init', 'system/etc/unica',
                   'system/etc/unica/tee-simulator']
        (self.work / 'configs/fs_config-system').write_text(''.join(
            f'{p} 0 0 755 capabilities=0x0\n' for p in parents))
        (self.work / 'configs/file_context-system').write_text(''.join(
            f'/{p} u:object_r:system_file:s0\n' for p in parents))
        self.framework = self.root / 'framework'
        self.framework.mkdir()

    def tearDown(self):
        self.temp.cleanup()

    def customize(self, mod=MOD, target='dm3q', sdk='37'):
        script = r'''
set -e
source "$SRC_DIR/scripts/utils/common_utils.sh"
ABORT() { echo "$1" >&2; return 1; }
APPLY_PATCH() { patch --fuzz=0 -p1 -d "$TEST_FRAMEWORK" --forward -i "$3"; }
DECODE_APK() { :; }
source "$MODPATH/customize.sh"
'''
        env = dict(os.environ, SRC_DIR=str(REPO), WORK_DIR=str(self.work), MODPATH=str(mod),
                   TEST_FRAMEWORK=str(self.framework), TARGET_CODENAME=target,
                   APKTOOL_DIR=str(self.root / 'apktool'),
                   SOURCE_PLATFORM_SDK_VERSION=sdk, TARGET_OS_BUILD_SYSTEM_EXT_PARTITION='true',
                   DEBUG='false')
        return subprocess.run(['bash', '-c', script], env=env, text=True, capture_output=True)

    def test_real_helpers_install_payload_policy_metadata_and_compatibility(self):
        result = self.customize()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        binary = self.work / 'system/system/bin/unica-teesim-inject'
        self.assertEqual(binary.read_bytes(), (MOD / 'payload/system/bin/unica-teesim-inject').read_bytes())
        metadata = (self.work / 'configs/fs_config-system').read_text()
        self.assertIn('system/bin/unica-teesim-inject 0 0 755', metadata)
        self.assertIn('system/lib64/libunica_teesim.so 0 0 644', metadata)
        self.assertIn('(type unica_teesim)', (self.selinux / 'system_ext_sepolicy.cil').read_text())
        self.assertTrue((self.framework / 'smali_classes6/io/mesalabs/unica/KeyProviderManager.smali').is_file())
        self.assertFalse((self.framework / 'smali_classes6/io/mesalabs/unica/KeyboxDbHelper.smali').exists())

    def test_policy_install_is_idempotent_and_invalidates_precompiled_digest(self):
        policy.install(self.work, MOD)
        first = {f.name: f.read_bytes() for f in self.selinux.iterdir()}
        policy.install(self.work, MOD)
        self.assertEqual(first, {f.name: f.read_bytes() for f in self.selinux.iterdir()})
        self.assertEqual(len((self.selinux / 'system_ext_sepolicy_and_mapping.sha256').read_text().strip()), 64)

    def test_unsupported_target_skips_without_installing_compatibility_marker(self):
        for target, sdk in [('dm1q', '37'), ('dm3q', '36')]:
            result = self.customize(target=target, sdk=sdk)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertFalse((self.work / 'system').exists())
            self.assertFalse(any(self.framework.iterdir()))

    def test_modified_payload_fails_before_copying_anything(self):
        mod = self.root / 'mod'
        mod.mkdir()
        shutil.copy2(MOD / 'customize.sh', mod)
        shutil.copy2(MOD / 'payload.sha256', mod)
        (mod / 'payload/system/bin').mkdir(parents=True)
        (mod / 'payload/system/bin/unica-teesim-inject').write_bytes(b'corrupt')
        result = self.customize(mod)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.work / 'system').exists())

    def test_modified_source_fails_before_copying_anything(self):
        mod = self.root / 'mod'
        mod.mkdir()
        shutil.copy2(MOD / 'customize.sh', mod)
        shutil.copy2(MOD / 'payload.sha256', mod)
        (mod / 'payload').symlink_to(MOD / 'payload', target_is_directory=True)
        (mod / 'source.sha256').write_text('0' * 64 + '  absent-source\n')
        result = self.customize(mod)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('sources changed', result.stderr)
        self.assertFalse((self.work / 'system').exists())

    def test_cached_old_backend_is_rejected_before_install(self):
        old = self.root / 'apktool/system/framework/framework.jar/smali_classes6/io/mesalabs/unica/KeyboxDbHelper.smali'
        old.parent.mkdir(parents=True)
        old.touch()
        result = self.customize()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('clean ROM build', result.stderr)
        self.assertFalse((self.work / 'system').exists())

    def test_payload_has_dex_and_rom_entrypoint(self):
        with zipfile.ZipFile(MOD / 'payload/system/framework/unica-teesim.jar') as z:
            self.assertIsNone(z.testzip())
            self.assertTrue(z.read('classes.dex').startswith(b'dex\n'))
            data = b''.join(z.read(n) for n in z.namelist() if n.endswith('.dex'))
            self.assertIn(b'Lorg/matrix/teesim/RomApp;', data)
        self.assertTrue((REPO / 'unica/mods/keybox/disable').is_file())

    def test_pif_fallback_recognizes_enabled_native_backend_in_module_order(self):
        repo = self.root / 'repo'
        native = repo / 'unica/mods/tee-simulator'
        native.mkdir(parents=True)
        (native / 'module.prop').touch()
        trace = self.root / 'pif-trace'
        script = r'''
set -e
APPLY_PATCH() { :; }
SMALI_PATCH() { printf '%s\n' "$5" >> "$TEST_TRACE"; }
source "$TEST_PIF"
'''
        for disabled, target, sdk, fallback in [
            (False, 'dm3q', '37', False), (True, 'dm3q', '37', True),
            (False, 'dm1q', '37', True), (False, 'dm3q', '36', True)]:
            marker = native / 'disable'
            if disabled:
                marker.touch()
            else:
                marker.unlink(missing_ok=True)
            trace.write_text('')
            env = dict(os.environ, SRC_DIR=str(repo), APKTOOL_DIR=str(self.root / 'apktool'),
                       MODPATH=str(REPO / 'unica/mods/pif'), TARGET_CODENAME=target,
                       SOURCE_PLATFORM_SDK_VERSION=sdk, TEST_TRACE=str(trace),
                       TEST_PIF=str(REPO / 'unica/mods/pif/customize.sh'))
            result = subprocess.run(['bash', '-c', script], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual('shouldBlockKeyAttestation()Z' in trace.read_text(), fallback)


if __name__ == '__main__':
    unittest.main(verbosity=2)
