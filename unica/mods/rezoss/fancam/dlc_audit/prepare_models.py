#!/usr/bin/env python3
"""Prepare real-image tests and a pretrained human/pet detector replacement."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

import cv2
import numpy as np
import onnx
import onnxruntime as ort
import torch
from torch import nn

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'locator'))
from export_locator import UPSTREAM_COMMIT, WEIGHTS_SHA256
from locator_contract import ANCHORS, OUTPUT_SHAPE, STRIDES, decode, preprocess

COCO_CLASSES = (16, 15, 0)
CATEGORIES = ('dog', 'cat', 'human')
DOCSEGMENT_SHA256 = '5842aae4b7cc0036b613a5a476358c2cd74eaf34604ffa4b84edd69752abd056'


def decode_categories(output):
    rows = output.reshape(OUTPUT_SHAPE).copy()
    winners = rows[..., 5:8, :].argmax(axis=2)
    results = []
    for category in range(3):
        selected = rows.copy()
        score = selected[:, :, 5 + category, :].copy()
        selected[:, :, 5:, :] = 0
        selected[:, :, 7, :] = score
        selected[:, :, 4, :] *= winners == category
        boxes = decode(selected)
        results.extend([{'class': category, 'name': CATEGORIES[category],
                         'score': float(box[4]), 'xyxy': box[:4].tolist()} for box in boxes])
    return sorted(results, key=lambda box: box['score'], reverse=True)


class HumanPetDetector(nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model
        head = model.model[-1]
        if head.nc != 80 or head.na != 3 or tuple(head.stride.tolist()) != STRIDES:
            raise ValueError('Unexpected pretrained detection head')
        if hasattr(head, 'ia') and head.forward.__name__ != 'fuseforward':
            raise ValueError('Implicit head layers must be fused')
        checkpoint_anchors = head.anchor_grid.reshape(3, 3, 2).numpy()
        for index, stride in enumerate(STRIDES):
            side = 320 // stride
            scale = np.ones((1, 3, side, side, 8), np.float32)
            scale[..., 2:4] = np.sqrt(checkpoint_anchors[index] / ANCHORS[index])[:, None, None, :]
            self.register_buffer('scale_' + str(index), torch.from_numpy(scale))
        indices = torch.tensor([anchor * 85 + item for anchor in range(3)
                                for item in (*range(5), *(5 + cls for cls in COCO_CLASSES))])
        for index, convolution in enumerate(head.m):
            reduced = nn.Conv2d(convolution.in_channels, 24, 1)
            with torch.no_grad():
                reduced.weight.copy_(convolution.weight[indices])
                reduced.bias.copy_(convolution.bias[indices])
            head.m[index] = reduced
        head.nc, head.no, head.export = 3, 8, True
        self.register_buffer('inactive', torch.zeros(1, 6300, 21))

    def forward(self, images):
        heads = self.model(images.permute(0, 3, 1, 2))
        raw = torch.cat([(head.sigmoid() * getattr(self, 'scale_' + str(index))).reshape(1, -1, 8)
                         for index, head in enumerate(heads)], dim=1)
        return torch.cat((raw[..., :5], raw[..., 5:8] * raw[..., 4:5],
                          self.inactive), dim=2).unsqueeze(3)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--kind', choices=('docsegment', 'humanpet'), required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--samples', type=Path, required=True)
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--weights', type=Path)
    parser.add_argument('--source-dlc', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    inputs = args.output / 'inputs'
    inputs.mkdir()
    images = {p.stem: cv2.imread(str(p)) for p in sorted((args.upstream / 'inference/images').glob('*.jpg'))}
    images.update({p.stem: cv2.imread(str(p)) for p in sorted(args.samples.iterdir()) if p.suffix in ('.jpg', '.png')})
    images['blank'] = np.full((320, 320, 3), 114, np.uint8)
    config = {'name': 'UN1CA-' + args.kind + '-experiment-v1',
              'candidate': ('DocSegment' if args.kind == 'docsegment' else 'HumanPetDetection') + '-SM8550-v1.dlc',
              'remote_name': args.output.name + '-20261003', 'max_relative_rmse': 0.15}
    report = {'kind': args.kind, 'tests': []}
    if args.kind == 'docsegment':
        if args.source_dlc is None:
            parser.error('--source-dlc is required for docsegment')
        config.update({'source_dlc': str(args.source_dlc.resolve()), 'input': 'data',
                       'graph_name': 'SS_segmenter_cnn_unquantized',
                       'outputs': {'output_1': [1, 224, 224, 12], 'output_2': [1, 14, 14, 12],
                                   'output_3': [1, 14, 14, 1], 'output_4': [1, 1, 1, 2]}})
        report['source_sha256'] = hashlib.sha256(args.source_dlc.read_bytes()).hexdigest()
        if report['source_sha256'] != DOCSEGMENT_SHA256:
            raise ValueError('Unreviewed document model')
        config['source_sha256'] = report['source_sha256']
        # The donor input encoding spans approximately [-127.5,126.5]. Consumer
        # channel ordering and class semantics still need native verification.
        report['preprocessing_status'] = 'RGB minus 127.5 numeric test; native preprocessing unverified'
        images = {name: images[name] for name in ('sudoku', 'bus', 'blank')}
    else:
        commit = subprocess.check_output(['git', '-C', str(args.upstream), 'rev-parse', 'HEAD'], text=True).strip()
        if commit != UPSTREAM_COMMIT or args.weights is None:
            raise ValueError('Missing weights or unreviewed upstream revision')
        weight_hash = hashlib.sha256(args.weights.read_bytes()).hexdigest()
        if weight_hash != WEIGHTS_SHA256:
            raise ValueError('Unreviewed pretrained weights')
        report.update({'upstream_commit': commit, 'weights_sha256': weight_hash, 'class_mapping': CATEGORIES})
        sys.path.insert(0, str(args.upstream.resolve()))
        from models.experimental import attempt_load
        from utils.general import non_max_suppression
        torch.set_num_threads(4)
        model = HumanPetDetector(attempt_load(str(args.weights), map_location='cpu')).eval()
        original = attempt_load(str(args.weights), map_location='cpu').eval()
        destination = args.output / 'humanpet.onnx'
        torch.onnx.export(model, torch.zeros(1, 320, 320, 3), str(destination),
                          opset_version=13, input_names=['images'], output_names=['output'])
        onnx.checker.check_model(str(destination))
        session = ort.InferenceSession(str(destination), providers=['CPUExecutionProvider'])
        config.update({'onnx': destination.name, 'input': 'images', 'outputs': {'output': list(OUTPUT_SHAPE)}})
    calibration, input_list = [], []
    for name, image in images.items():
        if image is None:
            raise ValueError('Unreadable sample: ' + name)
        if args.kind == 'docsegment':
            tensor = cv2.cvtColor(cv2.resize(image, (224, 224)), cv2.COLOR_BGR2RGB).astype(np.float32)[None] - 127.5
            entry = {'image': name}
        else:
            tensor, _ = preprocess(image)
            raw = session.run(['output'], {'images': tensor})[0]
            with torch.no_grad():
                expected = model(torch.from_numpy(tensor)).numpy()
                prediction = original(torch.from_numpy(tensor).permute(0, 3, 1, 2))[0]
            np.testing.assert_allclose(raw, expected, atol=0.00001, rtol=0.00001)
            original_raw = prediction.numpy()[0]
            for category, coco in enumerate(COCO_CLASSES):
                np.testing.assert_allclose(raw[0, :, 5 + category, 0],
                                           original_raw[:, 4] * original_raw[:, 5 + coco],
                                           atol=0.00001, rtol=0.00001)
            if np.count_nonzero(raw[:, :, 8:, :]):
                raise ValueError('Unsupported category has nonzero score')
            detections = decode_categories(raw)
            upstream = non_max_suppression(prediction, conf_thres=0.5, iou_thres=0.5,
                                           classes=list(COCO_CLASSES))[0].numpy()
            if len(detections) != len(upstream):
                raise ValueError('Upstream detection count mismatch: ' + name)
            for box, reference in zip(detections, upstream):
                if COCO_CLASSES[box['class']] != int(reference[5]):
                    raise ValueError('Upstream class mismatch')
                np.testing.assert_allclose([*box['xyxy'], box['score']], reference[:5],
                                           atol=0.001, rtol=0.00001)
            if name in ('cats', 'image3') and not any(b['name'] == ('cat' if name == 'cats' else 'dog') for b in detections):
                raise ValueError('No real pet detected: ' + name)
            if name in ('horses', 'blank') and detections:
                raise ValueError('False positive negative sample: ' + name)
            raw.tofile(args.output / (name + '-output-reference.raw'))
            entry = {'image': name, 'boxes': detections,
                     'max_onnx_error': float(np.abs(raw - expected).max())}
        tensor.tofile(inputs / (name + '.raw'))
        input_list.append('inputs/' + name + '.raw')
        if args.kind == 'humanpet':
            for index, gain in enumerate((0.8, 1.0, 1.2)):
                path = inputs / (name + '-cal-' + str(index) + '.raw')
                (tensor * gain).clip(0, 1).astype(np.float32).tofile(path)
                calibration.append(str(path.resolve()))
        report['tests'].append(entry)
        print(entry, flush=True)
    (args.output / 'calibration.txt').write_text('\n'.join(calibration) + '\n')
    (args.output / 'inputs.txt').write_text('\n'.join(input_list) + '\n')
    (args.output / 'experiment.json').write_text(json.dumps(config, indent=2) + '\n')
    (args.output / 'host-results.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
