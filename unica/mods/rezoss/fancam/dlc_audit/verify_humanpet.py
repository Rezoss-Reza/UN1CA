#!/usr/bin/env python3
"""Check real human/pet DSP outputs through the pinned Samsung native decoder."""
import argparse
import json
from pathlib import Path
import sys

import cv2
import numpy as np

from prepare_models import CATEGORIES, decode_categories
from run_candidate import run

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'locator'))
from locator_contract import OUTPUT_SHAPE, preprocess, restore_boxes
from probe_device import DECODER_LIBRARY, DECODER_SHA256, overlap


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model-dir', type=Path, required=True)
    parser.add_argument('--native-probe', type=Path, required=True)
    parser.add_argument('--samples', type=Path, required=True)
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--adb', default='/mnt/c/platform-tools/adb.exe')
    args = parser.parse_args()
    folder, adb = args.model_dir.resolve(), args.adb
    report = json.loads((folder / 'device-results.json').read_text())
    remote = report['remote']
    config = json.loads((folder / 'experiment.json').read_text())
    if (folder / 'build-results.json').exists():
        build = json.loads((folder / 'build-results.json').read_text())
        if build.get('model') != 'humanpet' or not report.get('reference_passed'):
            raise ValueError('Expected reference-qualified humanpet build')
        config['candidate'] = 'UN1CA-humanpet-SM8550-SNPE210-experimental.dlc'
    device_hash = run([adb, 'shell', 'sha256sum', remote + '/' + config['candidate']]).split()[0]
    if device_hash != report['candidate_sha256']:
        raise ValueError('Device model checksum mismatch')
    if run([adb, 'shell', 'sha256sum', DECODER_LIBRARY]).split()[0] != DECODER_SHA256:
        raise ValueError('Native decoder hash mismatch')
    path = (run(['wslpath', '-w', str(args.native_probe.resolve())]).strip()
            if adb.endswith('.exe') else str(args.native_probe.resolve()))
    run([adb, 'push', path, remote + '/native_decoder_probe'])
    run([adb, 'shell', 'chmod', '755', remote + '/native_decoder_probe'])
    results = {'decoder_sha256': DECODER_SHA256, 'device_dlc_sha256': device_hash,
               'tests': [], 'limitations': [
        'Ten public/blank sample inputs are not a validated accuracy benchmark.',
        'The public white-dog sample is misclassified as cat by the pretrained model.',
        'Faces, gestures, other Samsung classes, and SNAP/MCA service integration are unverified.']}
    images = {p.stem: cv2.imread(str(p)) for p in sorted((args.upstream / 'inference/images').glob('*.jpg'))}
    images.update({p.stem: cv2.imread(str(p)) for p in args.samples.iterdir() if p.suffix in ('.jpg', '.png')})
    images['blank'] = np.full((320, 320, 3), 114, np.uint8)
    for index, entry in enumerate(report['tests']):
        name = entry['image']
        raw = np.fromfile(folder / 'device-results/dsp-output' / ('Result_' + str(index)) / 'output.raw',
                          np.float32).reshape(OUTPUT_SHAPE)
        expected = np.fromfile(folder / (name + '-output-reference.raw'), np.float32).reshape(OUTPUT_SHAPE)
        boxes, reference = decode_categories(raw), decode_categories(expected)
        native = json.loads(run([adb, 'shell', remote + '/native_decoder_probe', DECODER_LIBRARY,
                                 remote + '/dsp-output/Result_' + str(index) + '/output.raw', '7']))
        if native['count'] != len(boxes) or len(boxes) != len(reference):
            raise ValueError('Native/DSP/float detection count mismatch: ' + name)
        unmatched = list(range(len(boxes)))
        ious = []
        for ref in reference:
            same_class = [i for i in unmatched if boxes[i]['class'] == ref['class']]
            if not same_class:
                raise ValueError('Quantized class changed: ' + name)
            best = max(same_class, key=lambda i: overlap(np.array(ref['xyxy']), np.array(boxes[i]['xyxy'])))
            ious.append(overlap(np.array(ref['xyxy']), np.array(boxes[best]['xyxy'])))
            unmatched.remove(best)
        if ious and min(ious) < 0.8:
            raise ValueError('Quantized box differs significantly: ' + name)
        unmatched = list(range(len(boxes)))
        for decoded in native['boxes']:
            same_class = [i for i in unmatched if boxes[i]['class'] == decoded['class']]
            if not same_class:
                raise ValueError('Native class mismatch: ' + name)
            best = min(same_class, key=lambda i: np.abs(np.clip(boxes[i]['xyxy'], 0, 320) - decoded['xyxy']).max())
            np.testing.assert_allclose(np.clip(boxes[best]['xyxy'], 0, 320), decoded['xyxy'], atol=0.001, rtol=0)
            np.testing.assert_allclose(boxes[best]['score'], decoded['score'], atol=0.00001, rtol=0)
            unmatched.remove(best)
        image = images[name].copy()
        _, transform = preprocess(image)
        for box in boxes:
            restored = restore_boxes(np.array([[*box['xyxy'], box['score']]], np.float32), transform, image.shape)[0]
            x1, y1, x2, y2 = map(round, restored[:4])
            color = ((30, 190, 240), (190, 100, 240), (60, 210, 40))[box['class']]
            cv2.rectangle(image, (x1, y1), (x2, y2), color, 2)
            label = '%s %.2f' % (CATEGORIES[box['class']], box['score'])
            width = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.6, 2)[0][0]
            cv2.putText(image, label, (max(0, min(x1, image.shape[1] - width)), max(20, y1)),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.6, color, 2)
        cv2.imwrite(str(folder / (name + '-dsp-detections.jpg')), image)
        result = {'image': name, 'boxes': boxes, 'native_decoder': native, 'float_reference_box_iou': ious}
        results['tests'].append(result)
        print(name, [b['name'] for b in boxes], 'IoU', ious, flush=True)
    (folder / 'native-results.json').write_text(json.dumps(results, indent=2) + '\n')


if __name__ == '__main__':
    main()
