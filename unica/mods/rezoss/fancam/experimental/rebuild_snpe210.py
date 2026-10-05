#!/usr/bin/env python3
"""Rebuild saved real ONNX models with SNPE 2.10 in fresh isolated folders."""
import argparse
import copy
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--python36', type=Path, required=True)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--destination', type=Path, required=True)
    parser.add_argument('--model', choices=('locator', 'reid', 'humanpet'), required=True)
    parser.add_argument('--runtime-libs', type=Path, required=True)
    parser.add_argument('--no-axis-quant', action='store_true')
    parser.add_argument('--activation-bitwidth', choices=(8, 16), type=int, default=16)
    parser.add_argument('--mixed-div8', action='store_true')
    parser.add_argument('--lower-constant-div', action='store_true')
    parser.add_argument('--unshare-conv-weights', action='store_true')
    parser.add_argument('--lower-dynamic-div', action='store_true')
    parser.add_argument('--pose-output-reshape', action='store_true')
    parser.add_argument('--pose-rank4-concat', action='store_true')
    parser.add_argument('--enhanced-quant', action='store_true')
    parser.add_argument('--weights-bitwidth', choices=(8, 16), type=int, default=8)
    parser.add_argument('--adjusted-weights', action='store_true')
    parser.add_argument('--reciprocal-refinement', action='store_true')
    args = parser.parse_args()
    if args.reciprocal_refinement and not args.lower_dynamic_div:
        parser.error('--reciprocal-refinement requires --lower-dynamic-div')
    sdk, source, dest = args.sdk.resolve(), args.source.resolve(), args.destination.resolve()
    tools = sdk / 'bin/x86_64-linux-clang'
    env = dict(os.environ, PYTHONPATH=str(sdk / 'lib/python'),
               LD_LIBRARY_PATH=str(sdk / 'lib/x86_64-linux-clang') + ':' + str(args.python36.resolve().parent.parent / 'lib') + ':' + str(args.runtime_libs.resolve()),
               PATH=str(tools) + ':' + os.environ.get('PATH', ''), SNPE_ROOT=str(sdk),
               PYTHONDONTWRITEBYTECODE='1')
    version = subprocess.check_output([str(tools / 'snpe-dlc-graph-prepare'), '--version'], env=env, text=True).strip()
    if not version.startswith('SNPE v2.10.'):
        raise ValueError('Expected SNPE 2.10, got ' + version)
    onnx = {'locator': 'locator-person.onnx', 'reid': 'reid-human.onnx',
            'humanpet': 'humanpet.onnx'}[args.model]
    outputs = {'output': [1, 6300, 29, 1]} if args.model != 'reid' else {
        'output_1_feature': [1, 128, 1, 1], 'output_0_pose': [1, 174, 640, 1]}
    for name in (onnx, 'calibration.txt', 'inputs'):
        if not (source / name).exists():
            raise FileNotFoundError(source / name)
    dest.mkdir(parents=True, exist_ok=False)
    shutil.copy2(source / onnx, dest / onnx)
    shutil.copytree(source / 'inputs', dest / 'inputs')
    for pattern in ('*-reference.raw', '*-onnx-output.raw', 'host-results.json', 'experiment.json'):
        for item in source.glob(pattern):
            shutil.copy2(item, dest / item.name)
    calibration = [str(dest / 'inputs' / Path(line.strip().split(':=')[-1]).name)
                   for line in (source / 'calibration.txt').read_text().splitlines() if line.strip()]
    if not all(Path(line).is_file() for line in calibration):
        raise ValueError('Missing calibration inputs')
    (dest / 'calibration.txt').write_text('\n'.join(calibration) + '\n')
    inputs = source / ('device-inputs.txt' if args.model == 'locator' else 'inputs.txt')
    shutil.copy2(inputs, dest / 'inputs.txt')
    entries = [('images', 'NHWC')]
    (dest / 'custom-io.yaml').write_text(''.join(
        '- IOName: ' + name + '\n  Layout:\n    Model: ' + layout + '\n    Custom: ' + layout + '\n'
        for name, layout in entries))
    report = {'model': args.model, 'sdk_version': version, 'source_onnx_sha256': hashlib.sha256((dest / onnx).read_bytes()).hexdigest(),
              'outputs': outputs, 'stages': [], 'status': 'building', 'rom_payload_changed': False,
              'quantization': {'weights_bitwidth': args.weights_bitwidth, 'activation_bitwidth': args.activation_bitwidth,
                               'bias_bitwidth': 32, 'axis_quant': not args.no_axis_quant,
                               'enhanced': args.model == 'locator' or args.enhanced_quant,
                               'adjusted_weights': args.adjusted_weights}}
    # The legacy converter has no HardSigmoid translation. Lower it exactly,
    # then prove the adapted float graph against the original on every input.
    import numpy as np
    import onnx as ox
    import onnxruntime as ort
    model = ox.load(str(dest / onnx))
    nodes, lowered = [], 0
    for node in model.graph.node:
        if node.op_type != 'HardSigmoid':
            nodes.append(node)
            continue
        attributes = {attribute.name: ox.helper.get_attribute_value(attribute) for attribute in node.attribute}
        prefix = 'legacy_hardsigmoid_' + str(lowered)
        for suffix, value in (('alpha', attributes.get('alpha', 0.2)), ('beta', attributes.get('beta', 0.5)), ('lo', 0), ('hi', 1)):
            model.graph.initializer.append(ox.numpy_helper.from_array(np.array(value, dtype=np.float32), prefix + '_' + suffix))
        nodes.extend([
            ox.helper.make_node('Mul', [node.input[0], prefix + '_alpha'], [prefix + '_mul'], name=prefix + '_mul'),
            ox.helper.make_node('Add', [prefix + '_mul', prefix + '_beta'], [prefix + '_add'], name=prefix + '_add'),
            ox.helper.make_node('Clip', [prefix + '_add', prefix + '_lo', prefix + '_hi'], list(node.output), name=prefix + '_clip')])
        lowered += 1
    constant_divs = 0
    if args.lower_constant_div:
        constants = {value.name: ox.numpy_helper.to_array(value) for value in model.graph.initializer}
        for node in nodes:
            if node.op_type == 'Constant':
                attributes = {a.name: ox.helper.get_attribute_value(a) for a in node.attribute}
                if 'value' in attributes:
                    constants[node.output[0]] = ox.numpy_helper.to_array(attributes['value'])
        adapted = []
        for node in nodes:
            denominator = constants.get(node.input[1]) if node.op_type == 'Div' else None
            if denominator is None:
                adapted.append(node)
                continue
            if not np.isfinite(denominator).all() or (denominator == 0).any():
                raise ValueError('Unsafe constant denominator: ' + node.name)
            name = 'legacy_div_reciprocal_' + str(constant_divs)
            reciprocal = np.reciprocal(denominator).astype(np.float32)
            model.graph.initializer.append(ox.numpy_helper.from_array(reciprocal, name))
            adapted.append(ox.helper.make_node('Mul', [node.input[0], name], list(node.output), name=node.name + '_reciprocal_mul'))
            constant_divs += 1
        nodes = adapted
    cloned_weights = 0
    if args.unshare_conv_weights:
        constants = {value.name: value for value in model.graph.initializer}
        uses = Counter(name for node in nodes if node.op_type == 'Conv' for name in node.input[1:])
        for node in nodes:
            if node.op_type != 'Conv':
                continue
            for index, name in enumerate(node.input[1:], 1):
                if uses[name] > 1 and name in constants:
                    cloned = copy.deepcopy(constants[name])
                    cloned.name = 'legacy_conv_parameter_' + str(cloned_weights)
                    model.graph.initializer.append(cloned)
                    node.input[index] = cloned.name
                    cloned_weights += 1
    dynamic_divs = 0
    if args.lower_dynamic_div:
        adapted = []
        for node in nodes:
            if node.op_type != 'Div':
                adapted.append(node)
                continue
            prefix = 'legacy_dynamic_reciprocal_' + str(dynamic_divs)
            model.graph.initializer.append(ox.numpy_helper.from_array(np.array(-1, np.float32), prefix + '_exponent'))
            reciprocal = prefix
            adapted.append(ox.helper.make_node('Pow', [node.input[1], prefix + '_exponent'], [reciprocal], name=prefix))
            if args.reciprocal_refinement:
                model.graph.initializer.append(ox.numpy_helper.from_array(np.array(2, np.float32), prefix + '_two'))
                # Newton refinement corrects reciprocal-kernel approximation;
                # the float equivalence check below still applies to every input.
                for step in range(2):
                    name = prefix + '_refine_' + str(step)
                    adapted.extend([
                        ox.helper.make_node('Mul', [node.input[1], reciprocal], [name + '_product'], name=name + '_product'),
                        ox.helper.make_node('Neg', [name + '_product'], [name + '_negative'], name=name + '_negative'),
                        ox.helper.make_node('Add', [prefix + '_two', name + '_negative'], [name + '_correction'], name=name + '_correction'),
                        ox.helper.make_node('Mul', [reciprocal, name + '_correction'], [name], name=name)])
                    reciprocal = name
            adapted.append(ox.helper.make_node('Mul', [node.input[0], reciprocal], list(node.output), name=node.name + '_reciprocal_mul'))
            dynamic_divs += 1
        nodes = adapted
    pose_reshapes = 0
    if args.pose_output_reshape:
        adapted = []
        for node in nodes:
            if node.op_type == 'Unsqueeze' and list(node.output) == ['output_0_pose']:
                name = 'legacy_pose_output_shape'
                model.graph.initializer.append(ox.numpy_helper.from_array(np.array([1, 174, 640, 1], np.int64), name))
                adapted.append(ox.helper.make_node('Reshape', [node.input[0], name], list(node.output), name='legacy_pose_output_reshape'))
                pose_reshapes += 1
            else:
                adapted.append(node)
        if pose_reshapes != 1:
            raise ValueError('Expected exactly one final pose Unsqueeze')
        nodes = adapted
    rank4_concats = 0
    if args.pose_rank4_concat:
        terminal = next(node for node in nodes if list(node.output) == ['output_0_pose'])
        if terminal.op_type not in ('Unsqueeze', 'Reshape'):
            raise ValueError('Unexpected pose terminal operator')
        concat = next(node for node in nodes if terminal.input[0] in node.output)
        if concat.op_type != 'Concat':
            raise ValueError('Expected terminal pose concat')
        adapted = []
        for node in nodes:
            if node is terminal:
                continue
            if node is concat:
                inputs = []
                for index, name in enumerate(node.input):
                    output = 'legacy_rank4_pose_part_' + str(index)
                    adapted.append(ox.helper.make_node('Unsqueeze', [name], [output], axes=[3], name=output))
                    inputs.append(output)
                adapted.append(ox.helper.make_node('Concat', inputs, ['output_0_pose'], axis=1, name='legacy_rank4_pose_concat'))
                rank4_concats += 1
            else:
                adapted.append(node)
        nodes = adapted
    if lowered or constant_divs or cloned_weights or dynamic_divs or pose_reshapes or rank4_concats:
        del model.graph.node[:]
        model.graph.node.extend(nodes)
        ox.checker.check_model(model)
        ox.save(model, str(dest / onnx))
        options = ort.SessionOptions()
        options.log_severity_level = 3
        old = ort.InferenceSession(str(source / onnx), options, providers=['CPUExecutionProvider'])
        new = ort.InferenceSession(str(dest / onnx), options, providers=['CPUExecutionProvider'])
        max_error = 0.0
        for raw in (dest / 'inputs').glob('*.raw'):
            feed = {'images': np.fromfile(raw, np.float32).reshape(1, 320, 320, 3)}
            for a, b in zip(old.run(list(outputs), feed), new.run(list(outputs), feed)):
                np.testing.assert_allclose(a, b, atol=0.00005, rtol=0.0001)
                max_error = max(max_error, float(np.max(np.abs(a - b))))
        report.update(lowered_hardsigmoid=lowered, lowered_constant_div=constant_divs,
                      cloned_shared_conv_parameters=cloned_weights,
                      lowered_dynamic_div=dynamic_divs,
                      pose_output_reshapes=pose_reshapes,
                      pose_rank4_concats=rank4_concats,
                      reciprocal_refinement=args.reciprocal_refinement,
                      adapted_float_max_abs_error=max_error)
    if args.mixed_div8:
        constants = {value.name for value in model.graph.initializer}
        constants.update(name for node in model.graph.node if node.op_type == 'Constant' for name in node.output)
        selected = sorted({name for node in model.graph.node if node.op_type == 'Div' and node.input[1] not in constants
                           for name in (*node.input, *node.output) if name not in constants})
        observed = ox.shape_inference.infer_shapes(model)
        shapes = {value.name: value for value in (*observed.graph.input, *observed.graph.value_info, *observed.graph.output)}
        del observed.graph.output[:]
        for name in selected:
            observed.graph.output.append(shapes.get(name, ox.helper.make_tensor_value_info(name, ox.TensorProto.FLOAT, None)))
        session = ort.InferenceSession(observed.SerializeToString(), providers=['CPUExecutionProvider'])
        limits = {name: [0.0, 0.0] for name in selected}
        for raw in calibration:
            results = session.run(selected, {'images': np.fromfile(raw, np.float32).reshape(1, 320, 320, 3)})
            for name, array in zip(selected, results):
                limits[name][0] = min(limits[name][0], float(array.min()))
                limits[name][1] = max(limits[name][1], float(array.max()))
        encodings = {}
        for name, (minimum, maximum) in limits.items():
            scale = max((maximum - minimum) / 255, 1e-8)
            offset = int(round(minimum / scale))
            encodings[name] = [{'bitwidth': 8, 'dtype': 'int', 'is_symmetric': 'False',
                                'min': offset * scale, 'max': (offset + 255) * scale,
                                'scale': scale, 'offset': offset}]
        (dest / 'mixed.encodings').write_text(json.dumps({'version': '0.6.1', 'activation_encodings': encodings, 'param_encodings': {}}, indent=2) + '\n')
        report['mixed_div8_tensors'] = selected

    def run(stage, command):
        result = subprocess.run(list(map(str, command)), env=env, cwd=dest, capture_output=True, text=True, timeout=900)
        (dest / (stage + '.log')).write_text(result.stdout + result.stderr)
        report['stages'].append({'stage': stage, 'command': list(map(str, command)), 'exit': result.returncode})
        report['status'] = 'failed' if result.returncode else stage
        (dest / 'build-results.json').write_text(json.dumps(report, indent=2) + '\n')
        if result.returncode:
            raise RuntimeError(stage + ': ' + (result.stdout + result.stderr)[-4000:])
        print(stage + ' passed', flush=True)

    command = [args.python36, tools / 'snpe-onnx-to-dlc', '-i', dest / onnx, '-o', dest / 'float.dlc',
               '--input_layout', 'images', 'NHWC', '--custom_io', dest / 'custom-io.yaml',
               '--model_version', 'UN1CA-' + args.model + '-SM8550-SNPE210-experimental']
    for name in outputs:
        command += ['--out_name', name]
    if args.mixed_div8:
        command += ['--quantization_overrides', dest / 'mixed.encodings']
    run('convert', command)
    command = [tools / 'snpe-dlc-quantize', '--input_dlc', dest / 'float.dlc', '--output_dlc', dest / 'quantized.dlc',
               '--input_list', dest / 'calibration.txt', '--act_bitwidth', str(args.activation_bitwidth), '--weights_bitwidth', str(args.weights_bitwidth),
               '--bias_bitwidth', '32']
    if not args.no_axis_quant:
        command += ['--axis_quant']
    if args.model == 'locator' or args.enhanced_quant:
        command += ['--use_enhanced_quantizer']
    if args.adjusted_weights:
        command += ['--use_adjusted_weights_quantizer']
    if args.mixed_div8:
        command += ['--override_params']
    run('quantize', command)
    if not (dest / 'quantized.dlc').is_file():
        report['status'] = 'quantizer-failed-with-zero-exit'
        (dest / 'build-results.json').write_text(json.dumps(report, indent=2) + '\n')
        raise RuntimeError('Quantizer returned success but produced no DLC; inspect quantize.log')
    candidate = dest / ('UN1CA-' + args.model + '-SM8550-SNPE210-experimental.dlc')
    run('prepare', [tools / 'snpe-dlc-graph-prepare', '--input_dlc', dest / 'quantized.dlc',
                    '--output_dlc', candidate, '--htp_socs', 'sm8550', '--set_output_tensors', ','.join(outputs)])
    run('inspect', [args.python36, tools / 'snpe-dlc-info', '-i', candidate])
    report.update(status='built-not-device-validated', candidate_sha256=hashlib.sha256(candidate.read_bytes()).hexdigest())
    (dest / 'build-results.json').write_text(json.dumps(report, indent=2) + '\n')
    print('No ROM replacement performed. Validate against installed SNPE and SNAP next.')


if __name__ == '__main__':
    main()
