#!/usr/bin/env python3
"""Independent host integer reference for probe_lora_matmul.c's fixed fixture."""
import argparse
import json
import math
from pathlib import Path
import struct

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('weights_u16', type=Path)
parser.add_argument('device_output_u16', type=Path)
parser.add_argument('weight_scale', type=float)
args = parser.parse_args()
if not math.isfinite(args.weight_scale) or args.weight_scale <= 0:
    parser.error('Invalid scale')
weights = args.weights_u16.read_bytes()
output = args.device_output_u16.read_bytes()
if len(weights) != 3072 * 32 * 2 or len(output) != 32 * 2:
    parser.error('Unexpected fixture dimensions')
w = struct.unpack('<' + 'H' * (3072 * 32), weights)
actual = struct.unpack('<32H', output)
scale = struct.unpack('<f', struct.pack('<f', args.weight_scale))[0]
rows = []
for n in range(32):
    accumulator = sum((((k * 17) % 257) - 128) * (w[k * 32 + n] - 32768) for k in range(3072))
    expected = accumulator * scale / 1024
    expected_q = round(expected * 4096) + 32768
    if not 0 <= expected_q <= 65535:
        parser.error('Reference saturates; fixture unsuitable')
    value = (actual[n] - 32768) / 4096
    rows.append(dict(index=n, actual=value, reference=expected,
                     absolute_error=abs(value - expected), lsb_error=abs(actual[n] - expected_q)))
max_lsb = max(row['lsb_error'] for row in rows)
print(json.dumps(dict(max_absolute_error=max(row['absolute_error'] for row in rows),
                      rmse=math.sqrt(sum(row['absolute_error'] ** 2 for row in rows) / 32),
                      max_lsb_error=max_lsb, strict_limit_lsb=2, strict_pass=max_lsb <= 2,
                      scope='one recovered LoRA matrix, synthetic activation; no decoder equivalence',
                      outputs=rows), indent=2))
