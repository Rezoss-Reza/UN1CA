#!/usr/bin/env python3
"""Geometry-estimator regression fixtures; not model acceptance evidence."""
import unittest

import numpy as np

from compare_export_crop import fit_transform


class ExportCropTests(unittest.TestCase):
    def test_known_zoom_and_translation(self):
        points = np.random.default_rng(42).uniform(0, 700, (100, 2)).astype(np.float32)
        result = fit_transform(points, points * 2 + [-100, -200], (720, 1280))
        self.assertIsNotNone(result)
        np.testing.assert_allclose(result['source_crop_xyxy'], [50, 100, 410, 740], atol=0.001)
        self.assertAlmostEqual(result['scale'], 2, places=5)

    def test_rotated_frames_rejected(self):
        points = np.random.default_rng(42).uniform(0, 700, (100, 2)).astype(np.float32)
        angle = np.radians(10)
        matrix = np.array([[np.cos(angle), -np.sin(angle)], [np.sin(angle), np.cos(angle)]])
        self.assertIsNone(fit_transform(points, points @ matrix.T, (720, 1280)))

    def test_insufficient_points_rejected(self):
        self.assertIsNone(fit_transform(np.zeros((5, 2)), np.zeros((5, 2)), (720, 1280)))


if __name__ == '__main__':
    unittest.main()
