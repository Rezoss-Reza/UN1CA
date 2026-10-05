#!/usr/bin/env python3
"""Capture only boxes/IDs from the latest user-requested 15-second clip."""
import argparse
from collections import defaultdict
import hashlib
import json
import math
from pathlib import Path
import re
import shlex
import struct
import subprocess


def unpack_frame(blob, feature_metadata=False):
    if len(blob) < 36 or struct.unpack_from('<I', blob)[0] != 0x20400:
        raise ValueError('Unexpected frame serialization')
    count = struct.unpack_from('<I', blob, 8)[0]
    boxes, offset = [], 36
    for _ in range(count):
        if offset + 36 > len(blob):
            raise ValueError('Truncated box')
        xyxy = list(struct.unpack_from('<4h', blob, offset))
        category = struct.unpack_from('<h', blob, offset + 16)[0]
        track_id = struct.unpack_from('<h', blob, offset + 20)[0]
        pose = struct.unpack_from('<I', blob, offset + 32)[0]
        boxes.append({'xyxy': xyxy, 'category': category, 'track_id': track_id})
        if pose:
            if offset + 562 > len(blob):
                raise ValueError('Truncated pose')
            features = struct.unpack_from('<I', blob, offset + 558)[0]
            if features > 512:
                raise ValueError('Unexpected feature count')
            if offset + 562 + features * 4 > len(blob):
                raise ValueError('Truncated features')
            if feature_metadata:
                raw = blob[offset + 562:offset + 562 + features * 4]
                values = struct.unpack('<' + 'f' * features, raw)
                if not all(math.isfinite(value) for value in values):
                    raise ValueError('Nonfinite feature values')
                boxes[-1]['feature_metadata'] = {'elements': features,
                    'negative_elements': sum(value < 0 for value in values),
                    'sha256': hashlib.sha256(raw).hexdigest()}
            offset += 562 + features * 4
        else:
            offset += 36
    if offset != len(blob):
        raise ValueError('Unexpected trailing frame data')
    return boxes


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--adb', default='/mnt/c/platform-tools/adb.exe')
    parser.add_argument('--pid', type=int, required=True)
    parser.add_argument('--feature-metadata', action='store_true',
                        help='Public test clip only: retain vector hashes/sign counts, not embeddings')
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Refusing to overwrite evidence')

    def root(command, mode='shell'):
        return subprocess.check_output([args.adb, mode, 'su -c ' + shlex.quote(command)])

    folder = '/data/user/0/com.sec.android.app.vepreload/files/temp/raw'
    paths = root('ls -1t ' + folder + '/*_reframe.json').decode().splitlines()
    path = paths[0].strip()
    reframe = json.loads(root('cat ' + shlex.quote(path), 'exec-out'))
    detection = json.loads(root('cat ' + shlex.quote(path.removesuffix('_reframe.json') + '.json'), 'exec-out'))
    region = reframe['ANALYZED_INFO'][0]['REGIONS'][0]
    if int(region['END_TIME_US']) != 14954000:
        raise ValueError('Latest result is not the requested clip duration')
    frames, by_id = [], defaultdict(list)
    dimensions = set()
    for info in detection['ANALYZED_INFO']:
        for data_region in info['REGIONS']:
            for frame in data_region.get('DATA', []):
                blob = bytes(int(value) & 255 for value in frame['RAWDATA'].split())
                shape = tuple(struct.unpack_from('<2I', blob, 28))
                if shape not in ((720, 1280), (768, 1280)):
                    raise ValueError('Unexpected clip dimensions')
                dimensions.add(shape)
                timestamp = int(frame['TIMESTAMP_US'])
                boxes = unpack_frame(blob, args.feature_metadata)
                frames.append({'timestamp_us': timestamp, 'boxes': boxes})
                for box in boxes:
                    by_id[box['track_id']].append({'timestamp_us': timestamp, 'xyxy': box['xyxy']})
    target_data = {}
    for key, value in region['REFRAME_BY_ID'].items():
        target_data[key] = sorted([{'timestamp_us': int(item['TIMESTAMP_US']),
                                    'track_id': int(item['ID']),
                                    'xyxy': [int(v.strip()) for v in item['BOX'].split(',')],
                                    'is_detected': int(item['IS_DETECTED'])}
                                   for item in value['BOX_INFO'].values()], key=lambda item: item['timestamp_us'])
    selected_ids = []
    logs = subprocess.check_output([args.adb, 'logcat', '-d', '-v', 'threadtime']).decode(errors='replace')
    for line in logs.splitlines():
        if not re.search(r'\s' + str(args.pid) + r'\s', line):
            continue
        if 'requestPrepareKenBurnsInfo' in line:
            match = re.search(r'\bid\s*[=:]\s*(-?\d+)', line)
            if match:
                selected_ids.append(int(match.group(1)))
    report = {'duration_us': int(region['END_TIME_US']), 'source_dimensions': [720, 1280],
              'record_dimensions': sorted(dimensions),
              'frames': frames, 'tracks': dict(by_id), 'postprocessed_track_boxes': target_data,
              'selected_ids_from_process_logs': selected_ids,
              'limitations': 'BOX_INFO is subject-track data, not final editor crop rectangles. Latest result matched by duration/dimensions, not media-content hash.'}
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print('Frames:', len(frames), 'selected IDs:', selected_ids)
    for key, values in target_data.items():
        print('ID', key, 'frames', len(values), 'first/last seconds', values[0]['timestamp_us'] / 1e6,
              values[-1]['timestamp_us'] / 1e6)


if __name__ == '__main__':
    main()
