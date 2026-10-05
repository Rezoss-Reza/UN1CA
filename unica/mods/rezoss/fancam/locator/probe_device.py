#!/usr/bin/env python3
"""Run the experimental Locator in /data/local/tmp and verify native decoding."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess

import cv2
import numpy as np

from locator_contract import OUTPUT_SHAPE, decode, preprocess, restore_boxes

REMOTE = '/data/local/tmp/unica-locator-person-20261003'
DECODER_LIBRARY = '/system/lib64/libauto-reframing-arm64-v8a.so'
DECODER_SHA256 = 'e8921a043f2050d69d11f906820a400054c835bebd0f743bff7a33405c42f835'


def run(command, timeout=180):
    result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(shlex.join(map(str, command)) + '\n' + result.stdout + result.stderr)
    return result.stdout.replace('\r', '')


def overlap(a, b):
    intersection = np.maximum(np.minimum(a[2:4], b[2:4]) - np.maximum(a[:2], b[:2]), 0).prod()
    area_a = np.maximum(a[2:4] - a[:2], 0).prod()
    area_b = np.maximum(b[2:4] - b[:2], 0).prod()
    return float(intersection / max(area_a + area_b - intersection, 1e-8))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', default='/mnt/c/platform-tools/adb.exe')
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--model-dir', type=Path, required=True)
    parser.add_argument('--native-probe', type=Path, required=True)
    parser.add_argument('--image-dir', type=Path, required=True)
    parser.add_argument('--runtime-libs', type=Path, required=True)
    parser.add_argument('--collect-only', action='store_true')
    args = parser.parse_args()
    adb, sdk, model = args.adb, args.sdk.resolve(), args.model_dir.resolve()
    soc = run([adb, 'shell', 'getprop', 'ro.soc.model']).strip()
    if soc != 'SM8550':
        raise ValueError('Expected SM8550, got ' + soc)
    library_hash = run([adb, 'shell', 'sha256sum', DECODER_LIBRARY]).split()[0]
    if library_hash != DECODER_SHA256:
        raise ValueError('Samsung decoder ABI hash mismatch')

    def host_path(path):
        if adb.endswith('.exe'):
            return run(['wslpath', '-w', str(path)]).strip()
        return str(path)

    if not args.collect_only:
        run([adb, 'shell', 'mkdir', '-p', REMOTE])
        files = [model / 'Locator-person-SM8550-v1.dlc', model / 'device-inputs.txt',
                 args.native_probe, sdk / 'bin/aarch64-android/snpe-net-run']
        files += [sdk / 'lib/aarch64-android' / name for name in (
            'libSNPE.so', 'libQnnHtp.so', 'libQnnSystem.so',
            'libSnpeHtpV73Stub.so', 'libQnnHtpV73Stub.so')]
        files += [sdk / 'lib/hexagon-v73/unsigned' / name for name in (
            'libSnpeHtpV73Skel.so', 'libQnnHtpV73Skel.so')]
        run([adb, 'push', *map(host_path, files), REMOTE + '/'])
        run([adb, 'push', host_path(model / 'inputs'), REMOTE + '/'])
        run([adb, 'shell', 'chmod', '755', REMOTE + '/snpe-net-run', REMOTE + '/native_decoder_probe'])
        command = ['env', 'LD_LIBRARY_PATH=' + REMOTE + ':/vendor/lib64',
                   'ADSP_LIBRARY_PATH=' + REMOTE + ';/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/dsp',
                   REMOTE + '/snpe-net-run', '--container', REMOTE + '/Locator-person-SM8550-v1.dlc',
                   '--input_list', REMOTE + '/device-inputs.txt', '--output_dir', REMOTE + '/dsp-output',
                   '--use_dsp', '--profiling_level', 'detailed', '--platform_options', 'unsignedPD:ON']
        log = run([adb, 'shell', 'cd ' + shlex.quote(REMOTE) + ' && ' + shlex.join(command)])
        (model / 'device-dsp-run.log').write_text(log)

    pulled = model / 'device-results'
    pulled.mkdir(exist_ok=True)
    run([adb, 'pull', REMOTE + '/dsp-output', host_path(pulled)])
    env = os.environ.copy()
    env['LD_LIBRARY_PATH'] = str(args.runtime_libs) + ':' + str(sdk / 'lib/x86_64-linux-clang')
    profile = subprocess.run([str(sdk / 'bin/x86_64-linux-clang/snpe-diagview'),
                              '--input_log', str(pulled / 'dsp-output/SNPEDiag_0.log')],
                             env=env, capture_output=True, text=True, check=True).stdout
    (model / 'device-profile.txt').write_text(profile)
    layer_runtimes = re.findall(r'^\s+\d+:.* : (DSP|CPU|GPU)\s*$', profile, re.MULTILINE)
    if not layer_runtimes or set(layer_runtimes) != {'DSP'}:
        raise ValueError('Profile does not establish all-DSP execution')
    inference_time = re.search(r'Total Inference Time:\s*(\d+) us', profile)
    report = {'soc': soc, 'decoder_sha256': library_hash,
              'dlc_sha256': hashlib.sha256((model / 'Locator-person-SM8550-v1.dlc').read_bytes()).hexdigest(),
              'runtime': 'DSP', 'profiled_layers': len(layer_runtimes),
              'sample_average_inference_us': int(inference_time.group(1)) if inference_time else None,
              'tests': []}
    names = [Path(line.strip()).stem for line in (model / 'device-inputs.txt').read_text().splitlines() if line.strip()]
    for index, name in enumerate(names):
        raw = np.fromfile(pulled / 'dsp-output' / ('Result_' + str(index)) / 'output.raw', np.float32).reshape(OUTPUT_SHAPE)
        expected = np.fromfile(model / (name + '-onnx-output.raw'), np.float32).reshape(OUTPUT_SHAPE)
        boxes, reference_boxes = decode(raw), decode(expected)
        if len(boxes) != len(reference_boxes):
            raise ValueError('Detection count differs from float reference: ' + name)
        unmatched = list(range(len(boxes)))
        overlaps = []
        for reference in reference_boxes:
            best = max(unmatched, key=lambda item: overlap(reference, boxes[item]))
            overlaps.append(overlap(reference, boxes[best]))
            unmatched.remove(best)
        if overlaps and min(overlaps) < 0.8:
            raise ValueError('Quantized boxes differ from float reference: ' + name)
        native = json.loads(run([adb, 'shell', REMOTE + '/native_decoder_probe', DECODER_LIBRARY,
                                 REMOTE + '/dsp-output/Result_' + str(index) + '/output.raw']))
        if native['count'] != len(boxes):
            raise ValueError('Native decoder count mismatch: ' + name)
        clipped = boxes.copy()
        clipped[:, :4] = clipped[:, :4].clip(0, 320)
        for result, box in zip(native['boxes'], clipped):
            if result['class'] != 2:
                raise ValueError('Unexpected native category')
            np.testing.assert_allclose(result['xyxy'], box[:4], atol=0.001, rtol=0)
            np.testing.assert_allclose(result['score'], box[4], atol=0.00001, rtol=0)
        if name in ('blank', 'horses') and len(boxes):
            raise ValueError('False detection on negative image: ' + name)
        image = (np.full((320, 320, 3), 114, np.uint8) if name == 'blank'
                 else cv2.imread(str(args.image_dir / (name + '.jpg'))))
        _, transform = preprocess(image)
        original_boxes = restore_boxes(boxes, transform, image.shape)
        for x1, y1, x2, y2, score in original_boxes:
            cv2.rectangle(image, (round(x1), round(y1)), (round(x2), round(y2)), (0, 220, 70), 2)
            cv2.putText(image, 'person %.2f' % score, (round(x1), max(20, round(y1))),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 220, 70), 2)
        cv2.imwrite(str(model / (name + '-dsp-detections.jpg')), image)
        report['tests'].append({'image': name, 'persons': len(boxes), 'native_decoder': native,
                                'float_reference_box_iou': overlaps,
                                'max_tensor_abs_difference': float(np.abs(raw - expected).max())})
        print(name, 'DSP/native persons:', len(boxes), 'reference IoU:', overlaps, flush=True)
    (model / 'device-results.json').write_text(json.dumps(report, indent=2) + '\n')
    print('Verified DSP layers:', len(layer_runtimes), 'sample mean us:', report['sample_average_inference_us'])


if __name__ == '__main__':
    main()
