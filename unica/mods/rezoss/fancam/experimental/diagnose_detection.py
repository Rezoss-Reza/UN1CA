#!/usr/bin/env python3
"""Trace a public clip through Samsung preprocessing, SNAP and native decoding."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import sys

import cv2
import numpy as np
import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'locator'))
from locator_contract import OUTPUT_SHAPE, decode, preprocess
from probe_device import overlap

AUTO = '/system/lib64/libauto-reframing-arm64-v8a.so'
MCA = '/system/lib64/libmediacontextanalyzer.so'
LOCATOR = '/system/etc/mediacontextanalyzer/Locator.dlc'
HASHES = {
    AUTO: 'e8921a043f2050d69d11f906820a400054c835bebd0f743bff7a33405c42f835',
    MCA: 'bedfc253a4c577d6917f2a79412c1c98d7c43cf5b2101406de9e970e526fabd7',
    LOCATOR: '39888e55d6245980bd77e6009c869d0cc294c7a0c6d9b0193aee00612993756a',
}


def run(args):
    return subprocess.run(args, capture_output=True, text=True, check=True).stdout.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--video', type=Path, required=True)
    parser.add_argument('--onnx', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--preprocess-probe', type=Path, required=True)
    parser.add_argument('--snap-probe', type=Path, required=True)
    parser.add_argument('--decoder-probe', type=Path, required=True)
    parser.add_argument('--frames', type=int, default=120)
    parser.add_argument('--adb', default='/mnt/c/platform-tools/adb.exe')
    parser.add_argument('--dlc', default=LOCATOR)
    parser.add_argument('--dlc-sha256', default=HASHES[LOCATOR])
    args = parser.parse_args()
    if not 1 <= args.frames <= 120:
        parser.error('--frames must be 1..120')
    args.output.mkdir(parents=True, exist_ok=False)
    adb = args.adb

    def shell(command):
        return run([adb, 'shell', command])

    def local(path):
        return run(['wslpath', '-w', str(path.resolve())]) if adb.endswith('.exe') else str(path.resolve())

    hashes = {AUTO: HASHES[AUTO], MCA: HASHES[MCA], args.dlc: args.dlc_sha256}
    for path, expected in hashes.items():
        if shell('sha256sum ' + shlex.quote(path)).split()[0] != expected:
            raise ValueError('Pinned ABI/model changed: ' + path)
    remote = '/data/local/tmp/unica-detection-' + args.output.name
    shell('test ! -e ' + shlex.quote(remote) + ' && mkdir ' + shlex.quote(remote))
    for probe in (args.preprocess_probe, args.snap_probe, args.decoder_probe):
        run([adb, 'push', local(probe), remote + '/' + probe.name])
        shell('chmod 755 ' + shlex.quote(remote + '/' + probe.name))
    session = ort.InferenceSession(str(args.onnx), providers=['CPUExecutionProvider'])
    capture = cv2.VideoCapture(str(args.video))
    if not capture.isOpened():
        raise ValueError('Cannot decode public clip')
    report = {'video_sha256': hashlib.sha256(args.video.read_bytes()).hexdigest(),
              'hashes': hashes, 'remote': remote, 'tests': [],
              'limitations': 'Isolated raw RGB path; Gallery YUV conversion and tracking not yet validated.'}
    for timestamp in (0.5, 1.5, 2.5):
        capture.set(cv2.CAP_PROP_POS_MSEC, timestamp * 1000)
        ok, image = capture.read()
        if not ok:
            raise ValueError('Frame unavailable at ' + str(timestamp))
        for orientation in ('portrait', 'landscape'):
            frame = cv2.resize(image, (720, 1365))
            if orientation == 'landscape':
                frame = cv2.rotate(frame, cv2.ROTATE_90_CLOCKWISE)
            rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            height, width = rgb.shape[:2]
            baseline = None
            for padded in (False, True):
                name = str(timestamp).replace('.', '_') + '-' + orientation + ('-padded' if padded else '-packed')
                folder = args.output / name
                folder.mkdir()
                stride = ((width + 63) // 64) * 64 if padded else width
                raw = np.zeros((height, stride, 3), np.uint8)
                raw[:, :width] = rgb
                raw.tofile(folder / 'frame.rgb')
                dest = remote + '/' + name
                shell('mkdir ' + shlex.quote(dest))
                run([adb, 'push', local(folder / 'frame.rgb'), dest + '/frame.rgb'])
                geometry = json.loads(shell(shlex.join([remote + '/' + args.preprocess_probe.name, AUTO,
                    dest + '/frame.rgb', str(width), str(height), str(stride), dest + '/input.raw'])))
                run([adb, 'pull', dest + '/input.raw', local(folder / 'input.raw')])
                tensor = np.fromfile(folder / 'input.raw', np.float32).reshape(1, 320, 320, 3)
                if not np.isfinite(tensor).all() or tensor.min() < 0 or tensor.max() > 1.00001:
                    raise ValueError('Invalid Samsung preprocessing tensor')
                if padded:
                    np.testing.assert_array_equal(tensor, baseline)
                else:
                    baseline = tensor.copy()
                reference = session.run(['output'], {'images': tensor})[0]
                reference.astype(np.float32).tofile(folder / 'onnx-output.raw')
                host_tensor, _ = preprocess(frame)
                host_boxes = decode(session.run(['output'], {'images': host_tensor})[0])
                command = ['env', 'LD_LIBRARY_PATH=/system/lib64:/vendor/lib64', remote + '/' + args.snap_probe.name,
                           MCA, args.dlc, dest + '/input.raw', dest, str(args.frames)]
                log = shell(shlex.join(['su', '-c', shlex.join(command)]))
                (folder / 'snap.log').write_text(log + '\n')
                run([adb, 'pull', dest + '/output.raw', local(folder / 'snap-output.raw')])
                actual = np.fromfile(folder / 'snap-output.raw', np.float32).reshape(OUTPUT_SHAPE)
                rmse = float(np.linalg.norm(actual - reference) / max(np.linalg.norm(reference), 1e-8))
                native = json.loads(shell(shlex.join([remote + '/' + args.decoder_probe.name, AUTO, dest + '/output.raw'])))
                production = json.loads(shell(shlex.join([remote + '/' + args.decoder_probe.name, AUTO,
                    dest + '/output.raw', '4', '0.95'])))
                actual_boxes, reference_boxes = decode(actual), decode(reference)
                clipped = actual_boxes.copy()
                clipped[:, :4] = clipped[:, :4].clip(0, 320)
                if native['count'] != len(clipped) or len(actual_boxes) != len(reference_boxes):
                    raise ValueError('Detection count mismatch')
                for item, box in zip(native['boxes'], clipped):
                    np.testing.assert_allclose(item['xyxy'], box[:4], atol=0.001, rtol=0)
                    np.testing.assert_allclose(item['score'], box[4], atol=0.00001, rtol=0)
                remaining = list(range(len(actual_boxes)))
                overlaps = []
                for box in reference_boxes:
                    best = max(remaining, key=lambda index: overlap(box, actual_boxes[index]))
                    overlaps.append(overlap(box, actual_boxes[best]))
                    remaining.remove(best)
                if overlaps and min(overlaps) < 0.8:
                    raise ValueError('Reference boxes moved')
                if production['count'] != len(decode(actual, score_threshold=0.95)):
                    raise ValueError('Production-score decoder mismatch')
                rows = actual[0, :, :, 0]
                result = {'sample': name, 'timestamp_seconds': timestamp, 'geometry': geometry,
                          'input_range': [float(tensor.min()), float(tensor.max())],
                          'host_preprocess_max_difference': float(np.abs(tensor - host_tensor).max()),
                          'host_preprocess_persons': len(host_boxes), 'onnx_persons': len(decode(reference)),
                          'snap_persons': len(decode(actual)), 'native': native,
                          'production_score_native': production,
                          'reference_iou': overlaps,
                          'relative_rmse': rmse, 'max_objectness': float(rows[:, 4].max()),
                          'max_person_score': float(rows[:, 7].max())}
                report['tests'].append(result)
                (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
                print(name, 'ONNX', result['onnx_persons'], 'SNAP', result['snap_persons'],
                      'native', native['count'], 'RMSE', rmse, flush=True)
                if rmse > 0.2 or native['count'] != result['snap_persons']:
                    raise ValueError('Inference/decoder contract mismatch; see report')
    capture.release()


if __name__ == '__main__':
    main()
