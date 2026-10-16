#!/usr/bin/env python3
# Copyright (c) 2026 Rezoss
# SPDX-License-Identifier: GPL-3.0-or-later
"""Exercise recovery migration in a redirected temporary filesystem; no device/build."""
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
import zipfile

REPO = Path(__file__).resolve().parents[4]
INSTALLER = REPO / 'target/dm3q/installer'
ENTRY = 'unica/tee-simulator/retire-runtime-hotfix'
PAYLOAD = ['system/bin/unica-teesim-inject', 'system/framework/unica-teesim.jar',
           'system/lib64/libunica_teesim.so']
RULES = [
    '(allow unica_teesim keystore (unix_dgram_socket (sendto)))',
    '(allow system_server unica_teesim (fd (use)))',
    '(allow system_server unica_teesim (fifo_file (read write getattr ioctl)))',
]


class RetirementTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='tee-retirement-', dir='/tmp')
        self.root = Path(self.temp.name)
        self.data = self.root / 'data'
        self.system = self.root / 'system_root'
        self.policy = self.root / 'system_ext/etc/selinux/system_ext_sepolicy.cil'
        self.policy.parent.mkdir(parents=True)
        self.policy.write_text('\n'.join(RULES) + '\n')
        manifest = []
        for relative in PAYLOAD:
            path = self.system / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(relative.encode())
            manifest.append(hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + relative)
        service = self.system / 'system/etc/init/unica-teesim.rc'
        service.parent.mkdir(parents=True)
        service.touch()
        self.manifest = '\n'.join(manifest) + '\n'
        self.modules = []
        for directory in ['modules', 'modules_update']:
            module = self.data / 'adb' / directory / 'unica_teesim_runtime_fix'
            module.mkdir(parents=True)
            (module / 'module.prop').write_text('id=unica_teesim_runtime_fix\n')
            self.modules.append(module)
        other = self.data / 'adb/modules/other-module'
        other.mkdir()
        (other / 'module.prop').write_text('id=other-module\n')
        self.other = other
        self.keys = self.data / 'misc/unica-teesim/harvested.json'
        self.keys.parent.mkdir(parents=True)
        self.keys.write_text('preserve this state')
        # Redirect only literal absolute filesystem roots. Variable-relative
        # paths and manifest entries retain their production interpretation.
        script = (INSTALLER / 'root' / (ENTRY + '.sh')).read_text()
        script = re.sub(r'(?<![A-Za-z0-9_$}])/(?:system_root|system_ext|system)(?![A-Za-z0-9_])',
                        lambda match: str(self.root) + match.group(), script)
        self.script = script.replace('DATA_DIR=/data', 'DATA_DIR=' + str(self.data))
        tools = self.root / 'bin'
        tools.mkdir()
        (tools / 'mount').write_text('#!/bin/sh\nexit 0\n')
        (tools / 'mount').chmod(0o755)
        (tools / 'umount').write_text('#!/bin/sh\nexit 0\n')
        (tools / 'umount').chmod(0o755)
        self.env = dict(os.environ, PATH=str(tools) + ':' + os.environ['PATH'])

    def tearDown(self):
        self.temp.cleanup()

    def run_wrapper(self, updater=0, dfe=0, marker=True):
        archive = self.root / 'rom.zip'
        with zipfile.ZipFile(archive, 'w') as z:
            z.writestr('META-INF/com/google/android/update-binary.unica',
                       '#!/bin/sh\nexit ' + str(updater) + '\n')
            z.writestr('unica/dfe/dfe-maintain.sh', '#!/bin/sh\nexit ' + str(dfe) + '\n')
            z.writestr(ENTRY + '.sh', self.script)
            if marker:
                z.writestr(ENTRY + '.sha256', self.manifest)
        wrapper = self.root / 'wrapper.sh'
        wrapper.write_text((INSTALLER / 'update-binary-wrapper.sh').read_text().replace(
            'TMPDIR="/tmp/unica-installer"', 'TMPDIR="' + str(self.root / 'installer') + '"'))
        return subprocess.run(['sh', str(wrapper), '3', '', str(archive)],
                              env=self.env, capture_output=True, text=True)

    def assert_preserved(self):
        self.assertFalse((self.other / 'disable').exists())
        self.assertEqual(self.keys.read_text(), 'preserve this state')
        self.assertTrue(all((m / 'module.prop').is_file() for m in self.modules))

    def assert_enabled(self):
        self.assertTrue(all(not (m / 'disable').exists() for m in self.modules))
        self.assert_preserved()

    def test_success_disables_active_and_staged_hotfix_only(self):
        result = self.run_wrapper()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(all((m / 'disable').is_file() for m in self.modules), result.stdout)
        self.assert_preserved()

    def test_failed_rom_install_keeps_hotfix(self):
        self.assertEqual(self.run_wrapper(updater=7).returncode, 7)
        self.assert_enabled()

    def test_failed_dfe_keeps_hotfix(self):
        self.assertEqual(self.run_wrapper(dfe=8).returncode, 8)
        self.assert_enabled()

    def test_old_rom_payload_keeps_hotfix(self):
        (self.system / PAYLOAD[0]).write_text('old injector')
        result = self.run_wrapper()
        self.assertIn('still needs the hotfix', result.stdout)
        self.assert_enabled()

    def test_missing_policy_rule_keeps_hotfix(self):
        self.policy.write_text('\n'.join(RULES[:-1]) + '\n')
        self.assertIn('policy still needs', self.run_wrapper().stdout)
        self.assert_enabled()

    def test_old_package_without_manifest_keeps_hotfix(self):
        self.assertEqual(self.run_wrapper(marker=False).returncode, 0)
        self.assert_enabled()

    def test_missing_service_keeps_hotfix(self):
        (self.system / 'system/etc/init/unica-teesim.rc').unlink()
        self.run_wrapper()
        self.assert_enabled()

    def test_duplicate_manifest_entry_keeps_hotfix(self):
        self.manifest = self.manifest.splitlines()[0] + '\n'
        self.manifest *= 3
        self.run_wrapper()
        self.assert_enabled()

    def test_packaging_adds_verified_manifest(self):
        package = self.root / 'package'
        binary = package / 'META-INF/com/google/android/update-binary'
        binary.parent.mkdir(parents=True)
        binary.write_text('test updater')
        result = subprocess.run(['bash', '-c',
            'LOG() { :; }; source "$TEST_CUSTOMIZE"'], capture_output=True, text=True,
            env=dict(self.env, SRC_DIR=str(REPO), TARGET_CODENAME='dm3q',
                     SOURCE_PLATFORM_SDK_VERSION='37', TMP_DIR=str(package),
                     TEST_CUSTOMIZE=str(INSTALLER / 'customize.sh')))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((package / (ENTRY + '.sha256')).read_bytes(),
                         (REPO / 'unica/mods/tee-simulator/payload.sha256').read_bytes())
        self.assertEqual(binary.with_name('update-binary.unica').read_text(), 'test updater')

    def test_unexpected_module_id_is_preserved(self):
        (self.modules[0] / 'module.prop').write_text('id=unrelated-module\n')
        result = self.run_wrapper()
        self.assertIn('Unexpected hotfix layout', result.stdout)
        self.assertFalse((self.modules[0] / 'disable').exists())
        self.assertTrue((self.modules[1] / 'disable').exists())
        self.assert_preserved()

    def test_symlinked_disable_does_not_touch_target(self):
        protected = self.root / 'protected'
        protected.write_text('unchanged')
        (self.modules[0] / 'disable').symlink_to(protected)
        self.run_wrapper()
        self.assertEqual(protected.read_text(), 'unchanged')
        self.assertTrue((self.modules[1] / 'disable').exists())
        self.assert_preserved()


if __name__ == '__main__':
    unittest.main(verbosity=2)
