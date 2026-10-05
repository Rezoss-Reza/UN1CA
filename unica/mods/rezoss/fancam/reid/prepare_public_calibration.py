#!/usr/bin/env python3
"""Broaden calibration using a separate public clip, never the failing holdout."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sys

import cv2
import numpy as np
import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'locator'))
from locator_contract import preprocess, decode, restore_boxes


def main():
    parser = argparse.ArgumentParser()
    for name in ('source', 'video', 'locator', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    for name in ('reid-human.onnx', 'inputs.txt', 'calibration.txt'):
        shutil.copy2(args.source / name, args.output / name)
    shutil.copytree(args.source / 'inputs', args.output / 'inputs')
    for path in args.source.glob('*-reference.raw'):
        shutil.copy2(path, args.output / path.name)
    options = ort.SessionOptions()
    options.log_severity_level = 3
    options.intra_op_num_threads = 4
    locator = ort.InferenceSession(str(args.locator), options, providers=['CPUExecutionProvider'])
    reid = ort.InferenceSession(str(args.output / 'reid-human.onnx'), options, providers=['CPUExecutionProvider'])
    cap = cv2.VideoCapture(str(args.video))
    records, index = [], 0
    calibration = [str(args.output.resolve() / 'inputs' / Path(line.strip()).name)
                   for line in (args.source / 'calibration.txt').read_text().splitlines() if line.strip()]
    try:
        while len(records) < 32:
            ok, image = cap.read()
            if not ok: break
            frame = index
            index += 1
            if frame % 20: continue
            tensor, transform = preprocess(image)
            boxes = restore_boxes(decode(locator.run(None, {'images': tensor})[0], score_threshold=0.95), transform, image.shape)
            if not len(boxes): continue
            boxes = boxes[np.argsort(boxes[:, 0])]
            box = boxes[(frame // 20) % len(boxes)]
            h, w = image.shape[:2]
            x1, y1 = np.floor(box[:2] - [w / 32, h / 32]).astype(int)
            x2, y2 = np.ceil(box[2:4] + [w / 32, h / 32]).astype(int)
            crop = image[max(0, y1):min(h, y2), max(0, x1):min(w, x2)]
            ratio = min(320 / crop.shape[0], 320 / crop.shape[1])
            resized = cv2.resize(crop, (round(crop.shape[1] * ratio), round(crop.shape[0] * ratio)))
            padded = np.zeros((320, 320, 3), np.uint8)
            top, left = (320 - resized.shape[0]) // 2, (320 - resized.shape[1]) // 2
            padded[top:top + resized.shape[0], left:left + resized.shape[1]] = resized
            raw = cv2.cvtColor(padded, cv2.COLOR_BGR2RGB).astype(np.float32)[None] / 255
            name = 'public-pedestrian-%04d' % frame
            path = args.output / 'inputs' / (name + '.raw')
            raw.tofile(path)
            calibration.append(str(path.resolve()))
            values = reid.run(None, {'images': raw})
            for output, value in zip(reid.get_outputs(), values):
                value.tofile(args.output / (name + '-' + output.name + '-reference.raw'))
            records.append({'frame': frame, 'box': box.tolist(), 'input': name})
    finally:
        cap.release()
    if len(records) < 16: raise ValueError('Insufficient public calibration crops')
    (args.output / 'calibration.txt').write_text('\n'.join(calibration) + '\n')
    # Keep the original sixteen regression inputs; new calibration samples are
    # explicitly not counted as unseen inference validation.
    report = {'video_sha256': hashlib.sha256(args.video.read_bytes()).hexdigest(), 'new_crops': records,
              'exact_clip_holdout_unchanged': True, 'preprocess': 'Host crop-letterbox approximation, not Gallery tensors.'}
    (args.output / 'public-calibration.json').write_text(json.dumps(report, indent=2) + '\n')
    print('Added', len(records), 'independent calibration crops')


if __name__ == '__main__':
    main()
