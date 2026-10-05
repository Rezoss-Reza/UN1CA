#!/usr/bin/env python3
"""Compile and probe isolated DLC experiments; never installs ROM payloads."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys

import numpy as np


def run(command, env=None, cwd=None, timeout=600):
    result = subprocess.run(list(map(str, command)), env=env, cwd=cwd,
                            capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(shlex.join(list(map(str, command))) + '\n' +
                           (result.stdout + result.stderr)[-8000:])
    return result.stdout.replace('\r', '')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--runtime-libs', type=Path, required=True)
    parser.add_argument('--python-libs', type=Path, required=True)
    parser.add_argument('--model-dir', type=Path, required=True)
    parser.add_argument('--adb', default='/mnt/c/platform-tools/adb.exe')
    parser.add_argument('--build', action='store_true')
    parser.add_argument('--device', action='store_true')
    args = parser.parse_args()
    sdk, folder = args.sdk.resolve(), args.model_dir.resolve()
    config = json.loads((folder / 'experiment.json').read_text())
    output_selection = (config.get('graph_name', '') + ' ' + ','.join(config['outputs'])).strip()
    if not re.fullmatch(r'[a-zA-Z0-9_-]+', config['remote_name']):
        raise ValueError('Invalid isolated device directory name')
    if 'source_dlc' in config:
        source_hash = hashlib.sha256(Path(config['source_dlc']).read_bytes()).hexdigest()
        if 'source_sha256' in config and source_hash != config['source_sha256']:
            raise ValueError('Donor model hash changed')
    candidate = folder / config['candidate']
    tools = sdk / 'bin/x86_64-linux-clang'
    env = os.environ.copy()
    env['PYTHONPATH'] = str(sdk / 'lib/python')
    env['LD_LIBRARY_PATH'] = ':'.join(map(str, (sdk / 'lib/x86_64-linux-clang',
                                              args.runtime_libs.resolve(), args.python_libs.resolve())))
    env['SNPE_ROOT'] = env['QAIRT_SDK_ROOT'] = str(sdk)
    env['PATH'] = ':'.join((str(Path(sys.executable).parent), str(tools), env.get('PATH', '')))
    if args.build:
        if candidate.exists():
            raise FileExistsError('Use a fresh directory; candidate already exists: ' + str(candidate))
        source = Path(config.get('source_dlc', folder / 'float.dlc'))
        if 'onnx' in config:
            command = [sys.executable, tools / 'snpe-onnx-to-dlc', '-i', folder / config['onnx'],
                       '-o', source, '--input_layout', config['input'], 'NHWC',
                       '--preserve_io', 'layout', config['input'], *config['outputs'],
                       '--model_version', config['name']]
            for output in config['outputs']:
                command += ['--out_name', output]
            (folder / 'convert.log').write_text(run(command, env, folder))
            quantized = folder / 'quantized.dlc'
            command = [tools / 'snpe-dlc-quantize', '--input_dlc', source,
                       '--output_dlc', quantized, '--input_list', folder / 'calibration.txt',
                       '--act_bitwidth', '16', '--weights_bitwidth', str(config.get('weights_bitwidth', 8)),
                       '--bias_bitwidth', '32', '--axis_quant']
            if config.get('enhanced_quantizer', True):
                command += ['--use_enhanced_quantizer']
            (folder / 'quantize.log').write_text(run(command, env, folder))
            if not quantized.is_file():
                raise RuntimeError('Quantizer did not produce a DLC')
            source = quantized
        command = [tools / 'snpe-dlc-graph-prepare', '--input_dlc', source,
                   '--output_dlc', candidate, '--htp_socs', 'sm8550', '--optimization_level', '3']
        command += ['--set_output_tensors', ','.join(config['outputs'])]
        (folder / 'prepare.log').write_text(run(command, env, folder))
        if not candidate.is_file():
            raise RuntimeError('Graph prepare did not produce a DLC')
    inspection = run([sys.executable, tools / 'snpe-dlc-info', '-i', candidate], env)
    (folder / 'inspect.log').write_text(inspection)
    if 'HTP_V73_SM8550_8MB' not in inspection:
        raise ValueError('No SM8550/V73 cache')
    report = {'name': config['name'], 'candidate_sha256': hashlib.sha256(candidate.read_bytes()).hexdigest(),
              'cache': 'HTP_V73_SM8550_8MB', 'tests': []}
    if 'source_dlc' in config:
        report.update({'source_dlc': config['source_dlc'], 'source_sha256': source_hash})
    if not args.device:
        print(json.dumps(report, indent=2))
        return
    adb = args.adb
    soc = run([adb, 'shell', 'getprop', 'ro.soc.model']).strip()
    if soc != 'SM8550':
        raise ValueError('Expected SM8550; got ' + soc)
    remote = '/data/local/tmp/unica-dlc-' + config['remote_name']
    # A fresh remote directory avoids accidentally comparing stale inference results.
    run([adb, 'shell', 'test ! -e ' + shlex.quote(remote) + ' && mkdir ' + shlex.quote(remote)])

    def host_path(path):
        return run(['wslpath', '-w', str(path)]).strip() if adb.endswith('.exe') else str(path)

    libraries = [sdk / 'lib/aarch64-android' / name for name in (
        'libSNPE.so', 'libQnnHtp.so', 'libQnnSystem.so', 'libSnpeHtpV73Stub.so', 'libQnnHtpV73Stub.so')]
    libraries += [sdk / 'lib/hexagon-v73/unsigned' / name for name in (
        'libSnpeHtpV73Skel.so', 'libQnnHtpV73Skel.so')]
    run([adb, 'push', *map(host_path, [candidate, folder / 'inputs.txt',
                                     sdk / 'bin/aarch64-android/snpe-net-run', *libraries]), remote + '/'])
    device_hash = run([adb, 'shell', 'sha256sum', remote + '/' + candidate.name]).split()[0]
    if device_hash != report['candidate_sha256']:
        raise ValueError('Device model checksum mismatch')
    report['device_sha256'] = device_hash
    run([adb, 'push', host_path(folder / 'inputs'), remote + '/'])
    run([adb, 'shell', 'chmod', '755', remote + '/snpe-net-run'])
    command = ['env', 'LD_LIBRARY_PATH=' + remote + ':/vendor/lib64',
               'ADSP_LIBRARY_PATH=' + remote + ';/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/dsp',
               remote + '/snpe-net-run', '--container', remote + '/' + candidate.name,
               '--input_list', remote + '/inputs.txt', '--output_dir', remote + '/dsp-output',
               '--set_output_tensors', output_selection,
               '--use_dsp', '--profiling_level', 'detailed', '--platform_options', 'unsignedPD:ON',
               '--cache_compatibility_mode', 'strict', '--validate_cache']
    (folder / 'device-run.log').write_text(run([adb, 'shell', 'cd ' + shlex.quote(remote) +
                                               ' && ' + shlex.join(command)]))
    pulled = folder / 'device-results'
    pulled.mkdir(exist_ok=False)
    run([adb, 'pull', remote + '/dsp-output', host_path(pulled)])
    profile = run([tools / 'snpe-diagview', '--input_log',
                   pulled / 'dsp-output/SNPEDiag_0.log'], env)
    (folder / 'device-profile.txt').write_text(profile)
    layers = re.findall(r'^\s+\d+:.* : (DSP|CPU|GPU)\s*$', profile, re.MULTILINE)
    if not layers or set(layers) != {'DSP'}:
        raise ValueError('Not all layers ran on DSP')
    timing = re.search(r'Total Inference Time:\s*(\d+) us', profile)
    report.update({'soc': soc, 'remote': remote, 'runtime': 'DSP', 'profiled_layers': len(layers),
                   'sample_average_inference_us': int(timing.group(1)) if timing else None})
    names = [Path(line.strip()).stem for line in (folder / 'inputs.txt').read_text().splitlines() if line.strip()]
    reference_failures = []
    if 'source_dlc' in config:
        host_inputs = folder / 'host-inputs.txt'
        host_inputs.write_text('\n'.join(str(folder / 'inputs' / (name + '.raw')) for name in names) + '\n')
        command = [tools / 'snpe-net-run', '--container', config['source_dlc'],
                   '--input_list', host_inputs, '--output_dir', folder / 'cpu-reference',
                   '--set_output_tensors', output_selection]
        (folder / 'host-reference.log').write_text(run(command, env, folder))
    for index, name in enumerate(names):
        entry = {'image': name, 'outputs': {}}
        for output, shape in config['outputs'].items():
            raw = np.fromfile(pulled / 'dsp-output' / ('Result_' + str(index)) / (output + '.raw'), np.float32)
            if raw.size != np.prod(shape) or not np.isfinite(raw).all():
                raise ValueError('Invalid device output: ' + name + '/' + output)
            reference = (folder / 'cpu-reference' / ('Result_' + str(index)) / (output + '.raw')
                         if 'source_dlc' in config else folder / (name + '-' + output + '-reference.raw'))
            expected = np.fromfile(reference, np.float32)
            if expected.shape != raw.shape or not np.isfinite(expected).all():
                raise ValueError('Invalid reference output')
            error = raw - expected
            relative_rmse = float(np.sqrt(np.mean(error.astype(np.float64) ** 2)) /
                                  max(np.sqrt(np.mean(expected.astype(np.float64) ** 2)), 1e-8))
            cosine = float(np.dot(raw.astype(np.float64), expected.astype(np.float64)) /
                           max(np.linalg.norm(raw.astype(np.float64)) * np.linalg.norm(expected.astype(np.float64)), 1e-8))
            entry['outputs'][output] = {'shape': shape, 'max_abs_error': float(np.abs(error).max()),
                                        'relative_rmse': relative_rmse, 'cosine': cosine}
            if relative_rmse > config.get('max_relative_rmse', 0.15):
                reference_failures.append(name + '/' + output)
        report['tests'].append(entry)
        print(name, entry['outputs'], flush=True)
    report['reference_passed'] = not reference_failures
    report['reference_failures'] = reference_failures
    (folder / 'device-results.json').write_text(json.dumps(report, indent=2) + '\n')
    if reference_failures:
        raise ValueError('DSP/reference mismatch: ' + ', '.join(reference_failures))
    print('Verified', len(layers), 'DSP layers; sample average', report['sample_average_inference_us'], 'us')


if __name__ == '__main__':
    main()
