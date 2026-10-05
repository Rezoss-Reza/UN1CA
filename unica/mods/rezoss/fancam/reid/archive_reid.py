#!/usr/bin/env python3
"""Preserve a tested experimental ReID candidate without activating it."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model-dir', type=Path, required=True)
    parser.add_argument('--destination', type=Path, required=True)
    parser.add_argument('--osnet-license', type=Path, required=True)
    args = parser.parse_args()
    source = args.model_dir.resolve()
    config = json.loads((source / 'experiment.json').read_text())
    device = json.loads((source / 'device-results.json').read_text())
    native = json.loads((source / 'native-results.json').read_text())
    candidate = source / config['candidate']
    digest = hashlib.sha256(candidate.read_bytes()).hexdigest()
    if not device.get('reference_passed') or digest != device['candidate_sha256']:
        raise ValueError('Candidate does not match passing device reference tests')
    if native['device_dlc_sha256'] != digest or len(native['tests']) != 10:
        raise ValueError('Native report does not match tested candidate')
    if len(native['pair_checks']) != 5 or not all(item['separated'] for item in native['pair_checks']):
        raise ValueError('Sample identities were not separated')
    args.osnet_license.read_bytes()
    destination = args.destination.resolve()
    destination.mkdir(parents=True, exist_ok=False)
    for name in (config['candidate'], config['onnx'], 'experiment.json', 'host-results.json',
                 'device-results.json', 'native-results.json', 'device-profile.txt',
                 'convert.log', 'quantize.log', 'prepare.log', 'device-run.log'):
        shutil.copy2(source / name, destination / name)
    for pattern in ('*-reference.raw', '*-host-pose.jpg', '*-dsp-pose.jpg'):
        for item in source.glob(pattern):
            shutil.copy2(item, destination / item.name)
    for name in ('inputs', 'device-results'):
        shutil.copytree(source / name, destination / name)
    shutil.copy2(args.osnet_license, destination / 'OSNet-LICENSE.txt')
    shutil.copytree(Path(__file__).resolve().parent, destination / 'source',
                    ignore=shutil.ignore_patterns('__pycache__'))
    runner = Path(__file__).resolve().parent.parent / 'dlc_audit/run_candidate.py'
    shutil.copy2(runner, destination / 'run_candidate.py')
    hashes = [hashlib.sha256(item.read_bytes()).hexdigest() + '  ' + str(item.relative_to(destination))
              for item in sorted(destination.rglob('*')) if item.is_file()]
    (destination / 'SHA256SUMS').write_text('\n'.join(hashes) + '\n')
    print(destination)
    print('Experimental only: no ROM files or active model selection changed.')


if __name__ == '__main__':
    main()
