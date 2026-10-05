#!/usr/bin/env python3
"""Recheck an existing donor-weight inventory with the legacy SDK reader."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--inventory', type=Path, required=True)
    parser.add_argument('--donor', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--python36', type=Path, required=True)
    parser.add_argument('--runtime-libs', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    sdk = args.sdk.resolve()
    tools = sdk / 'bin/x86_64-linux-clang'
    env = dict(os.environ, PYTHONPATH=str(sdk / 'lib/python'),
               LD_LIBRARY_PATH=':'.join(map(str, (sdk / 'lib/x86_64-linux-clang',
                   args.python36.resolve().parent.parent / 'lib', args.runtime_libs.resolve()))),
               PYTHONDONTWRITEBYTECODE='1')
    version = subprocess.check_output([str(tools / 'snpe-dlc-graph-prepare'), '--version'],
                                      env=env, text=True).strip()
    if not version.startswith('SNPE v2.10.'):
        raise ValueError('Expected SNPE 2.10')
    inventory = json.loads(args.inventory.read_text())
    donor = args.donor.resolve()
    # Verify every path before reusing the earlier portable-weight findings.
    paths = []
    for entry in inventory:
        path = (donor / entry['relative_path']).resolve()
        path.relative_to(donor)
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if digest != entry.get('sha256'):
            raise ValueError('Inventory hash mismatch: ' + entry['relative_path'])
        paths.append((entry, path, digest))
    current = {str(path.relative_to(donor)) for path in donor.rglob('*.dlc')}
    if current != {entry['relative_path'] for entry in inventory}:
        raise ValueError('Inventory coverage changed; rerun the modern donor audit')
    args.output.mkdir(parents=True, exist_ok=False)
    report = {'sdk_version': version, 'inventory_sha256': hashlib.sha256(args.inventory.read_bytes()).hexdigest(),
              'rom_payload_changed': False, 'models': []}
    seen = {}
    for entry, path, digest in paths:
        if digest not in seen:
            log_name = digest + '.log'
            try:
                result = subprocess.run([str(args.python36), str(tools / 'snpe-dlc-info'),
                                         '-i', str(path)], env=env, capture_output=True,
                                        text=True, timeout=60)
                log = result.stdout + result.stderr
                outcome = {'reader_exit': result.returncode, 'reader_passed': result.returncode == 0,
                           'reader_log': log_name}
            except subprocess.TimeoutExpired:
                log = 'Legacy reader timed out after 60 seconds\n'
                outcome = {'reader_exit': None, 'reader_passed': False, 'reader_log': log_name}
            (args.output / log_name).write_text(log)
            seen[digest] = outcome
        report['models'].append(dict(relative_path=entry['relative_path'], sha256=digest,
                                     weight_status=entry.get('weight_status'),
                                     graphs=entry.get('graphs', []), **seen[digest]))
        (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(len(report['models']), path.name, seen[digest]['reader_passed'], flush=True)
    report['summary'] = {'paths': len(paths), 'unique_models': len(seen),
                         'legacy_readable_unique': sum(item['reader_passed'] for item in seen.values()),
                         'portable_weight_unique': len({item['sha256'] for item in report['models']
                                                       if item['weight_status'] == 'portable'})}
    (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(report['summary'], flush=True)


if __name__ == '__main__':
    main()
