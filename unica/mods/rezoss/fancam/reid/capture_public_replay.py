#!/usr/bin/env python3
"""Preserve exact public v19 frame blobs after matching saved tensor hashes."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import struct
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'experimental'))
from capture_tracking_results import unpack_frame


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--reference', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--adb', default='/mnt/c/platform-tools/adb.exe')
    parser.add_argument('--serial', default='R5CW72R7SDF')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    def root(command):
        return subprocess.check_output([args.adb, '-s', args.serial, 'exec-out',
                                       'su -c ' + shlex.quote(command)])
    folder = '/data/user/0/com.sec.android.app.vepreload/files/temp/raw'
    candidates = root('ls -1t ' + folder + '/UN1CA_FanCam_v19_Test_*_reframe.json').decode().splitlines()
    reference = json.loads(args.reference.read_text())
    for path in candidates:
        path = path.strip()
        reframe = json.loads(root('cat ' + shlex.quote(path)))
        if int(reframe['ANALYZED_INFO'][0]['REGIONS'][0]['END_TIME_US']) != 14954000:
            continue
        data = json.loads(root('cat ' + shlex.quote(path.removesuffix('_reframe.json') + '.json')))
        frames, blobs = [], []
        for info in data['ANALYZED_INFO']:
            for region in info['REGIONS']:
                for frame in region.get('DATA', []):
                    blob = bytes(int(value) & 255 for value in frame['RAWDATA'].split())
                    frames.append({'timestamp_us': int(frame['TIMESTAMP_US']),
                                   'boxes': unpack_frame(blob, True)})
                    blobs.append(blob)
        if frames != reference['frames']:
            continue
        manifest = {'frames': [], 'reference_sha256': hashlib.sha256(args.reference.read_bytes()).hexdigest(),
                    'matched_saved_v19_records': True, 'public_test_only': True}
        for index, (frame, blob) in enumerate(zip(frames, blobs)):
            name = 'frame-%04d.blob' % index
            (args.output / name).write_bytes(blob)
            manifest['frames'].append({'timestamp_us': frame['timestamp_us'], 'file': name,
                'bytes': len(blob), 'sha256': hashlib.sha256(blob).hexdigest(),
                'dimensions': list(struct.unpack_from('<2I', blob, 28)),
                'boxes': frame['boxes']})
        (args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
        print('Exact saved v19 records matched:', len(frames))
        return
    raise ValueError('No exact match to the verified public v19 Gallery records')


if __name__ == '__main__':
    main()
