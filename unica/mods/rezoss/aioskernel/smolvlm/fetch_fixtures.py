#!/usr/bin/env python3
"""Download and hash-check the three public images used for device validation."""
import hashlib
import pathlib
import sys
import urllib.request

fixtures = {
    'cat.jpg': ('https://huggingface.co/datasets/huggingface/documentation-images/resolve/main/pipeline-cat-chonk.jpeg',
                '88b9eb3e6ec026c5ab96aab516f4a4a2773522a474abc52e934a52d86bba21e5'),
    'bus.jpg': ('https://raw.githubusercontent.com/ultralytics/assets/main/im/bus.jpg',
                'c02019c4979c191eb739ddd944445ef408dad5679acab6fd520ef9d434bfbc63'),
    'dog.jpg': ('https://raw.githubusercontent.com/pytorch/hub/master/images/dog.jpg',
                'f3f87bb8ab3c26c7ecfd3ac60421d7f32b0503d1d6c5baf8bac42ed93d86351a'),
}
target = pathlib.Path(sys.argv[1])
target.mkdir(parents=True, exist_ok=True)
for name, (url, expected) in fixtures.items():
    path = target / name
    content = path.read_bytes() if path.exists() else urllib.request.urlopen(url, timeout=60).read()
    if hashlib.sha256(content).hexdigest() != expected:
        raise SystemExit(f'Fixture hash mismatch: {name}')
    if not path.exists():
        with path.open('xb') as out:
            out.write(content)
    print(path)
