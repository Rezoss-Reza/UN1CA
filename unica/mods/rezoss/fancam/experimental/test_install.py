#!/usr/bin/env python3
"""Exercise the FanCam install block with real helpers in temporary work dirs."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[5]
MOD = ROOT / 'unica/mods/rezoss'
MODELS = {
    'Detection.dlc': '07-03_Video_HumanPetDetection_UN1CA_Experimental_v1_SM8550-SNPE210.dlc',
    'Locator.dlc': '07-21_Video_ReframingTargetLocator_UN1CA_Experimental_v3_SM8550-SNPE210.dlc',
    'Reid.dlc': '07-22_Video_ReframingReID_UN1CA_Experimental_v21_SM8550-SNPE210.dlc',
}
DONORS = {
    'Detection.dlc': '07-03_Video_HumanPetDetection_v2.0.1_SM8850_SNPE238.dlc',
    'Locator.dlc': '07-21_Video_ReframingTargetLocator_v0.1.0_SM8850-SNPE239.dlc',
    'Reid.dlc': '07-22_Video_ReframingReID_v0.2.3_SM8850-SNPE244.dlc',
}
ARCHIVES = {
    'Detection.dlc': ROOT / 'out/archived/UN1CA-Camera-SNPE210-20261004/humanpet-v2/UN1CA-humanpet-SM8550-SNPE210-experimental.dlc',
    'Locator.dlc': ROOT / 'out/archived/UN1CA-Detection-Repair-20261004/conditional-snpe210/UN1CA-locator-SM8550-SNPE210-experimental.dlc',
    'Reid.dlc': ROOT / 'out/archived/UN1CA-FanCam-Tracking-20261005/reid-unit-appearance-v21b/UN1CA-reid-SM8550-SNPE210-experimental.dlc',
}
LEGACY = (
    '07-21_Video_ReframingTargetLocator_UN1CA_Experimental_v1_SM8550-SNPE244.dlc',
    '07-22_Video_ReframingReID_UN1CA_Experimental_v1_SM8550-SNPE244.dlc',
)
BACKUP = ROOT / 'out/archived/UN1CA-SNPE210-Rebuild-Experiments-20261004/rom-source-backup-20261004-1000'
EXPECTED_HASHES = {
    'Detection.dlc': 'f055902476940bd6c3f25c87f9bd3353c34f31c806b1c40cd4170c87939f9179',
    'Locator.dlc': 'b43091ef9885cc7b3c136a69ce702d0afcc49d7fd5e5a9c748d2a389ed0e7ea6',
    'Reid.dlc': '64de5d1f753c143aa02cf97799b739097f64c152b41ba3a3c3c06d9b7522fb06',
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    script = (MOD / 'customize.sh').read_text()
    block = script.split('if [[ "$TARGET_CODENAME" == "dm3q" ]]; then', 1)[1]
    block = 'if [[ "$TARGET_CODENAME" == "dm3q" ]]; then' + block.split('\nADD_TO_WORK_DIR "m3qxxx" "vendor" "etc/saiv/image_understanding/db/fm"', 1)[0]
    payload = MOD / 'fancam/experimental/system/etc/mediacontextanalyzer'
    for alias, model in MODELS.items():
        assert (payload / alias).is_symlink() and os.readlink(payload / alias) == model
        assert digest(payload / model) == digest(ARCHIVES[alias])
        assert digest(payload / model) == EXPECTED_HASHES[alias]
    for codename in ('dm3q', 'other-device'):
        with tempfile.TemporaryDirectory(prefix='unica-fancam-install-') as temp:
            work = Path(temp)
            (work / 'configs').mkdir()
            parents = ('system', 'system/etc', 'system/etc/mediacontextanalyzer')
            (work / 'configs/fs_config-system').write_text(''.join(
                name + ' 0 0 755 capabilities=0x0\n' for name in parents))
            (work / 'configs/file_context-system').write_text(''.join(
                '/' + name + ' u:object_r:system_file:s0\n' for name in parents))
            env = dict(os.environ, SRC_DIR=str(ROOT), MODPATH=str(MOD), WORK_DIR=temp,
                       TARGET_CODENAME=codename, TARGET_OS_BUILD_SYSTEM_EXT_PARTITION='true',
                       OLD_MODPATH=str(BACKUP))
            header = 'set -e\nsource "$SRC_DIR/scripts/utils/common_utils.sh"\n'
            donor_setup = '\n'.join(
                'ADD_TO_WORK_DIR "m3qxxx" "system" "system/etc/mediacontextanalyzer/' + name +
                '" 0 0 644 "u:object_r:system_file:s0"' for name in (*DONORS.values(), *DONORS))
            subprocess.run(['bash'], input=header + donor_setup, text=True, env=env,
                           check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            if codename == 'dm3q':
                stale = (
                    '07-21_Video_ReframingTargetLocator_UN1CA_Experimental_v2_SM8550-SNPE210.dlc',
                    '07-22_Video_ReframingReID_UN1CA_Experimental_v2_SM8550-SNPE210.dlc',
                )
                for name in stale:
                    (work / 'system/system/etc/mediacontextanalyzer' / name).write_bytes(b'stale-test-placeholder')
                    entry = 'system/etc/mediacontextanalyzer/' + name
                    with (work / 'configs/fs_config-system').open('a') as metadata:
                        metadata.write(entry + ' 0 0 644 capabilities=0x0\n')
                    with (work / 'configs/file_context-system').open('a') as contexts:
                        contexts.write('/' + entry.replace('.', '\\.') + ' u:object_r:system_file:s0\n')
                legacy_setup = '\n'.join(
                    'ADD_TO_WORK_DIR "$OLD_MODPATH" "system" "system/etc/mediacontextanalyzer/' + name +
                    '" 0 0 644 "u:object_r:system_file:s0"' for name in LEGACY)
                subprocess.run(['bash'], input=header + legacy_setup, text=True, env=env,
                               check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            shell = header + block
            # Install twice to cover replacing stale donor aliases and repeat builds.
            for _ in range(2):
                result = subprocess.run(['bash'], input=shell, text=True, env=env,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                if result.returncode:
                    raise RuntimeError(result.stdout + result.stderr)
            target = work / 'system/system/etc/mediacontextanalyzer'
            expected = MODELS if codename == 'dm3q' else DONORS
            for alias, model in expected.items():
                assert os.readlink(target / alias) == model
                assert (target / alias).is_file()
                if codename == 'dm3q':
                    assert digest(target / alias) == digest(ARCHIVES[alias])
                    assert not (target / DONORS[alias]).exists()
                    assert all(not (target / old).exists() for old in LEGACY)
                    assert all(not (target / old).exists() for old in stale)
            metadata = (work / 'configs/fs_config-system').read_text().splitlines()
            contexts = (work / 'configs/file_context-system').read_text().splitlines()
            for name in (*expected, *expected.values()):
                entry = 'system/etc/mediacontextanalyzer/' + name
                assert metadata.count(entry + ' 0 0 644 capabilities=0x0') == 1
                assert contexts.count('/' + entry.replace('.', '\\.') + ' u:object_r:system_file:s0') == 1
            if codename == 'dm3q':
                for name in (*DONORS.values(), *LEGACY, *stale):
                    entry = 'system/etc/mediacontextanalyzer/' + name
                    assert all(not line.startswith(entry + ' ') for line in metadata)
                    assert all(not line.startswith('/' + entry.replace('.', '\\.') + ' ') for line in contexts)
            print(codename + ': payloads, aliases, metadata and repeated install passed')


if __name__ == '__main__':
    main()
