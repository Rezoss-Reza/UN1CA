#!/usr/bin/env python3
"""Focused contract fixtures; these do not prove person tracking."""
import unittest
import numpy as np
from audit_pose_gates import FACE_ROWS, gates
from compare_decoded_editor import normalize_source


class AuditTests(unittest.TestCase):
    def test_pose_count_boundary(self):
        points = np.zeros((87, 3), np.float32)
        points[:4, 2] = 0.9
        self.assertFalse(gates(points)[0])
        points[4, 2] = 0.9
        self.assertTrue(gates(points)[0])

    def test_face_count_boundary(self):
        points = np.zeros((87, 3), np.float32)
        points[list(FACE_ROWS[:23]), 2] = 0.9
        self.assertFalse(gates(points)[1])
        points[FACE_ROWS[23], 2] = 0.9
        self.assertTrue(gates(points)[1])

    def test_confidence_is_strict(self):
        points = np.zeros((87, 3), np.float32)
        points[:, 2] = 0.5
        self.assertEqual(gates(points), [False, False])
        points[:, 2] = np.nextafter(np.float32(0.5), np.float32(1))
        self.assertEqual(gates(points), [True, True])

    def test_source_ids_are_not_code_changes(self):
        self.assertEqual(normalize_source('.source "map-a"\nconst/4 v0, 0x0\n'),
                         normalize_source('.source "map-b"\nconst/4 v0, 0x0\n'))

    def test_instructions_are_not_normalized_away(self):
        self.assertNotEqual(normalize_source('const/4 v0, 0x0\n'),
                            normalize_source('const/4 v0, 0x1\n'))


if __name__ == '__main__':
    unittest.main()
