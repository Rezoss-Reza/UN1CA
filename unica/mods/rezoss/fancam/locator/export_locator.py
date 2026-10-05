#!/usr/bin/env python3
"""Export real YOLOv7-tiny person weights into Samsung's Locator tensor layout."""
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

from locator_contract import ANCHORS, OUTPUT_SHAPE, decode, preprocess, restore_boxes

UPSTREAM_COMMIT = 'a207844b1ce82d204ab36d87d496728d3d2348e7'
WEIGHTS_SHA256 = '0fa1423606133c06362ec716029dade1169d5631e83195b75d8efa66d2ee0b77'


class PersonLocator(nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model
        head = model.model[-1]
        if head.nc != 80 or head.na != 3:
            raise ValueError('Expected the original COCO YOLOv7-tiny checkpoint')
        checkpoint_anchors = head.anchor_grid.reshape(3, 3, 2).numpy()
        if tuple(head.stride.tolist()) != (8, 16, 32):
            raise ValueError('Unsupported detector strides')
        for index, stride in enumerate((8, 16, 32)):
            side = 320 // stride
            scale = np.ones((1, 3, side, side, 6), dtype=np.float32)
            scale[..., 2:4] = np.sqrt(checkpoint_anchors[index] / ANCHORS[index])[:, None, None, :]
            self.register_buffer('anchor_scale_' + str(index), torch.from_numpy(scale))
        if hasattr(head, 'ia') and head.forward.__name__ != 'fuseforward':
            raise ValueError('Detection head must have its implicit layers fused first')
        # Keep box/objectness logits and COCO person logits for each anchor.
        indices = torch.tensor([anchor * 85 + item for anchor in range(3) for item in range(6)])
        for index, conv in enumerate(head.m):
            reduced = nn.Conv2d(conv.in_channels, 18, 1)
            with torch.no_grad():
                reduced.weight.copy_(conv.weight[indices])
                reduced.bias.copy_(conv.bias[indices])
            head.m[index] = reduced
        head.nc, head.no, head.export = 1, 6, True
        self.register_buffer('dog_cat', torch.zeros(1, 6300, 2))
        self.register_buffer('other_classes', torch.zeros(1, 6300, 21))

    def forward(self, images):
        heads = self.model(images.permute(0, 3, 1, 2))
        raw = torch.cat([(head.sigmoid() * getattr(self, 'anchor_scale_' + str(index))).reshape(1, -1, 6)
                         for index, head in enumerate(heads)], dim=1)
        # VideoDetection additionally gates the conditional class score at 0.95.
        # Multiplying by objectness here incorrectly suppresses usable people.
        human_score = raw[..., 5:6]
        return torch.cat((raw[..., :5], self.dog_cat, human_score,
                          self.other_classes), dim=2).unsqueeze(3)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--weights', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    commit = subprocess.check_output(['git', '-C', str(args.upstream), 'rev-parse', 'HEAD'], text=True).strip()
    if commit != UPSTREAM_COMMIT:
        raise ValueError('Unreviewed YOLOv7 source revision: ' + commit)
    weights_hash = hashlib.sha256(args.weights.read_bytes()).hexdigest()
    if weights_hash != WEIGHTS_SHA256:
        raise ValueError('Unreviewed pretrained checkpoint hash')
    sys.path.insert(0, str(args.upstream.resolve()))
    from models.experimental import attempt_load

    torch.set_num_threads(4)
    torch.manual_seed(0)
    args.output.mkdir(parents=True, exist_ok=True)
    model = PersonLocator(attempt_load(str(args.weights), map_location='cpu')).eval()
    sample = torch.zeros(1, 320, 320, 3)
    with torch.no_grad():
        assert tuple(model(sample).shape) == OUTPUT_SHAPE
        destination = args.output / 'locator-person.onnx'
        torch.onnx.export(model, sample, str(destination), opset_version=13,
                          input_names=['images'], output_names=['output'],
                          do_constant_folding=True)
    onnx.checker.check_model(str(destination))
    session = ort.InferenceSession(str(destination), providers=['CPUExecutionProvider'])
    session.disable_fallback()
    report = {'upstream_commit': commit,
              'weights_sha256': weights_hash,
              'onnx_sha256': hashlib.sha256(destination.read_bytes()).hexdigest(),
              'input': [1, 320, 320, 3], 'output': list(OUTPUT_SHAPE),
              'human_class': 2, 'tests': []}
    raw_dir = args.output / 'inputs'
    raw_dir.mkdir(exist_ok=True)
    calibration, device_inputs = [], []
    images = {p.stem: cv2.imread(str(p)) for p in sorted((args.upstream / 'inference/images').glob('*.jpg'))}
    images['blank'] = np.full((320, 320, 3), 114, dtype=np.uint8)
    for name, image in images.items():
        tensor, transform = preprocess(image)
        raw = session.run(['output'], {'images': tensor})[0]
        with torch.no_grad():
            expected = model(torch.from_numpy(tensor)).numpy()
        error = float(np.abs(expected - raw).max())
        if error > 1e-4:
            raise ValueError('ONNX/PyTorch mismatch: ' + str(error))
        detections = restore_boxes(decode(raw), transform, image.shape)
        if name in ('bus', 'zidane') and len(detections) < 2:
            raise ValueError('Person detection check failed for ' + name)
        if name == 'blank' and len(detections):
            raise ValueError('Unexpected detection on blank input')
        tensor.tofile(raw_dir / (name + '.raw'))
        raw.tofile(args.output / (name + '-onnx-output.raw'))
        device_inputs.append('inputs/' + name + '.raw')
        for index, gain in enumerate((0.8, 1.0, 1.2)):
            augmented = (tensor * gain).clip(0, 1).astype(np.float32)
            file = raw_dir / (name + '-cal-' + str(index) + '.raw')
            augmented.tofile(file)
            calibration.append(str(file.resolve()))
        annotated = image.copy()
        for x1, y1, x2, y2, confidence in detections:
            cv2.rectangle(annotated, (round(x1), round(y1)), (round(x2), round(y2)), (0, 220, 70), 2)
            cv2.putText(annotated, 'person %.2f' % confidence, (round(x1), max(20, round(y1))),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 220, 70), 2)
        cv2.imwrite(str(args.output / (name + '-detections.jpg')), annotated)
        result = {'image': name, 'detections': detections.tolist(), 'max_onnx_error': error}
        report['tests'].append(result)
        print(name, len(detections), 'persons, ONNX max error', error, flush=True)
    (args.output / 'calibration.txt').write_text('\n'.join(calibration) + '\n')
    (args.output / 'device-inputs.txt').write_text('\n'.join(device_inputs) + '\n')
    (args.output / 'host-results.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
