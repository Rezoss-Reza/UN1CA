#!/usr/bin/env python3
"""Explicit, reversible diagnostic bind mount; never changes a ROM payload."""
import argparse
import json
from pathlib import Path
import shlex
import subprocess

OLD = 'f9ce6a502fa9ad1b33b5d20bfdd33159c9040613a5f737cc1f18ee8313788c2b'
NEW = '993fe6023f5d8e5787632f2c2f3f4b57af427ce8fe8ec8fe0cb60d0da2d0f2f8'
TARGET = '/system/etc/mediacontextanalyzer/07-22_Video_ReframingReID_UN1CA_Experimental_v2_SM8550-SNPE210.dlc'
SOURCE = '/data/local/tmp/unica-v19-gallery-20261005/Reid-v19.dlc'
CANDIDATES = {
    'v19': (NEW, SOURCE),
    'v21': ('64de5d1f753c143aa02cf97799b739097f64c152b41ba3a3c3c06d9b7522fb06',
            '/data/local/tmp/unica-v21-gallery-20261005/Reid-v21.dlc'),
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=('activate', 'status', 'restore'))
    parser.add_argument('--state', type=Path, required=True)
    parser.add_argument('--experimental-test-approved', action='store_true')
    parser.add_argument('--candidate-version', choices=tuple(CANDIDATES), default='v19')
    args = parser.parse_args()
    candidate_hash, candidate_source = CANDIDATES[args.candidate_version]
    if args.action == 'activate' and not args.experimental_test_approved:
        parser.error('Explicit user approval required for experimental activation')
    state = json.loads(args.state.read_text())
    if (state['target'], state['remote'], state['original_sha256'], state['candidate_sha256'], state['namespace_pid']) != (TARGET, candidate_source, OLD, candidate_hash, 1):
        raise ValueError('Unrecognized test state')
    adb = '/mnt/c/platform-tools/adb.exe'

    def root(command):
        return subprocess.check_output([adb, 'shell', 'su -c ' + shlex.quote(command)],
                                       text=True, stderr=subprocess.STDOUT, timeout=30).strip()

    def in_namespace(pid, command):
        return root(shlex.join(['nsenter', '-t', str(pid), '-m', '--', *command]))

    def checksum(pid):
        return in_namespace(pid, ['sha256sum', TARGET]).split()[0]

    def save():
        args.state.write_text(json.dumps(state, indent=2) + '\n')

    if root('getprop ro.soc.model') != 'SM8550':
        raise ValueError('Expected SM8550')
    if args.action == 'activate':
        if checksum(1) != OLD or root('sha256sum ' + candidate_source).split()[0] != candidate_hash:
            raise ValueError('Original/candidate hash mismatch')
        mounts = root('cat /proc/1/mountinfo')
        if any(line.split()[4] == TARGET for line in mounts.splitlines()):
            raise ValueError('Refusing to replace a pre-existing target mount')
        in_namespace(1, ['mount', '--bind', candidate_source, TARGET])
        try:
            if checksum(1) != candidate_hash:
                raise ValueError('Mount hash verification failed')
            root('am force-stop com.sec.android.app.vepreload')
            state['stage'] = 'active-awaiting-new-editor-process-verification'
            save()
        except Exception:
            in_namespace(1, ['umount', TARGET])
            raise
    elif args.action == 'restore':
        if checksum(1) == candidate_hash:
            mounts = root('cat /proc/1/mountinfo')
            if not any(line.split()[4] == TARGET for line in mounts.splitlines()):
                raise ValueError('Candidate active without expected diagnostic mount')
            in_namespace(1, ['umount', TARGET])
        if checksum(1) != OLD:
            raise ValueError('Original hash not restored')
        root('am force-stop com.sec.android.app.vepreload')
        state['stage'] = 'restored-original'
        save()
    result = {'stage': state['stage'], 'init_namespace_sha256': checksum(1)}
    pid = root('pidof com.sec.android.app.vepreload || true')
    if pid.isdigit():
        result['editor_pid'] = int(pid)
        result['editor_namespace_sha256'] = checksum(int(pid))
        result['editor_sees_v19'] = result['editor_namespace_sha256'] == NEW
        result['editor_sees_candidate'] = result['editor_namespace_sha256'] == candidate_hash
    else:
        result['editor_pid'] = None
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
