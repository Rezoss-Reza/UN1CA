#!/usr/bin/env python3
"""Check every anchor prediction against the original pretrained detector."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

import cv2
import numpy as np
import torch

from export_locator import UPSTREAM_COMMIT, WEIGHTS_SHA256
from locator_contract import ANCHORS, INPUT_SIZE, OUTPUT_SHAPE, STRIDES, decode, preprocess

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--weights', type=Path, required=True)
    parser.add_argument('--model-dir', type=Path, required=True)
    args = parser.parse_args()
    commit = subprocess.check_output(['git', '-C', str(args.upstream), 'rev-parse', 'HEAD'], text=True).strip()
    if commit != UPSTREAM_COMMIT or hashlib.sha256(args.weights.read_bytes()).hexdigest() != WEIGHTS_SHA256:
        raise ValueError('Reference source/checkpoint hash mismatch')
    sys.path.insert(0, str(args.upstream.resolve()))
    from models.experimental import attempt_load
    from utils.general import non_max_suppression

    torch.set_num_threads(4)
    model = attempt_load(str(args.weights), map_location='cpu').eval()
    results = []
    names = [Path(line).stem for line in (args.model_dir / 'device-inputs.txt').read_text().splitlines()]
    for name in names:
        image = (np.full((320, 320, 3), 114, np.uint8) if name == 'blank'
                 else cv2.imread(str(args.upstream / 'inference/images' / (name + '.jpg'))))
        tensor, _ = preprocess(image)
        with torch.no_grad():
            prediction = model(torch.from_numpy(tensor).permute(0, 3, 1, 2))[0]
        original = prediction.numpy()[0]
        raw = np.fromfile(args.model_dir / (name + '-onnx-output.raw'), np.float32).reshape(OUTPUT_SHAPE)[0, :, :, 0]
        rebuilt = []
        offset = 0
        for anchors, stride in zip(ANCHORS, STRIDES):
            side = INPUT_SIZE // stride
            count = 3 * side * side
            head = raw[offset:offset + count].reshape(3, side, side, 29)
            y, x = np.meshgrid(np.arange(side), np.arange(side), indexing='ij')
            centers = (head[..., :2] * 2 - 0.5 + np.stack((x, y), axis=-1)) * stride
            sizes = head[..., 2:4] ** 2 * 4 * anchors[:, None, None, :]
            rebuilt.append(np.concatenate((centers, sizes), axis=-1).reshape(-1, 4))
            offset += count
        recovered = np.concatenate(rebuilt)
        np.testing.assert_allclose(recovered, original[:, :4], atol=0.0005, rtol=0.00001)
        np.testing.assert_allclose(raw[:, 4], original[:, 4], atol=0.00001, rtol=0.00001)
        np.testing.assert_allclose(raw[:, 7], original[:, 5], atol=0.00001, rtol=0.00001)
        inactive = np.delete(raw[:, 5:], 2, axis=1)
        assert np.count_nonzero(inactive) == 0
        upstream_boxes = non_max_suppression(prediction, conf_thres=0.5, iou_thres=0.5, classes=[0])[0].numpy()
        # Upstream NMS reports joint confidence, unlike Samsung's class score.
        joint = raw.copy()
        joint[:, 7] *= joint[:, 4]
        boxes = decode(joint.reshape(OUTPUT_SHAPE))
        np.testing.assert_allclose(boxes, upstream_boxes[:, :5], atol=0.001, rtol=0.00001)
        result = {'image': name, 'persons': len(boxes),
                  'samsung_persons': len(decode(raw.reshape(OUTPUT_SHAPE))),
                  'production_score_persons': len(decode(raw.reshape(OUTPUT_SHAPE), score_threshold=0.95)),
                  'max_box_coordinate_error': float(np.abs(recovered - original[:, :4]).max())}
        results.append(result)
        print(result, flush=True)
    (args.model_dir / 'upstream-reference-results.json').write_text(json.dumps(results, indent=2) + '\n')


if __name__ == '__main__':
    main()
