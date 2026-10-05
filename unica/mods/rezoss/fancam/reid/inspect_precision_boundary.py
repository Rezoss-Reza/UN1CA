#!/usr/bin/env python3
"""Compare exposed pose intermediates on stock CPU and V73 without installing."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess

import numpy as np
import onnx
import onnxruntime as ort


def main():
    parser = argparse.ArgumentParser()
    for name in ('model', 'dlc', 'input', 'probe', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--tensors', nargs='+')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    adb = '/mnt/c/platform-tools/adb.exe'

    def run(command):
        return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT, timeout=180).strip()

    def windows(path):
        return run(['wslpath', '-w', str(path.resolve())])

    def shell(command):
        return run([adb, 'shell', 'su -c ' + shlex.quote(command)])

    if shell('sha256sum /vendor/lib64/libSNPE.so').split()[0] != '835a653e15ebda246a9d646b3176b485563518da4b72b9ef5de270ae790228e2':
        raise ValueError('Runtime hash mismatch')
    names = args.tensors or ['pose/simcc_x', 'pose/simcc_y', 'post//Div_output_0',
             'post//Div_5_output_0', 'output_0_pose', 'output_1_feature']
    model = onnx.shape_inference.infer_shapes(onnx.load(args.model))
    shapes = {v.name: v for v in (*model.graph.value_info, *model.graph.output)}
    del model.graph.output[:]
    model.graph.output.extend(shapes[name] for name in names)
    options = ort.SessionOptions()
    options.log_severity_level = 3
    options.intra_op_num_threads = 4
    session = ort.InferenceSession(model.SerializeToString(), options, providers=['CPUExecutionProvider'])
    host = dict(zip(names, session.run(names, {'images': np.fromfile(args.input, np.float32).reshape(1, 320, 320, 3)})))
    remote = '/data/local/tmp/unica-boundary-' + args.output.name
    shell('test ! -e ' + remote + ' && mkdir ' + remote)
    run([adb, 'push', windows(args.dlc), windows(args.input), windows(args.probe), remote + '/'])
    shell('chmod 755 ' + remote + '/' + args.probe.name)
    report = {}
    for processor in ('cpu', 'dsp'):
        dest = remote + '/' + processor
        shell('mkdir ' + dest)
        command = shlex.join(['env', 'LD_LIBRARY_PATH=/vendor/lib64',
                'ADSP_LIBRARY_PATH=/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/dsp', remote + '/' + args.probe.name,
                remote + '/' + args.dlc.name, remote + '/' + args.input.name, dest, processor, *names])
        log = shell(command)
        (args.output / (processor + '.log')).write_text(log + '\n')
        local = args.output / processor
        local.mkdir()
        run([adb, 'pull', dest + '/.', windows(local)])
        report[processor] = {}
        for name in names:
            expected = host[name]
            match = re.search(r'^' + re.escape(name) + r' shape ([0-9 ]+)$', log, re.MULTILINE)
            if not match:
                raise ValueError('Missing runtime shape: ' + name)
            runtime_shape = tuple(map(int, match.group(1).split()))
            actual = np.fromfile(local / (name.replace('/', '_') + '.raw'), np.float32).reshape(runtime_shape)
            layout = 'unchanged'
            if runtime_shape != expected.shape:
                if expected.ndim != 4 or runtime_shape != (expected.shape[0], expected.shape[2], expected.shape[3], expected.shape[1]):
                    raise ValueError('Unexplained layout: ' + name)
                actual = actual.transpose(0, 3, 1, 2)
                layout = 'NHWC-to-NCHW'
            report[processor][name] = {'shape': list(expected.shape),
                'runtime_shape': list(runtime_shape), 'layout_restoration': layout,
                'relative_rmse': float(np.linalg.norm(actual - expected) / max(np.linalg.norm(expected), 1e-12)),
                'host_min': float(expected.min()), 'host_max': float(expected.max()),
                'actual_min': float(actual.min()), 'actual_max': float(actual.max())}
    (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
