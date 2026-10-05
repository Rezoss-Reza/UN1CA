#!/usr/bin/env python3
"""Save structural Gallery test evidence without media names or log contents."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import shlex
import struct
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', default='/mnt/c/platform-tools/adb.exe')
    parser.add_argument('--pid', type=int, required=True)
    parser.add_argument('--since', required=True, help='logcat timestamp, e.g. 10-04 13:39:00')
    parser.add_argument('--locator-sha256', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Refusing to overwrite evidence')

    def adb(*command):
        return subprocess.check_output([args.adb, *command])

    paths = ['/system/etc/mediacontextanalyzer/Locator.dlc',
             '/system/etc/mediacontextanalyzer/Reid.dlc', '/vendor/lib64/libSNPE.so']
    hashes = {path: adb('shell', 'su', '-c', 'sha256sum ' + path).decode().split()[0] for path in paths}
    if hashes[paths[0]] != args.locator_sha256:
        raise ValueError('Active Locator does not match test candidate')
    folder = '/data/user/0/com.sec.android.app.vepreload/files/temp/raw'
    files = adb('shell', 'su', '-c', 'ls -1t ' + folder + '/*.json').decode().splitlines()
    records = []
    for path in files[:8]:
        data = json.loads(adb('exec-out', 'su', '-c', 'cat ' + shlex.quote(path.strip())))
        frames = [frame for info in data.get('ANALYZED_INFO', [])
                  for region in info.get('REGIONS', []) for frame in region.get('DATA', [])]
        if not frames:
            continue
        counts = []
        for frame in frames:
            blob = bytes(int(value) & 255 for value in frame['RAWDATA'].split())
            if len(blob) < 36 or struct.unpack_from('<I', blob)[0] != 0x20400:
                raise ValueError('Unexpected analysis-record format')
            counts.append(struct.unpack_from('<I', blob, 8)[0])
        records.append({'frames': len(counts), 'box_counts': dict(Counter(counts)),
                        'frames_with_boxes': sum(count > 0 for count in counts)})
    events = Counter()
    for line in adb('logcat', '-d', '-v', 'threadtime').decode(errors='replace').splitlines():
        if line[:14] < args.since or not re.search(r'\s' + str(args.pid) + r'\s', line):
            continue
        for marker in ('onExportCompleted', 'onExportFailed', 'onEncodeError', 'BOX_CHOOSING to PREVIEW'):
            if marker in line:
                events[marker] += 1
        match = re.search(r'Execute for /(Locator|Reid)\.dlc : (\d+)', line)
        if match:
            events[match.group(1) + '_execute_status_' + match.group(2)] += 1
    report = {'hashes': hashes, 'recent_results': records, 'events': dict(events),
              'limitations': 'Structural results and callbacks, not visual-quality assessment.'}
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
