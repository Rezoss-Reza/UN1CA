#!/usr/bin/env python3
"""Summarize the captured clip records without treating track boxes as crops."""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path

import cv2
import numpy as np

def main():
    parser = argparse.ArgumentParser()
    for name in ('video', 'gallery', 'baseline', 'evaluation', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    gallery = json.loads(args.gallery.read_text())
    baseline = json.loads((args.baseline / 'report.json').read_text())
    evaluation = json.loads((args.evaluation / 'report.json').read_text())
    ranks = defaultdict(Counter)
    for frame in gallery['frames']:
        boxes = sorted(frame['boxes'], key=lambda box: sum(box['xyxy'][::2]))
        for rank, box in enumerate(boxes):
            ranks[box['track_id']][rank] += 1
    holdout = [entry for entry in evaluation['tests'] if not entry['calibration_sample']]
    native_feature_cosines = [p['metrics']['output_1_feature']['cosine']
                             for f in baseline['frames'] for p in f['persons']]
    summary = {'gallery_frames': len(gallery['frames']),
               'gallery_track_rank_counts': {key: dict(value) for key, value in ranks.items()},
               'subject_track_ranges': {key: {'frames': len(value),
                                              'first_seconds': value[0]['timestamp_us'] / 1e6,
                                              'last_seconds': value[-1]['timestamp_us'] / 1e6}
                                        for key, value in gallery['postprocessed_track_boxes'].items()},
               'baseline_feature_cosine_min': min(native_feature_cosines),
               'baseline_feature_gate_failures': sum(value < 0.98 for value in native_feature_cosines),
               'recalibration_holdout': {'crops': len(holdout),
                                        'feature_cosine_min': min(e['feature_cosine'] for e in holdout),
                                        'feature_gate_failures': sum(e['feature_cosine'] < 0.98 for e in holdout),
                                        'pose_gate_failures': sum(e['pose_rmse'] > 0.2 for e in holdout)},
               'limitations': ['Selected middle person is inferred from user selection and initial position, not captured selection API.',
                               'No final editor crop rectangles captured.',
                               'Gallery record width 768 versus source width 720 remains a stride/display-contract question.']}
    panels = []
    capture = cv2.VideoCapture(str(args.video))
    for timestamp in (0, 3.4, 3.5, 5, 10, 14):
        capture.set(cv2.CAP_PROP_POS_MSEC, timestamp * 1000)
        ok, image = capture.read()
        if not ok:
            raise ValueError('Unavailable source frame')
        frame = min(gallery['frames'], key=lambda frame: abs(frame['timestamp_us'] / 1e6 - timestamp))
        for box in frame['boxes']:
            x1, y1, x2, y2 = box['xyxy']
            color = (0, 200, 255) if box['track_id'] == 2 else ((0, 0, 255) if box['track_id'] < 0 else (0, 220, 0))
            cv2.rectangle(image, (x1, y1), (x2, y2), color, 3)
            cv2.putText(image, 'ID ' + str(box['track_id']), (max(x1, 0), max(y1 - 8, 24)),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.8, color, 2)
        cv2.putText(image, str(timestamp) + ' seconds', (20, 50), cv2.FONT_HERSHEY_SIMPLEX, 1,
                    (0, 0, 0), 3)
        panels.append(cv2.resize(image, (360, 640)))
    capture.release()
    cv2.imwrite(str(args.output / 'gallery-track-overlay.jpg'),
                np.vstack((np.hstack(panels[:3]), np.hstack(panels[3:]))))
    (args.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
