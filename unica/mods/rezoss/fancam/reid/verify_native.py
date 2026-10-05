#!/usr/bin/env python3
"""Validate learned DSP ReID outputs through Samsung's exact native functions."""
import argparse
import json
from pathlib import Path
import sys

import cv2
import numpy as np

from export_reid import native_pose
from inspect_native import LIBRARY_SHA256

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'dlc_audit'))
from run_candidate import run

LIBRARY = '/system/lib64/libauto-reframing-arm64-v8a.so'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model-dir', type=Path, required=True)
    parser.add_argument('--native-probe', type=Path, required=True)
    parser.add_argument('--results-prefix', choices=('device', 'snap'), default='device')
    parser.add_argument('--diagnose-rejected', action='store_true', help='Native diagnostics only; never qualifies a rejected DLC')
    parser.add_argument('--appearance-contract', choices=('first128', 'fitted128'), default='first128')
    parser.add_argument('--adb', default='/mnt/c/platform-tools/adb.exe')
    args = parser.parse_args()
    folder, adb = args.model_dir.resolve(), args.adb
    device = json.loads((folder / (args.results_prefix + '-results.json')).read_text())
    if (folder / 'build-results.json').exists():
        if not device.get('reference_passed') and not args.diagnose_rejected:
            raise ValueError('Legacy DLC reference quality gates did not pass')
        config = {'candidate': 'UN1CA-reid-SM8550-SNPE210-experimental.dlc'}
    else:
        config = json.loads((folder / 'experiment.json').read_text())
    remote = device['remote']
    if run([adb, 'shell', 'getprop', 'ro.soc.model']).strip() != 'SM8550':
        raise ValueError('Expected SM8550')
    if run([adb, 'shell', 'sha256sum', LIBRARY]).split()[0] != LIBRARY_SHA256:
        raise ValueError('Native decoder library hash mismatch')
    device_hash = run([adb, 'shell', 'sha256sum', remote + '/' + config['candidate']]).split()[0]
    if device_hash != device['candidate_sha256']:
        raise ValueError('DLC changed after testing')
    host_path = (run(['wslpath', '-w', str(args.native_probe.resolve())]).strip()
                 if adb.endswith('.exe') else str(args.native_probe.resolve()))
    run([adb, 'push', host_path, remote + '/native_reid_probe'])
    run([adb, 'shell', 'chmod', '755', remote + '/native_reid_probe'])
    names = [entry['image'] for entry in device['tests']]
    feature_vectors = []
    for index in range(len(names)):
        feature_vectors.append(np.fromfile(folder / (args.results_prefix + '-results/dsp-output') / ('Result_' + str(index)) /
                                            'output_1_feature.raw', np.float32))
    report = {'library_sha256': LIBRARY_SHA256, 'device_dlc_sha256': device_hash,
              'full_reference_passed': device.get('reference_passed'),
              'diagnostic_only': args.diagnose_rejected,
              'tests': [], 'distances': {}, 'quality_limitations': [
                  'Facial landmark mapping is provisional, not recovered donor training semantics.',
                  ('128 coordinates are selected from a trained 512D appearance head without retraining.'
                   if args.appearance_contract == 'first128' else
                   '128D teacher-compression projection fitted on independent public pedestrian crops, not identity-supervised training.'),
                  'Brightness pairs do not establish temporal, viewpoint or occlusion robustness.',
                  'No SNAP/MCA/Gallery tracking or export test was performed.']}
    for index, name in enumerate(names):
        root = folder / (args.results_prefix + '-results/dsp-output') / ('Result_' + str(index))
        reference_feature = np.fromfile(folder / (name + '-output_1_feature-reference.raw'), np.float32)
        current_feature = feature_vectors[index]
        feature_cosine = float(np.dot(reference_feature.astype(np.float64), current_feature.astype(np.float64)) /
                               (np.linalg.norm(reference_feature.astype(np.float64)) * np.linalg.norm(current_feature.astype(np.float64))))
        if feature_cosine < 0.98:
            raise ValueError('Quantization changes the learned feature direction: ' + name)
        pose = np.fromfile(root / 'output_0_pose.raw', np.float32).reshape(1, 174, 640, 1)
        points = native_pose(pose)
        reference = native_pose(np.fromfile(folder / (name + '-output_0_pose-reference.raw'),
                                             np.float32).reshape(1, 174, 640, 1))
        if not np.isfinite(pose).all() or np.linalg.norm(feature_vectors[index]) < 0.9:
            raise ValueError('Invalid learned output')
        distances = {}
        native_points = None
        for other, other_name in enumerate(names):
            native = json.loads(run([adb, 'shell', remote + '/native_reid_probe', LIBRARY,
                                     remote + '/dsp-output/Result_' + str(index) + '/output_0_pose.raw',
                                     remote + '/dsp-output/Result_' + str(index) + '/output_1_feature.raw',
                                     remote + '/dsp-output/Result_' + str(other) + '/output_1_feature.raw']))
            if native['decode_status'] != 0:
                raise ValueError('Samsung pose decoder failed')
            native_points = np.array(native['points'])
            np.testing.assert_allclose(native_points, points, atol=0.00001, rtol=0)
            a, b = feature_vectors[index], feature_vectors[other]
            expected = float(1 - np.clip(np.dot(a.astype(np.float64), b.astype(np.float64)) /
                                          (np.linalg.norm(a.astype(np.float64)) * np.linalg.norm(b.astype(np.float64))), 0, 1))
            np.testing.assert_allclose(native['cosine_distance'], expected, atol=0.00001, rtol=0)
            distances[other_name] = native['cosine_distance']
        reliable = (reference[:17, 2] >= 0.5) & (native_points[:17, 2] >= 0.5)
        errors = np.linalg.norm(native_points[:17, :2] - reference[:17, :2], axis=1)[reliable]
        if errors.size and (np.median(errors) > 3 or errors.max() > 8):
            raise ValueError('Body landmarks moved significantly during quantization')
        # Render the DSP view from the unannotated public normalized input.
        tensor = np.fromfile(folder / 'inputs' / (name + '.raw'), np.float32).reshape(320, 320, 3)
        image = cv2.cvtColor((tensor * 255).clip(0, 255).astype(np.uint8), cv2.COLOR_RGB2BGR)
        for point_index, (x, y, score) in enumerate(native_points[:23]):
            if score >= 0.5:
                cv2.circle(image, (round(x), round(y)), 3, (50, 210, 80), -1)
                cv2.putText(image, str(point_index), (round(x) + 3, round(y)),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.35, (40, 40, 230), 1)
        cv2.imwrite(str(folder / (name + '-dsp-pose.jpg')), image)
        result = {'image': name, 'body_points_above_half': int(np.count_nonzero(native_points[:17, 2] >= 0.5)),
                  'float_reference_feature_cosine': feature_cosine,
                  'native_points': native_points.tolist(), 'reliable_body_count': int(reliable.sum()),
                  'body_median_pixel_error': float(np.median(errors)) if errors.size else None,
                  'body_max_pixel_error': float(errors.max()) if errors.size else None}
        report['tests'].append(result)
        report['distances'][name] = distances
        print(name, 'body points', result['body_points_above_half'], 'median error', result['body_median_pixel_error'], flush=True)
    pair_checks = []
    for name in names:
        if name.endswith('-brightness') or name + '-brightness' not in report['distances'][name]:
            continue
        same = report['distances'][name][name + '-brightness']
        different = min(distance for other, distance in report['distances'][name].items()
                        if other not in (name, name + '-brightness'))
        passed = same < different
        pair_checks.append({'person': name, 'brightness_pair_distance': same,
                            'nearest_different_person_distance': different, 'separated': passed})
        if not passed:
            raise ValueError('Appearance baseline does not separate sample identities: ' + name)
    report['pair_checks'] = pair_checks
    (folder / 'native-results.json').write_text(json.dumps(report, indent=2) + '\n')
    print('Native pose and cosine checks passed;', len(pair_checks), 'sample identity pairs separated.')
    if args.diagnose_rejected:
        print('DIAGNOSTIC ONLY: does not override failed reference gates or permit ROM installation.')


if __name__ == '__main__':
    main()
