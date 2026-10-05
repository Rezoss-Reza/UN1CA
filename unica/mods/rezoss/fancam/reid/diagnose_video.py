#!/usr/bin/env python3
"""Compare exact-clip crop geometry and ReID tensors using pinned native ABIs."""
import argparse
from collections import Counter
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
from locator_contract import decode
from export_reid import native_pose

AUTO = '/system/lib64/libauto-reframing-arm64-v8a.so'
MCA = '/system/lib64/libmediacontextanalyzer.so'
HASHES = {
    AUTO: 'e8921a043f2050d69d11f906820a400054c835bebd0f743bff7a33405c42f835',
    MCA: 'bedfc253a4c577d6917f2a79412c1c98d7c43cf5b2101406de9e970e526fabd7',
    '/system/etc/mediacontextanalyzer/Locator.dlc': 'b43091ef9885cc7b3c136a69ce702d0afcc49d7fd5e5a9c748d2a389ed0e7ea6',
    '/system/etc/mediacontextanalyzer/Reid.dlc': 'f9ce6a502fa9ad1b33b5d20bfdd33159c9040613a5f737cc1f18ee8313788c2b',
}


def cosine(a, b):
    a, b = a.astype(np.float64).ravel(), b.astype(np.float64).ravel()
    return float(np.dot(a, b) / max(np.linalg.norm(a) * np.linalg.norm(b), 1e-12))


