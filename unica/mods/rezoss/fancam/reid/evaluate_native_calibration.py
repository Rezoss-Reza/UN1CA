#!/usr/bin/env python3
"""Evaluate a new ReID cache on preserved exact native clip crops."""
import argparse
import json
from pathlib import Path
import shlex
import subprocess

import numpy as np
import onnxruntime as ort

from diagnose_video import AUTO, HASHES, MCA, cosine


def main():
    parser = argparse.ArgumentParser()
    for name in ('baseline', 'model_dir', 'probe', 'output'):
        parser.add_argument('--' + name.replace('_', '-'), type=Path, required=True)
    parser.add_argument('--adb', default='/mnt/c/platform-tools/adb.exe')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)

    def run(command):
        return subprocess.check_output(command, text=True, stderr=subprocess.DEVNULL).strip()

    def local(path):
        return run(['wslpath', '-w', str(path.resolve())])

    def shell(command, root=False):
        return run([args.adb, 'shell', *(['su', '-c'] if root else []), command])

    for library in (AUTO, MCA):
        if shell('sha256sum ' + library).split()[0] != HASHES[library]:
            raise ValueError('Native ABI hash mismatch')
    build = json.loads((args.model_dir / 'build-results.json').read_text())
    candidate = args.model_dir / 'UN1CA-reid-SM8550-SNPE210-experimental.dlc'
    remote = '/data/local/tmp/unica-track-eval-' + args.output.name
    shell('test ! -e ' + remote + ' && mkdir ' + remote)
    run([args.adb, 'push', local(candidate), local(args.probe), remote + '/'])
    if shell('sha256sum ' + remote + '/' + candidate.name).split()[0] != build['candidate_sha256']:
        raise ValueError('Device candidate hash mismatch')
    shell('chmod 755 ' + remote + '/' + args.probe.name)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.log_severity_level = 3
    session = ort.InferenceSession(str(args.model_dir / 'reid-human.onnx'), options,
                                  providers=['CPUExecutionProvider'])
    baseline = json.loads((args.baseline / 'report.json').read_text())
    report = {'candidate_sha256': build['candidate_sha256'], 'calibration_frames': [0, 60],
              'tests': [], 'holdout_failures': [], 'limitations': 'Numeric/appearance reference test, not Samsung tracker validation.'}
    templates = None
    for frame in baseline['frames']:
        features = []
        for person in frame['persons']:
            rank, index = person['horizontal_rank'], frame['frame']
            name = 'frame-%04d-person-%d' % (index, rank)
            tensor_path = args.baseline / ('frame-%04d' % index) / ('person-%d.raw' % rank)
            dest = remote + '/' + name
            shell('mkdir ' + dest)
            run([args.adb, 'push', local(tensor_path), dest + '/input.raw'])
            log = shell(shlex.join(['env', 'LD_LIBRARY_PATH=/system/lib64:/vendor/lib64',
                        remote + '/' + args.probe.name, MCA, remote + '/' + candidate.name,
                        dest + '/input.raw', dest, '1']), root=True)
            (args.output / (name + '.log')).write_text(log)
            run([args.adb, 'pull', dest + '/output_0_pose.raw', local(args.output / (name + '-pose.raw'))])
            run([args.adb, 'pull', dest + '/output_1_feature.raw', local(args.output / (name + '-feature.raw'))])
            tensor = np.fromfile(tensor_path, np.float32).reshape(1, 320, 320, 3)
            host = dict(zip([o.name for o in session.get_outputs()], session.run(None, {'images': tensor})))
            feature = np.fromfile(args.output / (name + '-feature.raw'), np.float32)
            pose = np.fromfile(args.output / (name + '-pose.raw'), np.float32).reshape(host['output_0_pose'].shape)
            feature_cosine = cosine(feature, host['output_1_feature'])
            pose_rmse = float(np.linalg.norm(pose - host['output_0_pose']) / max(np.linalg.norm(host['output_0_pose']), 1e-12))
            entry = {'frame': index, 'person_rank': rank, 'calibration_sample': index in (0, 60),
                     'feature_cosine': feature_cosine, 'pose_rmse': pose_rmse}
            if index not in (0, 60) and (feature_cosine < 0.98 or pose_rmse > 0.2):
                report['holdout_failures'].append(entry)
            report['tests'].append(entry)
            features.append(feature)
        if len(features) == 3:
            if templates is None:
                templates = np.array(features)
            matrix = [[cosine(a, b) for b in templates] for a in features]
            for entry, row in zip(report['tests'][-3:], matrix):
                entry['template_cosines'] = row
                entry['template_winner'] = int(np.argmax(row))
        (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(frame['frame'], 'feature', round(min(e['feature_cosine'] for e in report['tests'][-len(features):]), 4),
              'pose', round(max(e['pose_rmse'] for e in report['tests'][-len(features):]), 4), flush=True)


if __name__ == '__main__':
    main()
