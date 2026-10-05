#!/usr/bin/env python3
"""Separate float conversion, quantization and DSP errors on identical inputs."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess

import numpy as np
import onnxruntime as ort

from diagnose_video import cosine


def main():
    parser = argparse.ArgumentParser()
    for name in ('model-dir', 'probe', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--adb', default='/mnt/c/platform-tools/adb.exe')
    parser.add_argument('--limit', type=int, default=16)
    args = parser.parse_args()
    if args.limit < 1:
        parser.error('Positive input limit required')
    args.output.mkdir(parents=True, exist_ok=False)

    def run(command):
        return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT,
                                       timeout=180).strip()

    def windows(path):
        return run(['wslpath', '-w', str(path.resolve())]) if args.adb.endswith('.exe') else str(path)

    def shell(command):
        return run([args.adb, 'shell', 'su -c ' + shlex.quote(command)])

    runtime = '/vendor/lib64/libSNPE.so'
    runtime_hash = shell('sha256sum ' + runtime).split()[0]
    if runtime_hash != '835a653e15ebda246a9d646b3176b485563518da4b72b9ef5de270ae790228e2':
        raise ValueError('Stock runtime hash mismatch')
    remote = '/data/local/tmp/unica-precision-' + args.output.name
    shell('test ! -e ' + shlex.quote(remote) + ' && mkdir ' + shlex.quote(remote))
    paths = [args.probe, args.model_dir / 'float.dlc', args.model_dir / 'quantized.dlc',
             args.model_dir / 'UN1CA-reid-SM8550-SNPE210-experimental.dlc']
    run([args.adb, 'push', *map(windows, paths), remote + '/'])
    for path in paths:
        expected = hashlib.sha256(path.read_bytes()).hexdigest()
        if shell('sha256sum ' + shlex.quote(remote + '/' + path.name)).split()[0] != expected:
            raise ValueError('Device payload hash mismatch')
    shell('chmod 755 ' + shlex.quote(remote + '/' + args.probe.name))
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    session = ort.InferenceSession(str(args.model_dir / 'reid-human.onnx'), options,
                                  providers=['CPUExecutionProvider'])
    outputs = ['output_0_pose', 'output_1_feature']
    names = [Path(line.strip()).stem for line in (args.model_dir / 'inputs.txt').read_text().splitlines()
             if line.strip()][:args.limit]
    report = {'runtime_sha256': runtime_hash, 'tests': [], 'failures': [], 'reference_failures': [],
              'limitations': 'Direct SNPE diagnostics; CPU/DSP selection is explicit. Not Gallery acceptance.'}
    stages = [('float_cpu', 'float.dlc', 'cpu'), ('quantized_cpu', 'quantized.dlc', 'cpu'),
              ('cached_dsp', paths[-1].name, 'dsp')]
    for name in names:
        raw = args.model_dir / 'inputs' / (name + '.raw')
        run([args.adb, 'push', windows(raw), remote + '/input.raw'])
        tensor = np.fromfile(raw, np.float32).reshape(1, 320, 320, 3)
        host = dict(zip(outputs, session.run(outputs, {'images': tensor})))
        entry = {'input': name, 'stages': {}}
        for stage, dlc, processor in stages:
            directory = remote + '/' + stage + '-' + name
            shell('mkdir ' + shlex.quote(directory))
            command = shlex.join(['env', 'LD_LIBRARY_PATH=/vendor/lib64',
                'ADSP_LIBRARY_PATH=/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/dsp',
                remote + '/' + args.probe.name, remote + '/' + dlc,
                remote + '/input.raw', directory, processor, *outputs])
            try:
                log = shell(command)
                (args.output / (stage + '-' + name + '.log')).write_text(log + '\n')
                destination = args.output / (stage + '-' + name)
                destination.mkdir()
                run([args.adb, 'pull', directory + '/.', windows(destination)])
                metrics = {}
                for output in outputs:
                    value = np.fromfile(destination / (output + '.raw'), np.float32)
                    expected = host[output].reshape(-1)
                    if value.shape != expected.shape or not np.isfinite(value).all():
                        raise ValueError('Invalid output tensor')
                    rmse = float(np.linalg.norm(value - expected) / max(np.linalg.norm(expected), 1e-12))
                    metrics[output] = {'relative_rmse': rmse, 'cosine': cosine(value, expected),
                                       'min': float(value.min()), 'max': float(value.max())}
                    passed = rmse <= 0.2 and (output != 'output_1_feature' or metrics[output]['cosine'] >= 0.98)
                    metrics[output]['reference_passed'] = passed
                    if not passed:
                        report['reference_failures'].append(name + '/' + stage + '/' + output)
                    if output == 'output_0_pose':
                        a, b = value.reshape(174, 640), expected.reshape(174, 640)
                        metrics[output]['mean_peak_bin_error'] = float(np.abs(a.argmax(1) - b.argmax(1)).mean())
                        metrics[output]['peak_rows_above_one'] = int(np.count_nonzero(a.max(1) > 1))
                entry['stages'][stage] = metrics
            except subprocess.CalledProcessError as error:
                (args.output / (stage + '-' + name + '.log')).write_text(error.output)
                entry['stages'][stage] = {'error': error.output[-2000:]}
                report['failures'].append(name + '/' + stage)
        report['tests'].append(entry)
        (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(name, json.dumps(entry['stages']), flush=True)


if __name__ == '__main__':
    main()
