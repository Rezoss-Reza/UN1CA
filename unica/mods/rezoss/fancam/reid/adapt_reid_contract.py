#!/usr/bin/env python3
"""Create an isolated ReID experiment with fitted appearance and exact reductions."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import shutil

import numpy as np
import onnx
from onnx import compose, helper, numpy_helper
import onnxruntime as ort


def main():
    parser = argparse.ArgumentParser()
    for name in ('source', 'appearance', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--norm-matmul', action='store_true')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    model = onnx.shape_inference.infer_shapes(onnx.load(args.source / 'reid-human.onnx'))
    shapes = {v.name: [d.dim_value for d in v.type.tensor_type.shape.dim] for v in
              (*model.graph.input, *model.graph.value_info, *model.graph.output)}
    converted = []
    if args.norm_matmul:
        observed = copy.deepcopy(model)
        names = [n.input[0] for n in model.graph.node if n.name.startswith('pose/') and n.op_type == 'ReduceSum']
        del observed.graph.output[:]
        observed.graph.output.extend(helper.make_tensor_value_info(name, onnx.TensorProto.FLOAT, None) for name in names)
        options = ort.SessionOptions()
        options.intra_op_num_threads = 4
        options.log_severity_level = 3
        session = ort.InferenceSession(observed.SerializeToString(), options, providers=['CPUExecutionProvider'])
        sample = next((args.source / 'inputs').glob('*.raw'))
        values = session.run(names, {'images': np.fromfile(sample, np.float32).reshape(1, 320, 320, 3)})
        shapes.update({name: list(value.shape) for name, value in zip(names, values)})
        nodes = []
        for node in model.graph.node:
            attributes = {a.name: helper.get_attribute_value(a) for a in node.attribute}
            if node.name.startswith('pose/') and node.op_type == 'ReduceSum':
                shape = shapes[node.input[0]]
                if len(shape) != 3 or attributes.get('axes') != [2] or attributes.get('keepdims', 1) != 1:
                    raise ValueError('Unexpected reduction contract')
                name = 'exact_norm_sum_' + str(len(converted))
                model.graph.initializer.append(numpy_helper.from_array(np.ones((shape[-1], 1), np.float32), name))
                nodes.append(helper.make_node('MatMul', [node.input[0], name], list(node.output), name=name + '_matmul'))
                converted.append(node.name)
            else:
                nodes.append(copy.deepcopy(node))
        if len(converted) != 2:
            raise ValueError('Expected two RMS norm reductions')
        del model.graph.node[:]
        model.graph.node.extend(nodes)
    # Remove only the feature-output ancestry. The shared images input remains.
    producer = {out: node for node in model.graph.node for out in node.output}
    removable, pending = set(), ['output_1_feature']
    while pending:
        tensor = pending.pop()
        node = producer.get(tensor)
        if node is None or node.name in removable:
            continue
        removable.add(node.name)
        pending.extend(node.input)
    nodes = [copy.deepcopy(node) for node in model.graph.node if node.name not in removable]
    del model.graph.node[:]
    model.graph.node.extend(nodes)
    used = {name for node in nodes for name in node.input}
    initializers = [copy.deepcopy(v) for v in model.graph.initializer if v.name in used]
    del model.graph.initializer[:]
    model.graph.initializer.extend(initializers)
    pose_output = copy.deepcopy(next(v for v in model.graph.output if v.name == 'output_0_pose'))
    del model.graph.output[:]
    model.graph.output.append(pose_output)
    model.graph.ClearField('value_info')
    # Keep earlier lowering constants distinct from this rebuild's generated names.
    model = compose.add_prefix(model, 'baseline/')
    restored = {'baseline/images': 'images', 'baseline/output_0_pose': 'output_0_pose'}
    for node in model.graph.node:
        for sequence in (node.input, node.output):
            for i, name in enumerate(sequence):
                sequence[i] = restored.get(name, name)
    for value in (*model.graph.input, *model.graph.output):
        value.name = restored.get(value.name, value.name)
    appearance = compose.add_prefix(onnx.load(args.appearance), 'fitted/')
    if model.opset_import[0].version != appearance.opset_import[0].version:
        raise ValueError('Opset mismatch')
    # Both branches consume the same native RGB NHWC input.
    for node in appearance.graph.node:
        for sequence in (node.input, node.output):
            for i, name in enumerate(sequence):
                if name == 'fitted/images': sequence[i] = 'images'
                if name == 'fitted/output_1_feature': sequence[i] = 'output_1_feature'
    model.graph.node.extend(appearance.graph.node)
    model.graph.initializer.extend(appearance.graph.initializer)
    model.graph.output.append(helper.make_tensor_value_info('output_1_feature', onnx.TensorProto.FLOAT, [1, 128, 1, 1]))
    onnx.checker.check_model(model)
    onnx.save(model, args.output / 'reid-human.onnx')
    shutil.copytree(args.source / 'inputs', args.output / 'inputs')
    for name in ('inputs.txt', 'calibration.txt'):
        shutil.copy2(args.source / name, args.output / name)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.log_severity_level = 3
    old = ort.InferenceSession(str(args.source / 'reid-human.onnx'), options, providers=['CPUExecutionProvider'])
    new = ort.InferenceSession(model.SerializeToString(), options, providers=['CPUExecutionProvider'])
    appearance_session = ort.InferenceSession(str(args.appearance), options, providers=['CPUExecutionProvider'])
    maximum = 0.0
    for raw in sorted((args.output / 'inputs').glob('*.raw')):
        feed = {'images': np.fromfile(raw, np.float32).reshape(1, 320, 320, 3)}
        reference = old.run(['output_0_pose'], feed)[0]
        values = dict(zip([v.name for v in new.get_outputs()], new.run(None, feed)))
        maximum = max(maximum, float(np.max(np.abs(reference - values['output_0_pose']))))
        np.testing.assert_allclose(reference, values['output_0_pose'], atol=1e-4, rtol=1e-4)
        np.testing.assert_allclose(appearance_session.run(None, feed)[0], values['output_1_feature'], atol=1e-5, rtol=1e-5)
        for name, value in values.items():
            value.tofile(args.output / (raw.stem + '-' + name + '-reference.raw'))
    report = {'reductions_replaced': converted, 'float_pose_max_abs_error': maximum,
              'source_onnx_sha256': hashlib.sha256((args.source / 'reid-human.onnx').read_bytes()).hexdigest(),
              'appearance_onnx_sha256': hashlib.sha256(args.appearance.read_bytes()).hexdigest(),
              'status': 'float-equivalent-experiment-not-runtime-validated'}
    (args.output / 'adaptation.json').write_text(json.dumps(report, indent=2) + '\n')
    print(report)


if __name__ == '__main__':
    main()
