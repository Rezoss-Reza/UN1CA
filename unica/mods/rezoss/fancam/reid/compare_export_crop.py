#!/usr/bin/env python3
"""Estimate rendered crop geometry from matching source/export image features.

These are measured image transforms, not native editor crop keyframes or IDs.
"""
import argparse
import hashlib
import json
from pathlib import Path

import cv2
import numpy as np


def fit_transform(source_points, export_points, output_size):
    if len(source_points) < 12:
        return None
    matrix, mask = cv2.estimateAffinePartial2D(source_points, export_points,
            method=cv2.RANSAC, ransacReprojThreshold=2.5, maxIters=3000,
            confidence=0.999, refineIters=20)
    if matrix is None or mask is None:
        return None
    mask = mask[:, 0].astype(bool)
    if mask.sum() < 12 or mask.mean() < 0.3:
        return None
    linear = matrix[:, :2]
    scale = float(np.sqrt(np.linalg.det(linear)))
    rotation = float(np.degrees(np.arctan2(linear[1, 0], linear[0, 0])))
    projected = source_points @ linear.T + matrix[:, 2]
    error = float(np.median(np.linalg.norm(projected[mask] - export_points[mask], axis=1)))
    if not np.isfinite(matrix).all() or not 0.5 < scale < 6 or abs(rotation) > 1 or error > 1.5:
        return None
    width, height = output_size
    corners = np.array([[0, 0], [width, 0], [width, height], [0, height]], np.float64)
    restored = (corners - matrix[:, 2]) @ np.linalg.inv(linear).T
    return {'source_to_export': matrix.tolist(), 'scale': scale, 'rotation_degrees': rotation,
            'inliers': int(mask.sum()), 'matches': len(mask), 'median_reprojection_px': error,
            'source_crop_xyxy': [float(restored[:, 0].min()), float(restored[:, 1].min()),
                                 float(restored[:, 0].max()), float(restored[:, 1].max())]}


def main():
    parser = argparse.ArgumentParser()
    for name in ('source', 'export', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--step', type=int, default=3)
    args = parser.parse_args()
    if args.step < 1:
        parser.error('Positive frame step required')
    args.output.mkdir(parents=True, exist_ok=False)
    cv2.setNumThreads(4)
    cv2.setRNGSeed(42)
    source = cv2.VideoCapture(str(args.source))
    exported = cv2.VideoCapture(str(args.export))
    fps = exported.get(cv2.CAP_PROP_FPS)
    source_fps = source.get(cv2.CAP_PROP_FPS)
    if fps <= 0 or source_fps <= 0:
        raise ValueError('Invalid frame rate')
    size = tuple(int(exported.get(key)) for key in (cv2.CAP_PROP_FRAME_WIDTH, cv2.CAP_PROP_FRAME_HEIGHT))
    original_size = tuple(int(source.get(key)) for key in (cv2.CAP_PROP_FRAME_WIDTH, cv2.CAP_PROP_FRAME_HEIGHT))
    sift = cv2.SIFT_create(nfeatures=2200)
    matcher = cv2.BFMatcher(cv2.NORM_L2)
    report = {'source_sha256': hashlib.sha256(args.source.read_bytes()).hexdigest(),
              'export_sha256': hashlib.sha256(args.export.read_bytes()).hexdigest(),
              'source_size': original_size, 'export_size': size, 'frames': [],
              'limitations': ['Image-feature estimated crop, NOT native keyframes or track IDs.',
                             'Source timing searched within two frames of export timestamp; no edits/speed changes supported.']}
    panels, frame_index = [], 0
    try:
        while True:
            ok, image = exported.read()
            if not ok:
                break
            index = frame_index
            frame_index += 1
            if index % args.step:
                continue
            timestamp = index / fps
            keys, descriptors = sift.detectAndCompute(cv2.cvtColor(image, cv2.COLOR_BGR2GRAY), None)
            best, best_source = None, None
            nominal = round(timestamp * source_fps)
            for offset in (0, -1, 1, -2, 2):
                selected = nominal + offset
                if selected < 0:
                    continue
                source.set(cv2.CAP_PROP_POS_FRAMES, selected)
                valid, original = source.read()
                if not valid or descriptors is None:
                    continue
                original_keys, original_descriptors = sift.detectAndCompute(cv2.cvtColor(original, cv2.COLOR_BGR2GRAY), None)
                if original_descriptors is None:
                    continue
                pairs = matcher.knnMatch(original_descriptors, descriptors, k=2)
                good = [pair[0] for pair in pairs if len(pair) == 2 and pair[0].distance < 0.72 * pair[1].distance]
                a = np.array([original_keys[m.queryIdx].pt for m in good], np.float32).reshape(-1, 2)
                b = np.array([keys[m.trainIdx].pt for m in good], np.float32).reshape(-1, 2)
                fitted = fit_transform(a, b, size)
                if fitted is None:
                    continue
                fitted.update(source_frame=selected, export_frame=index, timestamp_seconds=timestamp)
                if best is None or (fitted['inliers'], -fitted['median_reprojection_px']) > (best['inliers'], -best['median_reprojection_px']):
                    best, best_source = fitted, original
            report['frames'].append(best or {'export_frame': index, 'timestamp_seconds': timestamp, 'unmatched': True})
            if best and index % 30 == 0:
                x1, y1, x2, y2 = map(round, best['source_crop_xyxy'])
                cv2.rectangle(best_source, (x1, y1), (x2, y2), (0, 220, 255), 3)
                left = cv2.resize(best_source, (270, 480))
                right = cv2.resize(image, (270, 480))
                panel = np.hstack((left, right))
                cv2.putText(panel, '%.1fs zoom %.3fx' % (timestamp, best['scale']), (10, 25),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.65, (0, 0, 0), 4)
                cv2.putText(panel, '%.1fs zoom %.3fx' % (timestamp, best['scale']), (10, 25),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.65, (255, 255, 255), 1)
                panels.append(panel)
                print(timestamp, 'zoom', round(best['scale'], 4), 'crop', [round(v, 1) for v in best['source_crop_xyxy']], flush=True)
            (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    finally:
        source.release()
        exported.release()
    if panels:
        while len(panels) % 3:
            panels.append(np.zeros_like(panels[0]))
        cv2.imwrite(str(args.output / 'source-export-comparison.jpg'),
                    np.vstack([np.hstack(panels[i:i + 3]) for i in range(0, len(panels), 3)]))
    print('Measured', sum('scale' in frame for frame in report['frames']), '/', len(report['frames']), 'sampled frames')


if __name__ == '__main__':
    main()
