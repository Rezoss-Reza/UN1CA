#!/usr/bin/env python3
"""Compare learned OSNet feature reductions without changing shipped models."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

import numpy as np
import torch

from diagnose_video import cosine
from export_reid import OSNET_HASH, Preprocess


def main():
    parser = argparse.ArgumentParser()
    for name in ('baseline', 'upstream', 'weights', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists() or hashlib.sha256(args.weights.read_bytes()).hexdigest() != OSNET_HASH:
        raise ValueError('Output exists or weights hash mismatch')
    spec = importlib.util.spec_from_file_location('reviewed_osnet', args.upstream / 'torchreid/models/osnet.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    weights = torch.load(args.weights, map_location='cpu', weights_only=True)
    weights = weights.get('state_dict', weights)
    weights = {key.removeprefix('module.'): value for key, value in weights.items()}
    model = module.osnet_x0_25(num_classes=weights['classifier.weight'].shape[0], pretrained=False)
    model.load_state_dict(weights, strict=True)
    model.eval()
    torch.set_num_threads(4)
    prep = Preprocess((256, 128))
    baseline = json.loads((args.baseline / 'report.json').read_text())
    reductions = ('first128', 'mean4x128', 'full512')
    templates = {}
    result = {name: [] for name in reductions}
    with torch.no_grad():
        for frame in baseline['frames']:
            if len(frame['persons']) != 3:
                continue
            values = []
            for rank in range(3):
                raw = np.fromfile(args.baseline / ('frame-%04d' % frame['frame']) /
                                  ('person-%d.raw' % rank), np.float32).reshape(1, 320, 320, 3)
                values.append(model(prep(torch.from_numpy(raw))).numpy()[0])
            full = np.array(values)
            representations = {'first128': full[:, :128], 'mean4x128': full.reshape(3, 4, 128).mean(axis=1),
                               'full512': full}
            for name, features in representations.items():
                if name not in templates:
                    templates[name] = features.copy()
                matrix = np.array([[cosine(a, b) for b in templates[name]] for a in features])
                result[name].append({'frame': frame['frame'], 'cosines': matrix.tolist(),
                                     'winners': matrix.argmax(axis=1).tolist(),
                                     'mismatches': int(np.count_nonzero(matrix.argmax(axis=1) != np.arange(3)))})
    report = {'methods': result, 'summary': {name: {'mismatches': sum(e['mismatches'] for e in entries),
                                                  'comparisons': len(entries) * 3} for name, entries in result.items()},
              'limitations': 'Horizontal ranks assume no order crossing; fixed first-frame templates are not Samsung temporal association. No fitting on this clip.'}
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(report['summary'])


if __name__ == '__main__':
    main()
