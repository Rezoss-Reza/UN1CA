#!/usr/bin/env python3
"""Person-only Locator contract inferred from the pinned Samsung decoder."""
import cv2
import numpy as np

INPUT_SIZE = 320
OUTPUT_SHAPE = (1, 6300, 29, 1)
HUMAN_CLASS = 2
ANCHORS = np.array([
    [[10, 13], [16, 30], [33, 23]],
    [[30, 61], [62, 45], [59, 119]],
    [[116, 90], [156, 198], [373, 326]],
], dtype=np.float32)
STRIDES = (8, 16, 32)


def preprocess(image):
    height, width = image.shape[:2]
    ratio = min(INPUT_SIZE / height, INPUT_SIZE / width)
    resized = cv2.resize(image, (round(width * ratio), round(height * ratio)))
    pad_x = (INPUT_SIZE - resized.shape[1]) / 2
    pad_y = (INPUT_SIZE - resized.shape[0]) / 2
    left, top = round(pad_x - 0.1), round(pad_y - 0.1)
    right, bottom = round(pad_x + 0.1), round(pad_y + 0.1)
    padded = cv2.copyMakeBorder(resized, top, bottom, left, right,
                               cv2.BORDER_CONSTANT, value=(114, 114, 114))
    tensor = cv2.cvtColor(padded, cv2.COLOR_BGR2RGB).astype(np.float32) / 255
    return tensor[None], (ratio, pad_x, pad_y)


def decode(output, object_threshold=0.25, score_threshold=0.5, iou_threshold=0.5):
    output = np.asarray(output, dtype=np.float32)
    if output.shape != OUTPUT_SHAPE or not np.isfinite(output).all():
        raise ValueError('Invalid Locator tensor shape or values')
    rows = output[0, :, :, 0]
    decoded = []
    offset = 0
    for anchors, stride in zip(ANCHORS, STRIDES):
        side = INPUT_SIZE // stride
        count = 3 * side * side
        head = rows[offset:offset + count].reshape(3, side, side, 29)
        offset += count
        for anchor_index, anchor in enumerate(anchors):
            data = head[anchor_index]
            classes = data[..., 5:].argmax(axis=-1)
            confidence = data[..., 5 + HUMAN_CLASS]
            valid = ((data[..., 4] >= object_threshold) &
                     (confidence >= score_threshold) & (classes == HUMAN_CLASS))
            y, x = np.nonzero(valid)
            if not len(x):
                continue
            chosen = data[y, x]
            centers = (chosen[:, :2] * 2 - 0.5 + np.stack((x, y), axis=1)) * stride
            sizes = chosen[:, 2:4] ** 2 * 4 * anchor
            boxes = np.concatenate((centers - sizes / 2, centers + sizes / 2), axis=1)
            decoded.extend(np.column_stack((boxes, confidence[y, x])).tolist())
    if not decoded:
        return np.empty((0, 5), dtype=np.float32)
    detections = np.asarray(decoded, dtype=np.float32)
    # Samsung sorts its candidate list stably; quantized class scores can tie.
    order = np.argsort(-detections[:, 4], kind='stable')
    kept = []
    while len(order):
        current = order[0]
        kept.append(current)
        others = order[1:]
        if not len(others):
            break
        low = np.maximum(detections[current, :2], detections[others, :2])
        high = np.minimum(detections[current, 2:4], detections[others, 2:4])
        intersection = np.maximum(high - low, 0).prod(axis=1)
        area = np.maximum(detections[:, 2:4] - detections[:, :2], 0).prod(axis=1)
        overlap = intersection / np.maximum(area[current] + area[others] - intersection, 1e-8)
        order = others[overlap <= iou_threshold]
    return detections[kept]


def restore_boxes(detections, transform, image_shape):
    result = detections.copy()
    ratio, pad_x, pad_y = transform
    result[:, [0, 2]] = (result[:, [0, 2]] - pad_x) / ratio
    result[:, [1, 3]] = (result[:, [1, 3]] - pad_y) / ratio
    result[:, [0, 2]] = result[:, [0, 2]].clip(0, image_shape[1])
    result[:, [1, 3]] = result[:, [1, 3]].clip(0, image_shape[0])
    return result
