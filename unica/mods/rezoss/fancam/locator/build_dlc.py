#!/usr/bin/env python3
"""Build a V73 Locator candidate without modifying ROM install inputs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--model-dir', type=Path, required=True)
    parser.add_argument('--runtime-libs', type=Path, required=True)
    parser.add_argument('--python-libs', type=Path, required=True)
    args = parser.parse_args()
    sdk = args.sdk.resolve()
    model = args.model_dir.resolve()
    env = os.environ.copy()
    env['PYTHONPATH'] = str(sdk / 'lib/python')
    env['LD_LIBRARY_PATH'] = ':'.join(map(str, (sdk / 'lib/x86_64-linux-clang',
                                              args.runtime_libs, args.python_libs)))
    env['SNPE_ROOT'] = env['QAIRT_SDK_ROOT'] = str(sdk)
    tools = sdk / 'bin/x86_64-linux-clang'
    env['PATH'] = ':'.join((str(Path(sys.executable).parent), str(tools), env.get('PATH', '')))
    commands = [
        ('convert', [sys.executable, str(tools / 'snpe-onnx-to-dlc'),
                     '-i', str(model / 'locator-person.onnx'),
                     '-o', str(model / 'locator-person-float.dlc'),
                     '--input_layout', 'images', 'NHWC', '--preserve_io', 'layout', 'images', 'output',
                     '--out_name', 'output', '--model_version', 'UN1CA-Locator-Person-Experiment-v1']),
        ('quantize', [str(tools / 'snpe-dlc-quantize'),
                      '--input_dlc', str(model / 'locator-person-float.dlc'),
                      '--output_dlc', str(model / 'locator-person-quant.dlc'),
                      '--input_list', str(model / 'calibration.txt'),
                      '--act_bitwidth', '16', '--weights_bitwidth', '8', '--bias_bitwidth', '32',
                      '--use_enhanced_quantizer', '--axis_quant']),
        ('prepare', [str(tools / 'snpe-dlc-graph-prepare'),
                     '--input_dlc', str(model / 'locator-person-quant.dlc'),
                     '--output_dlc', str(model / 'Locator-person-SM8550-v1.dlc'),
                     '--htp_socs', 'sm8550', '--set_output_tensors', 'output',
                     '--optimization_level', '3', '--verbose']),
        ('inspect', [sys.executable, str(tools / 'snpe-dlc-info'),
                     '-i', str(model / 'Locator-person-SM8550-v1.dlc')]),
    ]
    results = []
    for name, command in commands:
        log = model / (name + '.log')
        print('Running', name, flush=True)
        with log.open('w') as stream:
            result = subprocess.run(command, env=env, cwd=model, stdout=stream,
                                    stderr=subprocess.STDOUT, timeout=600)
        results.append({'stage': name, 'command': command, 'exit': result.returncode})
        (model / 'build-results.json').write_text(json.dumps(results, indent=2) + '\n')
        if result.returncode:
            print(log.read_text()[-6000:])
            raise SystemExit(result.returncode)
        expected_files = {'convert': 'locator-person-float.dlc',
                          'quantize': 'locator-person-quant.dlc',
                          'prepare': 'Locator-person-SM8550-v1.dlc'}
        if name in expected_files and not (model / expected_files[name]).is_file():
            raise RuntimeError('Tool exited successfully without producing ' + expected_files[name])
    inspection = (model / 'inspect.log').read_text()
    if 'HTP_V73_SM8550_8MB' not in inspection:
        raise ValueError('Missing SM8550/V73 cache')
    artifact = model / 'Locator-person-SM8550-v1.dlc'
    print('Built', artifact, 'SHA256', hashlib.sha256(artifact.read_bytes()).hexdigest())


if __name__ == '__main__':
    main()
