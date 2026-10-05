#!/usr/bin/env python3
"""Experimental learned human pose plus appearance model; not a ROM port."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys

import cv2
import numpy as np
import onnx
from onnx import compose, helper
import onnxruntime as ort
import torch
from torch import nn
from torch.nn import functional as F

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'locator'))
from locator_contract import preprocess

OSNET_COMMIT = 'f8cd150fdf77e8d9e1ed143b7f308c2c609ded50'
OSNET_HASH = 'cf55163d78fc44c62c82f85ab62d39f10438679b5abe8c698ae08cfa84aa6e18'
POSE_HASH = '213aae5fd54473fec38a2621f9d658a7dbf6808653ac796ad3d0fa6f23c2a2b8'
# Body/hand/foot ordering follows Samsung's skeleton. Mouth assignments are
# an explicitly provisional inference, not recovered donor training labels.
MAPPING = [1, 2, 0, 5, 11, 5, 7, 9, 6, 8, 10, 11, 13, 15, 12, 14, 16,
           71, 77, 3, 4, 17, 20, *range(92, 112), *range(113, 133),
           *range(71, 91), 18, 19, 21, 22]


class Preprocess(nn.Module):
    def __init__(self, size):
        super().__init__()
        self.size = size
        self.register_buffer('mean', torch.tensor([0.485, 0.456, 0.406]).reshape(1, 3, 1, 1))
        self.register_buffer('std', torch.tensor([0.229, 0.224, 0.225]).reshape(1, 3, 1, 1))

    def forward(self, images):
        image = F.interpolate(images.permute(0, 3, 1, 2), size=self.size,
                              mode='bilinear', align_corners=False)
        return (image - self.mean) / self.std


class Appearance(nn.Module):
    def __init__(self, model):
        super().__init__()
        self.preprocess = Preprocess((256, 128))
        self.model = model

    def forward(self, images):
        # Select actual trained head coordinates; no random projection or
        # untrained replacement layer is introduced. Distance calibration TBD.
        features = self.model(self.preprocess(images))[:, :128]
        # Samsung normalizes both vectors inside calculate_cosine_distance.
        # Keep raw head activations to avoid a quantized dynamic division here.
        return features.reshape(1, 128, 1, 1)


class PoseLayout(nn.Module):
    def __init__(self):
        super().__init__()
        self.register_buffer('mapping', torch.tensor(MAPPING, dtype=torch.long))
        self.register_buffer('bins', torch.arange(640).reshape(1, 1, 640).float())

    def distribution(self, raw):
        # Preserve each peak location while keeping native decoder scores <=1.
        raw = raw / raw.amax(dim=2, keepdim=True).clamp(min=1)
        resized = F.interpolate(raw.unsqueeze(2), size=(1, 640), mode='bilinear',
                                align_corners=False).squeeze(2)
        selected = resized.index_select(1, self.mapping)
        virtual = []
        for a, b in ((5, 6), (11, 12)):
            coordinate = (resized[:, a:a + 1].argmax(dim=2, keepdim=True).float() +
                          resized[:, b:b + 1].argmax(dim=2, keepdim=True).float()) / 2
            confidence = torch.minimum(resized[:, a:a + 1].amax(dim=2, keepdim=True),
                                       resized[:, b:b + 1].amax(dim=2, keepdim=True)).clamp(min=0)
            virtual.append(torch.exp(-((self.bins - coordinate) ** 2) / 18) * confidence)
        return torch.cat((selected[:, :3], virtual[0], virtual[1], selected[:, 5:]), dim=1)

    def forward(self, x, y):
        return torch.cat((self.distribution(x), self.distribution(y)), dim=1).unsqueeze(3)


def export(module, inputs, path, names, outputs):
    module.eval()
    with torch.no_grad():
        torch.onnx.export(module, inputs, str(path), opset_version=11,
                          input_names=names, output_names=outputs)
    return onnx.load(path)


def native_pose(raw):
    rows = raw.reshape(174, 640)
    x, y = rows[:87], rows[87:]
    confidence = np.minimum(x.max(axis=1), y.max(axis=1))
    # Pinned decode_rtmcc_image clamps negative scores to 0 and scores >1 to 1.
    confidence = np.clip(confidence, 0, 1)
    return np.column_stack((x.argmax(axis=1) / 2, y.argmax(axis=1) / 2, confidence))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--weights', type=Path, required=True)
    parser.add_argument('--pose', type=Path, required=True)
    parser.add_argument('--samples', type=Path, required=True)
    parser.add_argument('--locator-results', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    commit = subprocess.check_output(['git', '-C', str(args.upstream), 'rev-parse', 'HEAD'], text=True).strip()
    if commit != OSNET_COMMIT or hashlib.sha256(args.weights.read_bytes()).hexdigest() != OSNET_HASH:
        raise ValueError('Unreviewed OSNet source/weights')
    if hashlib.sha256(args.pose.read_bytes()).hexdigest() != POSE_HASH:
        raise ValueError('Unreviewed pose model')
    args.output.mkdir(parents=True, exist_ok=False)
    inputs = args.output / 'inputs'
    inputs.mkdir()
    torch.set_num_threads(4)
    spec = importlib.util.spec_from_file_location('reviewed_osnet', args.upstream / 'torchreid/models/osnet.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    weights = torch.load(args.weights, map_location='cpu', weights_only=True)
    weights = weights.get('state_dict', weights)
    weights = {key.removeprefix('module.'): value for key, value in weights.items()}
    model = module.osnet_x0_25(num_classes=weights['classifier.weight'].shape[0], pretrained=False)
    model.load_state_dict(weights, strict=True)
    appearance = Appearance(model).eval()
    pose_layout = PoseLayout().eval()
    dummy = torch.zeros(1, 320, 320, 3)
    feature_graph = compose.add_prefix(export(appearance, dummy, args.output / 'appearance.onnx',
                                             ['images'], ['feature']), 'appearance/')
    prep = compose.add_prefix(export(Preprocess((256, 192)), dummy, args.output / 'pose-preprocess.onnx',
                                    ['images'], ['pose_input']), 'prep/')
    pose = compose.add_prefix(onnx.load(args.pose), 'pose/')
    for value, shape in [(pose.graph.input[0], [1, 3, 256, 192]),
                         (pose.graph.output[0], [1, 133, 384]), (pose.graph.output[1], [1, 133, 512])]:
        for dimension, number in zip(value.type.tensor_type.shape.dim, shape):
            dimension.ClearField('dim_param')
            dimension.dim_value = number
    post = compose.add_prefix(export(pose_layout, (torch.zeros(1, 133, 384), torch.zeros(1, 133, 512)),
                                    args.output / 'pose-layout.onnx', ['x', 'y'], ['native_pose']), 'post/')
    combined = compose.merge_models(prep, pose, [('prep/pose_input', 'pose/input')])
    combined = compose.merge_models(combined, post, [('pose/simcc_x', 'post/x'), ('pose/simcc_y', 'post/y')])
    combined = compose.merge_models(combined, feature_graph, [])
    renames = {'prep/images': 'images', 'appearance/images': 'images',
               'post/native_pose': 'output_0_pose', 'appearance/feature': 'output_1_feature'}
    for node in combined.graph.node:
        for sequence in (node.input, node.output):
            for index, name in enumerate(sequence):
                sequence[index] = renames.get(name, name)
    combined.graph.ClearField('input')
    combined.graph.input.append(helper.make_tensor_value_info('images', onnx.TensorProto.FLOAT, [1, 320, 320, 3]))
    combined.graph.ClearField('output')
    combined.graph.output.extend([
        helper.make_tensor_value_info('output_1_feature', onnx.TensorProto.FLOAT, [1, 128, 1, 1]),
        helper.make_tensor_value_info('output_0_pose', onnx.TensorProto.FLOAT, [1, 174, 640, 1])])
    combined = onnx.shape_inference.infer_shapes(combined)
    onnx.checker.check_model(combined)
    destination = args.output / 'reid-human.onnx'
    onnx.save(combined, destination)
    session = ort.InferenceSession(str(destination), providers=['CPUExecutionProvider'])
    pose_session = ort.InferenceSession(str(args.pose), providers=['CPUExecutionProvider'])
    detections = json.loads(args.locator_results.read_text())['tests']
    samples = {}
    for entry in detections:
        if entry['image'] not in ('bus', 'zidane'):
            continue
        image = cv2.imread(str(args.samples / (entry['image'] + '.jpg')))
        for index, box in enumerate(entry['detections']):
            x1, y1, x2, y2 = np.asarray(box[:4]).round().astype(int)
            crop = image[max(0, y1):min(image.shape[0], y2), max(0, x1):min(image.shape[1], x2)]
            samples[entry['image'] + '-' + str(index)] = crop
    report = {'upstream_commit': commit, 'weights_sha256': OSNET_HASH, 'pose_sha256': POSE_HASH,
              'mapping': MAPPING, 'mapping_status': 'skeletal ordering recovered; facial assignments provisional',
              'appearance_status': '128-coordinate subset of trained 512D OSNet head; not retrained/calibrated',
              'tests': []}
    vectors = {}
    calibration, input_list = [], []
    for name, crop in samples.items():
        tensor, _ = preprocess(crop)
        for suffix, gain in (('', 1.0), ('-brightness', 0.85)):
            test_name = name + suffix
            image_tensor = (tensor * gain).clip(0, 1).astype(np.float32)
            feature, pose_tensor = session.run(None, {'images': image_tensor})
            with torch.no_grad():
                feature_reference = appearance(torch.from_numpy(image_tensor)).numpy()
                pose_input = Preprocess((256, 192))(torch.from_numpy(image_tensor)).numpy()
                x, y = pose_session.run(None, {'input': pose_input})
                pose_reference = pose_layout(torch.from_numpy(x), torch.from_numpy(y)).numpy()
            np.testing.assert_allclose(feature, feature_reference, atol=5e-5, rtol=1e-4)
            np.testing.assert_allclose(pose_tensor, pose_reference, atol=1e-5, rtol=1e-4)
            if not np.isfinite(feature).all() or np.linalg.norm(feature) < 0.99:
                raise ValueError('Invalid trained appearance output')
            points = native_pose(pose_tensor)
            feature.tofile(args.output / (test_name + '-output_1_feature-reference.raw'))
            pose_tensor.tofile(args.output / (test_name + '-output_0_pose-reference.raw'))
            image_tensor.tofile(inputs / (test_name + '.raw'))
            calibration.append(str((inputs / (test_name + '.raw')).resolve()))
            input_list.append('inputs/' + test_name + '.raw')
            vectors[test_name] = feature.reshape(128)
            active = int(np.count_nonzero(points[:17, 2] >= 0.5))
            display = cv2.cvtColor((image_tensor[0] * 255).astype(np.uint8), cv2.COLOR_RGB2BGR)
            for index, (x, y, score) in enumerate(points[:23]):
                if score >= 0.5:
                    cv2.circle(display, (round(x), round(y)), 3, (40, 210, 60), -1)
                    cv2.putText(display, str(index), (round(x) + 3, round(y)),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.35, (30, 30, 240), 1)
            cv2.imwrite(str(args.output / (test_name + '-host-pose.jpg')), display)
            entry = {'image': test_name, 'body_points_above_half': active,
                     'feature_norm': float(np.linalg.norm(feature)), 'points': points.tolist(),
                     'max_feature_onnx_error': float(np.abs(feature - feature_reference).max()),
                     'max_pose_onnx_error': float(np.abs(pose_tensor - pose_reference).max())}
            report['tests'].append(entry)
            print(test_name, 'active body points', active, flush=True)
    if sum(t['body_points_above_half'] >= 5 for t in report['tests'] if not t['image'].endswith('-brightness')) < 2:
        raise ValueError('No convincing poses on at least two independent crops')
    report['distances'] = {a: {b: float(1 - np.clip(np.dot(vectors[a], vectors[b]) /
                                                  (np.linalg.norm(vectors[a]) * np.linalg.norm(vectors[b])), 0, 1)) for b in vectors}
                           for a in vectors}
    (args.output / 'host-results.json').write_text(json.dumps(report, indent=2) + '\n')
    (args.output / 'calibration.txt').write_text('\n'.join(calibration) + '\n')
    (args.output / 'inputs.txt').write_text('\n'.join(input_list) + '\n')
    (args.output / 'experiment.json').write_text(json.dumps({
        'name': 'UN1CA-ReID-Human-Experimental-v1', 'candidate': 'ReID-human-SM8550-v1.dlc',
        'graph_name': 'float',
        'onnx': destination.name, 'input': 'images', 'remote_name': args.output.name + '-20261003',
        'outputs': {'output_1_feature': [1, 128, 1, 1], 'output_0_pose': [1, 174, 640, 1]},
        'weights_bitwidth': 8, 'enhanced_quantizer': False,
        'max_relative_rmse': 0.2}, indent=2) + '\n')


if __name__ == '__main__':
    main()
