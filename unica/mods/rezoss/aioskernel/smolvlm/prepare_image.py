#!/usr/bin/env python3
"""Prepare one global 512px image, matching the pinned non-split export."""
import argparse
from pathlib import Path
import numpy as np
from PIL import Image, ImageOps

parser = argparse.ArgumentParser()
parser.add_argument('image', type=Path)
parser.add_argument('output', type=Path)
args = parser.parse_args()
if args.output.exists():
    parser.error(f'Refusing to overwrite {args.output}')
with Image.open(args.image) as original:
    image = ImageOps.exif_transpose(original).convert('RGB')
    image = image.resize((512, 512), Image.Resampling.LANCZOS)
    pixels = (np.asarray(image, dtype=np.float32) / 255.0 - 0.5) / 0.5
    tensor = np.ascontiguousarray(pixels.transpose(2, 0, 1)[None], dtype='<f4')
    with args.output.open('xb') as stream:
        stream.write(tensor.tobytes())
print(f'{args.output}: float32 NCHW {tensor.shape}, {tensor.nbytes} bytes')