def main():
    parser = argparse.ArgumentParser()
    for name in ('video', 'locator', 'reid', 'output', 'preprocess_probe', 'snap_probe', 'pose_probe'):
        parser.add_argument('--' + name.replace('_', '-'), type=Path, required=True)
    parser.add_argument('--adb', default='/mnt/c/platform-tools/adb.exe')
    parser.add_argument('--frame-step', type=int, default=15)
    args = parser.parse_args()
    if args.frame_step < 1:
        parser.error('frame-step must be positive')
    args.output.mkdir(parents=True, exist_ok=False)

    def run(command):
        return subprocess.check_output(command, text=True).strip()

    def shell(command, root=False):
        return run([args.adb, 'shell', *(['su', '-c'] if root else []), command])

    def local(path):
        return run(['wslpath', '-w', str(path.resolve())])

    for path, digest in HASHES.items():
        if shell('sha256sum ' + path).split()[0] != digest:
            raise ValueError('ABI/model hash mismatch: ' + path)
    remote = '/data/local/tmp/unica-track-' + args.output.name
    shell('test ! -e ' + remote + ' && mkdir ' + remote)
    for probe in (args.preprocess_probe, args.snap_probe, args.pose_probe):
        run([args.adb, 'push', local(probe), remote + '/' + probe.name])
        shell('chmod 755 ' + remote + '/' + probe.name)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.log_severity_level = 3
    locator = ort.InferenceSession(str(args.locator), options, providers=['CPUExecutionProvider'])
    reid = ort.InferenceSession(str(args.reid), options, providers=['CPUExecutionProvider'])
    capture = cv2.VideoCapture(str(args.video))
    fps = capture.get(cv2.CAP_PROP_FPS)
    report = {'video_sha256': hashlib.sha256(args.video.read_bytes()).hexdigest(),
              'library_model_hashes': HASHES, 'frame_step': args.frame_step, 'fps': fps,
              'frames': [], 'failures': [],
              'limitations': ['Horizontal rank labels are reference labels, NOT Samsung track IDs.',
                              'No production tracker or crop rectangles inferred from this probe.',
                              'Sampled frames cover the clip; not every decoded frame is tested.']}
    templates = None
    frame_index = 0
    while True:
        ok, image = capture.read()
        if not ok:
            break
        index = frame_index
        frame_index += 1
        if index % args.frame_step:
            continue
        folder = args.output / ('frame-%04d' % index)
        folder.mkdir()
        dest = remote + '/' + folder.name
        shell('mkdir ' + dest)
        cv2.cvtColor(image, cv2.COLOR_BGR2RGB).tofile(folder / 'frame.rgb')
        run([args.adb, 'push', local(folder / 'frame.rgb'), dest + '/frame.rgb'])
        height, width = image.shape[:2]

        def preprocess(name, box=None):
            command = [remote + '/' + args.preprocess_probe.name, AUTO, dest + '/frame.rgb',
                       str(width), str(height), str(width), dest + '/' + name + '.raw']
            if box is not None:
                command += [str(float(value)) for value in box]
            geometry = json.loads(shell(shlex.join(command)))
            if geometry['status']:
                raise ValueError('Native preprocessing failed')
            run([args.adb, 'pull', dest + '/' + name + '.raw', local(folder / (name + '.raw'))])
            run([args.adb, 'pull', dest + '/' + name + '.raw.geometry', local(folder / (name + '.raw.geometry'))])
            return np.fromfile(folder / (name + '.raw'), np.float32).reshape(1, 320, 320, 3), geometry

        tensor, geometry = preprocess('locator-input')
        detections = decode(locator.run(None, {'images': tensor})[0], score_threshold=0.95)
        for axis in range(2):
            detections[:, [axis, axis + 2]] = ((detections[:, [axis, axis + 2]] - geometry['padding'][axis]) *
                                              geometry['scale'][axis] + geometry['bounds'][axis])
        detections[:, [0, 2]] = detections[:, [0, 2]].clip(0, width)
        detections[:, [1, 3]] = detections[:, [1, 3]].clip(0, height)
        detections = detections[np.argsort(detections[:, 0] + detections[:, 2])]
        entry = {'frame': index, 'seconds': index / fps, 'locator_geometry': geometry,
                 'raw_host_boxes': detections.tolist(), 'persons': []}
        features = []
        for person, box in enumerate(detections):
            name = 'person-' + str(person)
            crop, crop_geometry = preprocess(name, box[:4])
            host = dict(zip([o.name for o in reid.get_outputs()], reid.run(None, {'images': crop})))
            result_path = dest + '/' + name
            shell('mkdir ' + result_path)
            log = shell(shlex.join(['env', 'LD_LIBRARY_PATH=/system/lib64:/vendor/lib64',
                        remote + '/' + args.snap_probe.name, MCA,
                        '/system/etc/mediacontextanalyzer/Reid.dlc', dest + '/' + name + '.raw',
                        result_path, '1']), root=True)
            (folder / (name + '.log')).write_text(log)
            run([args.adb, 'pull', result_path, local(folder)])
            dsp = {key: np.fromfile(folder / name / (key + '.raw'), np.float32).reshape(value.shape)
                   for key, value in host.items()}
            metrics = {key: {'relative_rmse': float(np.linalg.norm(dsp[key] - value) /
                                                    max(np.linalg.norm(value), 1e-12)),
                             'cosine': cosine(dsp[key], value)} for key, value in host.items()}
            native = json.loads(shell(shlex.join([remote + '/' + args.pose_probe.name, AUTO,
                                result_path + '/output_0_pose.raw', result_path + '/output_1_feature.raw',
                                result_path + '/output_1_feature.raw', dest + '/' + name + '.raw.geometry'])))
            points = native_pose(dsp['output_0_pose'])
            for axis in range(2):
                points[:, axis] = ((points[:, axis] - crop_geometry['padding'][axis]) *
                                   crop_geometry['scale'][axis] + crop_geometry['bounds'][axis])
            native_points = np.array(native['points'])
            np.testing.assert_allclose(points[:, :2], native_points[:, :2], atol=0.001, rtol=0)
            features.append(dsp['output_1_feature'].ravel())
            entry['persons'].append({'horizontal_rank': person, 'geometry': crop_geometry,
                                    'metrics': metrics, 'points': native_points.tolist(),
                                    'confident_body_points': int(np.count_nonzero(native_points[:17, 2] >= 0.5))})
            if metrics['output_0_pose']['relative_rmse'] > 0.2:
                report['failures'].append([index, person, 'pose_rmse'])
            if metrics['output_1_feature']['cosine'] < 0.98:
                report['failures'].append([index, person, 'feature_cosine'])
        if len(features) == 3:
            if templates is None:
                templates = np.array(features)
            entry['feature_template_cosines'] = [[cosine(a, b) for b in templates] for a in features]
            entry['reference_rank_template_winners'] = np.argmax(entry['feature_template_cosines'], axis=1).tolist()
        report['frames'].append(entry)
        (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(index, round(index / fps, 2), len(detections),
              entry.get('reference_rank_template_winners'), flush=True)
    capture.release()
    report['decoded_frames'] = frame_index
    report['detection_counts'] = dict(Counter(len(f['persons']) for f in report['frames']))
    (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
