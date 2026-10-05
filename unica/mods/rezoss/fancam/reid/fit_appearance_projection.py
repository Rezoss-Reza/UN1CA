#!/usr/bin/env python3
"""Fit a 128D angular-energy projection on a separate public pedestrian clip.

This is data-fitted compression of a learned teacher, not identity-supervised
training or proof of Samsung tracking. Evaluation clips never fit the matrix.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

import cv2
import numpy as np
import onnx
import onnxruntime as ort
import torch
from torch import nn

from export_reid import OSNET_HASH, OSNET_COMMIT, Preprocess

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'locator'))
from locator_contract import preprocess, decode, restore_boxes


def normalize(features):
    return features / np.maximum(np.linalg.norm(features, axis=1, keepdims=True), 1e-12)


def diagnostics(full, projected):
    teacher = normalize(full) @ normalize(full).T
    student = normalize(projected) @ normalize(projected).T
    mask = ~np.eye(len(full), dtype=bool)
    teacher_nn, student_nn = teacher.copy(), student.copy()
    np.fill_diagonal(teacher_nn, -np.inf)
    np.fill_diagonal(student_nn, -np.inf)
    return {'samples': len(full), 'pairwise_cosine_rmse': float(np.sqrt(np.mean((teacher[mask] - student[mask]) ** 2))),
            'teacher_nearest_neighbor_agreement': float(np.mean(teacher_nn.argmax(1) == student_nn.argmax(1)))}


class ProjectedAppearance(nn.Module):
    def __init__(self, teacher, projection):
        super().__init__()
        self.preprocess = Preprocess((256, 128))
        self.teacher = teacher
        self.register_buffer('projection', torch.from_numpy(projection))

    def forward(self, images):
        return (self.teacher(self.preprocess(images)) @ self.projection).reshape(1, 128, 1, 1)


def main():
    parser = argparse.ArgumentParser()
    for name in ('train-video', 'eval-video', 'baseline', 'upstream', 'weights', 'locator', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    import subprocess
    if hashlib.sha256(args.weights.read_bytes()).hexdigest() != OSNET_HASH:
        raise ValueError('Weights hash mismatch')
    if subprocess.check_output(['git', '-C', str(args.upstream), 'rev-parse', 'HEAD'], text=True).strip() != OSNET_COMMIT:
        raise ValueError('Upstream revision mismatch')
    train_hash = hashlib.sha256(args.train_video.read_bytes()).hexdigest()
    eval_hash = hashlib.sha256(args.eval_video.read_bytes()).hexdigest()
    baseline = json.loads((args.baseline / 'report.json').read_text())
    if train_hash in (eval_hash, baseline['video_sha256']):
        raise ValueError('Training/evaluation leakage')
    args.output.mkdir(parents=True, exist_ok=False)
    torch.set_num_threads(4)
    spec = importlib.util.spec_from_file_location('reviewed_osnet', args.upstream / 'torchreid/models/osnet.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    weights = torch.load(args.weights, map_location='cpu', weights_only=True)
    weights = weights.get('state_dict', weights)
    weights = {key.removeprefix('module.'): value for key, value in weights.items()}
    teacher = module.osnet_x0_25(num_classes=weights['classifier.weight'].shape[0], pretrained=False)
    teacher.load_state_dict(weights, strict=True)
    teacher.eval()
    prep = Preprocess((256, 128))
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.log_severity_level = 3
    locator = ort.InferenceSession(str(args.locator), options, providers=['CPUExecutionProvider'])

    def features(tensor):
        with torch.no_grad():
            return teacher(prep(torch.from_numpy(tensor))).numpy()[0]

    def collect(video, step, limit):
        cap = cv2.VideoCapture(str(video))
        values, records = [], []
        index = 0
        try:
            while len(values) < limit:
                ok, image = cap.read()
                if not ok:
                    break
                index += 1
                if (index - 1) % step:
                    continue
                tensor, transform = preprocess(image)
                boxes = restore_boxes(decode(locator.run(None, {'images': tensor})[0], score_threshold=0.95),
                                      transform, image.shape)
                for box in boxes:
                    h, w = image.shape[:2]
                    x1, y1 = np.floor(box[:2] - [w / 32, h / 32]).astype(int)
                    x2, y2 = np.ceil(box[2:4] + [w / 32, h / 32]).astype(int)
                    x1, x2 = max(0, x1), min(w, x2)
                    y1, y2 = max(0, y1), min(h, y2)
                    if x2 - x1 < 12 or y2 - y1 < 24:
                        continue
                    crop = image[y1:y2, x1:x2]
                    ratio = min(320 / crop.shape[0], 320 / crop.shape[1])
                    resized = cv2.resize(crop, (round(crop.shape[1] * ratio), round(crop.shape[0] * ratio)))
                    padded = np.zeros((320, 320, 3), np.uint8)
                    left, top = (320 - resized.shape[1]) // 2, (320 - resized.shape[0]) // 2
                    padded[top:top + resized.shape[0], left:left + resized.shape[1]] = resized
                    raw = cv2.cvtColor(padded, cv2.COLOR_BGR2RGB).astype(np.float32)[None] / 255
                    values.append(features(raw))
                    records.append({'frame': index - 1, 'box': box.tolist()})
                if (index - 1) % (step * 20) == 0:
                    print(video.name, index - 1, 'crops', len(values), flush=True)
        finally:
            cap.release()
        return np.array(values), records

    training, train_records = collect(args.train_video, 3, 1400)
    if len(training) < 256:
        raise ValueError('Insufficient independent training crops')
    # Uncentered PCA of normalized teacher features preserves angular energy;
    # centering would change Samsung's cosine metric.
    unit = normalize(training).astype(np.float64)
    eigenvalues, vectors = np.linalg.eigh(unit.T @ unit / len(unit))
    projection = vectors[:, -128:][:, ::-1]
    for column in range(128):
        if projection[np.abs(projection[:, column]).argmax(), column] < 0:
            projection[:, column] *= -1
    projection = projection.astype(np.float32)
    np.save(args.output / 'projection.npy', projection)
    external, external_records = collect(args.eval_video, 15, 240)
    exact, exact_records = [], []
    for frame in baseline['frames']:
        for person in frame['persons']:
            rank = person['horizontal_rank']
            raw = np.fromfile(args.baseline / ('frame-%04d' % frame['frame']) / ('person-%d.raw' % rank),
                              np.float32).reshape(1, 320, 320, 3)
            exact.append(features(raw))
            exact_records.append({'frame': frame['frame'], 'rank': rank})
    exact = np.array(exact)
    report = {'train_video_sha256': train_hash, 'eval_video_sha256': eval_hash,
              'training_samples': len(training), 'retained_angular_energy': float(eigenvalues[-128:].sum() / eigenvalues.sum()),
              'weights_sha256': OSNET_HASH, 'projection_sha256': hashlib.sha256(projection.tobytes()).hexdigest(),
              'training_records': train_records, 'external_records': external_records, 'exact_records': exact_records,
              'evaluations': {}, 'limitations': ['Unsupervised teacher compression, not an identity-trained head.',
                  'External crops use host letterboxing approximation; exact-clip crops use saved Samsung preprocessing.',
                  'Teacher agreement is not identity ground truth or production tracker acceptance.']}
    for label, values in [('external_public_clip', external), ('exact_native_clip', exact)]:
        if len(values) < 2:
            raise ValueError('Insufficient evaluation crops')
        report['evaluations'][label] = {name: diagnostics(values, reduced) for name, reduced in
            [('first128', values[:, :128]), ('mean4x128', values.reshape(-1, 4, 128).mean(1)),
             ('fitted128', values @ projection)]}
    templates = exact[:3]
    mask = np.array([sum(r['frame'] == row['frame'] for r in exact_records) == 3 for row in exact_records])
    ranks = np.array([row['rank'] for row in exact_records])
    report['fixed_template_proxy'] = {}
    for name, reduce in [('first128', lambda x: x[:, :128]), ('mean4x128', lambda x: x.reshape(-1, 4, 128).mean(1)),
                         ('fitted128', lambda x: x @ projection), ('full512', lambda x: x)]:
        winners = (normalize(reduce(exact)) @ normalize(reduce(templates)).T).argmax(1)
        report['fixed_template_proxy'][name] = {'mismatches': int(np.count_nonzero(winners[mask] != ranks[mask])),
                                               'comparisons': int(mask.sum())}
    adapted = ProjectedAppearance(teacher, projection).eval()
    with torch.no_grad():
        torch.onnx.export(adapted, torch.zeros(1, 320, 320, 3), str(args.output / 'appearance-fitted128.onnx'),
                          opset_version=11, input_names=['images'], output_names=['output_1_feature'])
    onnx.checker.check_model(onnx.load(args.output / 'appearance-fitted128.onnx'))
    (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'evaluations': report['evaluations'], 'identity_proxy': report['fixed_template_proxy']}, indent=2))


if __name__ == '__main__':
    main()
