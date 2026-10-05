#!/usr/bin/env python3
"""Compare an isolated legacy DLC on stock SNPE against saved float outputs."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import sys

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'dlc_audit'))
from run_candidate import run


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model-dir', type=Path, required=True)
    parser.add_argument('--model', choices=('locator', 'reid', 'humanpet'), required=True)
    parser.add_argument('--probe', type=Path, required=True)
    parser.add_argument('--snap', action='store_true', help='Probe installed MCA/SNAP instead of direct SNPE')
    parser.add_argument('--frames', type=int, default=3)
    parser.add_argument('--adb', default='/mnt/c/platform-tools/adb.exe')
    args = parser.parse_args()
    if args.snap and args.model == 'humanpet':
        parser.error('The SNAP probe only verifies Locator/ReID session ABIs; use direct SNPE for humanpet')
    folder, adb = args.model_dir.resolve(), args.adb
    build = json.loads((folder / 'build-results.json').read_text())
    if build['status'] != 'built-not-device-validated':
        raise ValueError('Build did not complete')
    if run([adb, 'shell', 'getprop', 'ro.soc.model']).strip() != 'SM8550':
        raise ValueError('Expected SM8550')
    candidate = folder / ('UN1CA-' + args.model + '-SM8550-SNPE210-experimental.dlc')
    if args.frames < 1 or args.frames > 120:
        raise ValueError('Expected 1..120 frames')
    prefix = 'snap' if args.snap else 'device'
    remote = '/data/local/tmp/unica-' + folder.name + ('-snap' if args.snap else '')
    run([adb, 'shell', 'test ! -e ' + shlex.quote(remote) + ' && mkdir ' + shlex.quote(remote)])

    def windows(path):
        return run(['wslpath', '-w', str(path)]).strip() if adb.endswith('.exe') else str(path)

    run([adb, 'push', windows(candidate), windows(args.probe.resolve()), remote + '/'])
    run([adb, 'push', windows(folder / 'inputs'), remote + '/'])
    run([adb, 'shell', 'chmod 755 ' + remote + '/' + args.probe.name])
    checksum = run([adb, 'shell', 'sha256sum', remote + '/' + candidate.name]).split()[0]
    if checksum != build['candidate_sha256']:
        raise ValueError('Device checksum mismatch')
    mca_hash = None
    if args.snap:
        mca_hash = run([adb, 'shell', 'sha256sum', '/system/lib64/libmediacontextanalyzer.so']).split()[0]
        if mca_hash != 'bedfc253a4c577d6917f2a79412c1c98d7c43cf5b2101406de9e970e526fabd7':
            raise ValueError('Private MCA ABI hash mismatch')
        alias = 'Locator.dlc' if args.model == 'locator' else 'Reid.dlc'
        run([adb, 'shell', 'ln', '-s', candidate.name, remote + '/' + alias])
    report = {'candidate_sha256': checksum, 'remote': remote, 'soc': 'SM8550',
              'runtime_library_sha256': run([adb, 'shell', 'sha256sum', '/vendor/lib64/libSNPE.so']).split()[0],
              'requested_runtime': 'MCA CoreType=0 through stock SNAP' if args.snap else 'DSP only',
              'mca_library_sha256': mca_hash, 'frames_per_input': args.frames if args.snap else 1,
              'layer_profile_captured': False,
              'probe_limitations': 'Probe uses _Exit; process-shutdown and Gallery integration untested.',
              'tests': [], 'reference_failures': []}
    names = [Path(line.strip()).stem for line in (folder / 'inputs.txt').read_text().splitlines() if line.strip()]
    for index, name in enumerate(names):
        result_dir = remote + '/dsp-output/Result_' + str(index)
        run([adb, 'shell', 'mkdir -p ' + result_dir])
        command = ['env', 'LD_LIBRARY_PATH=' + ('/system/lib64:' if args.snap else '') + '/vendor/lib64',
                   'ADSP_LIBRARY_PATH=/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/dsp',
                   remote + '/' + args.probe.name, remote + '/' + candidate.name,
                   remote + '/inputs/' + name + '.raw', result_dir, 'dsp', *build['outputs']]
        if args.snap:
            command = command[:3] + [remote + '/' + args.probe.name, '/system/lib64/libmediacontextanalyzer.so',
                                    remote + '/' + alias, remote + '/inputs/' + name + '.raw', result_dir, str(args.frames)]
            command = ['su', '-c', shlex.join(command)]
        log = run([adb, 'shell', shlex.join(command)])
        (folder / (name + '-' + prefix + '.log')).write_text(log)
    destination = folder / (prefix + '-results')
    destination.mkdir(exist_ok=False)
    run([adb, 'pull', remote + '/dsp-output', windows(destination)])
    for index, name in enumerate(names):
        entry = {'image': name, 'outputs': {}}
        for output, shape in build['outputs'].items():
            suffix = {'locator': '-onnx-output.raw', 'humanpet': '-output-reference.raw',
                      'reid': '-' + output + '-reference.raw'}[args.model]
            reference = folder / (name + suffix)
            expected = np.fromfile(reference, np.float32).astype(np.float64)
            actual = np.fromfile(destination / 'dsp-output' / ('Result_' + str(index)) / (output + '.raw'), np.float32).astype(np.float64)
            if actual.size != int(np.prod(shape)) or expected.shape != actual.shape or not np.isfinite(actual).all():
                raise ValueError('Invalid tensor: ' + name + '/' + output)
            cosine = float(np.dot(expected, actual) / max(np.linalg.norm(expected) * np.linalg.norm(actual), 1e-8))
            rmse = float(np.linalg.norm(expected - actual) / max(np.linalg.norm(expected), 1e-8))
            entry['outputs'][output] = {'shape': shape, 'relative_rmse': rmse, 'cosine': cosine}
            if rmse > 0.2 or (output == 'output_1_feature' and cosine < 0.98):
                report['reference_failures'].append(name + '/' + output)
        report['tests'].append(entry)
        print(name, entry['outputs'], flush=True)
    report['reference_passed'] = not report['reference_failures']
    (folder / (prefix + '-results.json')).write_text(json.dumps(report, indent=2) + '\n')
    if not report['reference_passed']:
        raise ValueError('Reference quality gates failed; do not install this candidate')
    print('Stock ' + ('SNAP' if args.snap else 'SNPE') + ' sample inference/reference gates passed; native/Gallery validation remains separate.')


if __name__ == '__main__':
    main()
