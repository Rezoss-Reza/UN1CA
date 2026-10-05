#!/usr/bin/env python3
"""Add early native-crop calibration tensors without changing model semantics."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

import numpy as np
import onnxruntime as ort


def main():
    parser = argparse.ArgumentParser()
    for name in ('source', 'diagnostics', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    for name in ('reid-human.onnx', 'inputs.txt', 'host-results.json'):
        shutil.copy2(args.source / name, args.output / name)
    shutil.copytree(args.source / 'inputs', args.output / 'inputs')
    for path in args.source.glob('*-reference.raw'):
        shutil.copy2(path, args.output / path.name)
    inputs = args.output / 'inputs'
    calibration = [str((inputs / Path(line.strip().split(':=')[-1]).name).resolve())
                   for line in (args.source / 'calibration.txt').read_text().splitlines() if line.strip()]
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.log_severity_level = 3
    session = ort.InferenceSession(str(args.output / 'reid-human.onnx'), options,
                                  providers=['CPUExecutionProvider'])
    manifest = []
    tests = (args.output / 'inputs.txt').read_text().splitlines()
    # Limit calibration to 0 and 2 seconds; later frames remain holdout tensors.
    for index in (0, 60):
        for person in range(3):
            source = args.diagnostics / ('frame-%04d' % index) / ('person-%d.raw' % person)
            name = 'native-%04d-person-%d' % (index, person)
            target = inputs / (name + '.raw')
            shutil.copy2(source, target)
            calibration.append(str(target.resolve()))
            tensor = np.fromfile(target, np.float32).reshape(1, 320, 320, 3)
            for output, array in zip(session.get_outputs(), session.run(None, {'images': tensor})):
                array.tofile(args.output / (name + '-' + output.name + '-reference.raw'))
            tests.append('inputs/' + name + '.raw')
            manifest.append({'frame': index, 'person_rank': person,
                             'sha256': hashlib.sha256(target.read_bytes()).hexdigest()})
    (args.output / 'inputs.txt').write_text('\n'.join(tests) + '\n')
    (args.output / 'calibration.txt').write_text('\n'.join(calibration) + '\n')
    (args.output / 'native-calibration.json').write_text(json.dumps({
        'added': manifest, 'evaluation_overlap': 'Exclude frames 0 and 60 from clip holdout metrics',
        'limitations': 'Single-clip calibration distribution experiment; no generalization proof.'}, indent=2) + '\n')


if __name__ == '__main__':
    main()
