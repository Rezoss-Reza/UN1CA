#!/usr/bin/env python3
"""Parser regression fixtures; not model or tracking acceptance evidence."""
import struct
import unittest

from capture_tracking_results import unpack_frame


class TrackingRecordTests(unittest.TestCase):
    def fixture(self, track_id, feature_count=None):
        box = bytearray(36 if feature_count is None else 562 + 4 * feature_count)
        struct.pack_into('<4h', box, 0, 10, 20, 30, 40)
        struct.pack_into('<h', box, 16, 2)
        struct.pack_into('<h', box, 20, track_id)
        struct.pack_into('<h', box, 22, -1)
        if feature_count is not None:
            struct.pack_into('<I', box, 32, 1)
            struct.pack_into('<I', box, 558, feature_count)
        header = bytearray(36)
        struct.pack_into('<I', header, 0, 0x20400)
        struct.pack_into('<I', header, 8, 1)
        return bytes(header + box)

    def test_id_is_signed_short_not_reserved_int32(self):
        self.assertEqual(unpack_frame(self.fixture(2))[0]['track_id'], 2)
        self.assertEqual(unpack_frame(self.fixture(-1))[0]['track_id'], -1)

    def test_pose_with_feature_vector(self):
        self.assertEqual(unpack_frame(self.fixture(3, 128))[0]['track_id'], 3)

    def test_truncated_pose_rejected(self):
        with self.assertRaises(ValueError):
            unpack_frame(self.fixture(3, 128)[:-1])


if __name__ == '__main__':
    unittest.main()
