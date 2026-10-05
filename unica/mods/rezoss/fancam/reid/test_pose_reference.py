#!/usr/bin/env python3
"""Diagnostic decoder-contract fixtures, not model acceptance samples."""
import unittest

import numpy as np

from export_reid import native_pose


class PoseReferenceTests(unittest.TestCase):
    def test_upper_confidence_is_clamped_not_discarded(self):
        rows = np.zeros((174, 640), np.float32)
        rows[:87, 10] = 1.1
        rows[87:, 20] = 1.2
        points = native_pose(rows)
        np.testing.assert_array_equal(points[:, 2], 1)
        np.testing.assert_array_equal(points[:, :2], np.tile([5, 10], (87, 1)))

    def test_lower_confidence_is_clamped(self):
        points = native_pose(np.full((174, 640), -0.5, np.float32))
        np.testing.assert_array_equal(points[:, 2], 0)

    def test_confidence_is_minimum_of_axis_peaks(self):
        rows = np.zeros((174, 640), np.float32)
        rows[:87, 10] = 0.8
        rows[87:, 20] = 0.6
        np.testing.assert_allclose(native_pose(rows)[:, 2], 0.6)


if __name__ == '__main__':
    unittest.main()
