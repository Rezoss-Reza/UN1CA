#!/usr/bin/env python3
"""Regression tests for Samsung class scores and quantized NMS ties."""
import unittest

import numpy as np
import torch
from torch import nn

from export_locator import PersonLocator
from locator_contract import ANCHORS, OUTPUT_SHAPE, decode


class Head(nn.Module):
    def __init__(self):
        super().__init__()
        self.nc, self.na = 80, 3
        self.stride = torch.tensor([8, 16, 32])
        self.anchor_grid = torch.tensor(ANCHORS).reshape(3, 1, 3, 1, 1, 2)
        self.m = nn.ModuleList(nn.Conv2d(1, 255, 1) for _ in range(3))


class DummyYOLO(nn.Module):
    def __init__(self):
        super().__init__()
        self.model = nn.ModuleList([Head()])

    def forward(self, images):
        heads = []
        for side in (40, 20, 10):
            head = torch.zeros(1, 3, side, side, 6)
            head[..., 4], head[..., 5] = 2, 10
            heads.append(head)
        return heads


class ContractTests(unittest.TestCase):
    def test_class_probability_is_not_joint_confidence(self):
        with torch.no_grad():
            output = PersonLocator(DummyYOLO())(torch.zeros(1, 320, 320, 3)).numpy()
        self.assertEqual(output.shape, OUTPUT_SHAPE)
        rows = output[0, :, :, 0]
        self.assertTrue((rows[:, 4] < 0.95).all())
        self.assertTrue((rows[:, 7] > 0.95).all())
        np.testing.assert_allclose(rows[:, 7], torch.sigmoid(torch.tensor(10.)).item())
        self.assertEqual(np.count_nonzero(np.delete(rows[:, 5:], 2, axis=1)), 0)

    def test_equal_score_nms_preserves_first_candidate(self):
        output = np.zeros(OUTPUT_SHAPE, np.float32)
        rows = output[0, :, :, 0]
        rows[:2, :4] = [0.5, 0.5, 0.9, 0.9]
        rows[:2, 4], rows[:2, 7] = 0.9, 0.999
        boxes = decode(output, score_threshold=0.95)
        self.assertEqual(len(boxes), 1)
        size = 0.9 ** 2 * 4 * ANCHORS[0, 0]
        np.testing.assert_allclose(boxes[0, :4], np.r_[4 - size / 2, 4 + size / 2], atol=1e-5)

    def test_objectness_and_negative_gates_remain(self):
        output = np.zeros(OUTPUT_SHAPE, np.float32)
        output[0, 0, 7, 0] = 1
        self.assertEqual(len(decode(output)), 0)


if __name__ == '__main__':
    unittest.main()
