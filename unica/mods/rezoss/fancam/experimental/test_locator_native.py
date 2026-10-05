#!/usr/bin/env python3
"""Check qualified legacy Locator tensors with Samsung's installed decoder."""
import argparse
import json
from pathlib import Path
import sys

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'locator'))
from locator_contract import OUTPUT_SHAPE, decode
from probe_device import DECODER_LIBRARY, DECODER_SHA256, overlap, run


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model-dir', type=Path, required=True)
    parser.add_argument('--native-probe', type=Path, required=True)
    parser.add_argument('--adb', default='/mnt/c/platform-tools/adb.exe')
    args = parser.parse_args()
    folder, adb = args.model_dir.resolve(), args.adb
    evidence = json.loads((folder / 'snap-results.json').read_text())
    if not evidence['reference_passed']:
        raise ValueError('SNAP reference gates failed')
    if run([adb, 'shell', 'sha256sum', DECODER_LIBRARY]).split()[0] != DECODER_SHA256:
        raise ValueError('Decoder ABI hash mismatch')
    remote = evidence['remote']
    candidate = remote + '/UN1CA-locator-SM8550-SNPE210-experimental.dlc'
    if run([adb, 'shell', 'sha256sum', candidate]).split()[0] != evidence['candidate_sha256']:
        raise ValueError('Candidate changed')
    probe = run(['wslpath', '-w', str(args.native_probe.resolve())]).strip() if adb.endswith('.exe') else str(args.native_probe.resolve())
    run([adb, 'push', probe, remote + '/native_decoder_probe'])
    run([adb, 'shell', 'chmod', '755', remote + '/native_decoder_probe'])
    report = {'candidate_sha256': evidence['candidate_sha256'], 'decoder_sha256': DECODER_SHA256,
              'source': 'stock SNAP results', 'gallery_validated': False, 'tests': []}
    for index, test in enumerate(evidence['tests']):
        name = test['image']
        actual = np.fromfile(folder / 'snap-results/dsp-output' / ('Result_' + str(index)) / 'output.raw', np.float32).reshape(OUTPUT_SHAPE)
        expected = np.fromfile(folder / (name + '-onnx-output.raw'), np.float32).reshape(OUTPUT_SHAPE)
        boxes, reference = decode(actual), decode(expected)
        if len(boxes) != len(reference):
            raise ValueError('Reference detection count mismatch: ' + name)
        remaining, overlaps = list(range(len(boxes))), []
        for box in reference:
            best = max(remaining, key=lambda i: overlap(box, boxes[i]))
            overlaps.append(overlap(box, boxes[best]))
            remaining.remove(best)
        if overlaps and min(overlaps) < 0.8:
            raise ValueError('Reference boxes moved: ' + name)
        native = json.loads(run([adb, 'shell', remote + '/native_decoder_probe', DECODER_LIBRARY,
                                 remote + '/dsp-output/Result_' + str(index) + '/output.raw']))
        if native['count'] != len(boxes):
            raise ValueError('Native detection count mismatch: ' + name)
        clipped = boxes.copy()
        clipped[:, :4] = clipped[:, :4].clip(0, 320)
        for item, box in zip(native['boxes'], clipped):
            if item['class'] != 2:
                raise ValueError('Non-person native category')
            np.testing.assert_allclose(item['xyxy'], box[:4], atol=0.001, rtol=0)
            np.testing.assert_allclose(item['score'], box[4], atol=0.00001, rtol=0)
        if name in ('blank', 'horses') and len(boxes):
            raise ValueError('Negative-image detection')
        report['tests'].append({'image': name, 'persons': len(boxes), 'reference_iou': overlaps, 'native': native})
        print(name, len(boxes), overlaps, flush=True)
    (folder / 'native-results.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
