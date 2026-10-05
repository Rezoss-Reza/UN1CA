#!/usr/bin/env python3
"""Normalize only the learned appearance output for Samsung's dot-product matcher."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

import numpy as np
import onnx
from onnx import helper, numpy_helper
import onnxruntime as ort


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    source = args.source / 'reid-human.onnx'
    model = onnx.load(source)
    output = 'output_1_feature'
    raw_output = 'unit_appearance/raw'
    if any('unit_appearance/' in node.name for node in model.graph.node):
        raise ValueError('Already adapted')
    producers = [node for node in model.graph.node if output in node.output]
    if len(producers) != 1 or any(output in node.input for node in model.graph.node):
        raise ValueError('Unexpected feature-output contract')
    for index, name in enumerate(producers[0].output):
        if name == output:
            producers[0].output[index] = raw_output
    prefix = 'unit_appearance/'
    for name, array in (
        ('flat_shape', np.array([1, 128], np.int64)),
        ('output_shape', np.array([1, 128, 1, 1], np.int64)),
        ('ones', np.ones((128, 1), np.float32)),
        ('epsilon', np.array([[1e-12]], np.float32)),
    ):
        model.graph.initializer.append(numpy_helper.from_array(array, prefix + name))
    def node(kind, inputs, target):
        return helper.make_node(kind, inputs, [prefix + target], name=prefix + target)
    model.graph.node.extend([
        node('Reshape', [raw_output, prefix + 'flat_shape'], 'flat'),
        node('Mul', [prefix + 'flat', prefix + 'flat'], 'squared'),
        node('MatMul', [prefix + 'squared', prefix + 'ones'], 'sum'),
        node('Add', [prefix + 'sum', prefix + 'epsilon'], 'safe_sum'),
        node('Sqrt', [prefix + 'safe_sum'], 'norm'),
        node('Div', [prefix + 'flat', prefix + 'norm'], 'normalized'),
        helper.make_node('Reshape', [prefix + 'normalized', prefix + 'output_shape'], [output], name=prefix + 'output'),
    ])
    onnx.checker.check_model(model)
    args.output.mkdir(parents=True, exist_ok=False)
    onnx.save(model, args.output / 'reid-human.onnx')
    shutil.copytree(args.source / 'inputs', args.output / 'inputs')
    for name in ('inputs.txt', 'calibration.txt'):
        shutil.copy2(args.source / name, args.output / name)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.log_severity_level = 3
    before = ort.InferenceSession(str(source), options, providers=['CPUExecutionProvider'])
    after = ort.InferenceSession(model.SerializeToString(), options, providers=['CPUExecutionProvider'])
    tests = []
    for path in sorted((args.output / 'inputs').glob('*.raw')):
        feed = {'images': np.fromfile(path, np.float32).reshape(1, 320, 320, 3)}
        names = ['output_0_pose', output]
        old_pose, old_feature = before.run(names, feed)
        pose, feature = after.run(names, feed)
        np.testing.assert_array_equal(old_pose, pose)
        expected = old_feature / np.sqrt(np.sum(old_feature.astype(np.float64) ** 2) + 1e-12)
        np.testing.assert_allclose(feature, expected, rtol=1e-5, atol=1e-6)
        norm = float(np.linalg.norm(feature))
        if abs(norm - 1) > 1e-5:
            raise ValueError('Output is not unit-length')
        for name, tensor in zip(names, (pose, feature)):
            tensor.tofile(args.output / (path.stem + '-' + name + '-reference.raw'))
        tests.append({'input': path.stem, 'raw_feature_norm': float(np.linalg.norm(old_feature)),
                      'unit_feature_norm': norm, 'pose_identical': True})
    report = {'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
              'output_sha256': hashlib.sha256((args.output / 'reid-human.onnx').read_bytes()).hexdigest(),
              'tests': tests, 'status': 'host-contract-validated-device-and-gallery-pending'}
    (args.output / 'unit-contract.json').write_text(json.dumps(report, indent=2) + '\n')
    print('Unit appearance contract:', len(tests), 'inputs; pose unchanged')


if __name__ == '__main__':
    main()
