#!/usr/bin/env python3
"""Compare exact stateless Samsung pose gates with saved public-clip outputs."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess

import numpy as np
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from elftools.elf.elffile import ELFFile

LIBRARY_SHA256 = 'e8921a043f2050d69d11f906820a400054c835bebd0f743bff7a33405c42f835'
FACE_ROWS = (0, 1, 2, 17, 18, 19, 20, *range(63, 83))


def gates(points):
    return [bool(np.count_nonzero(points[:, 2] > 0.5) > 4),
            bool(np.count_nonzero(points[list(FACE_ROWS), 2] > 0.5) > 23)]


def main():
    parser = argparse.ArgumentParser()
    for name in ('library', 'evaluation', 'probe', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--adb', default='/mnt/c/platform-tools/adb.exe')
    args = parser.parse_args()
    if hashlib.sha256(args.library.read_bytes()).hexdigest() != LIBRARY_SHA256:
        raise ValueError('Unreviewed library ABI')
    args.output.mkdir(parents=True, exist_ok=False)
    evidence = {}
    assembly = []
    with args.library.open('rb') as stream:
        elf = ELFFile(stream)
        disassembler = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
        for symbol in elf.get_section_by_name('.dynsym').iter_symbols():
            if not symbol['st_value'] or not symbol['st_size']:
                continue
            if not any(token in symbol.name for token in ('verify_pose', 'verify_face',
                    'keypoint_activation', 'TrackerParameters', 'is_clean_feature',
                    'tracking7Tracker', 'calculate_cosine_distance', 'expired',
                    'check_enable_track', 'pose_data_t', 'set_parameters')):
                continue
            start, size = symbol['st_value'], symbol['st_size']
            section = next(s for s in elf.iter_sections()
                           if s['sh_addr'] <= start and start + size <= s['sh_addr'] + s['sh_size'])
            data = section.data()[start - section['sh_addr']:start - section['sh_addr'] + size]
            evidence[symbol.name] = {'address': hex(start), 'size': size, 'sha256': hashlib.sha256(data).hexdigest()}
            if symbol['st_info']['type'] == 'STT_FUNC':
                assembly.append('\n' + symbol.name + ' @ ' + hex(start))
                assembly.extend('%08x  %s %s' % (i.address, i.mnemonic, i.op_str)
                                for i in disassembler.disasm(data, start))
            else:
                evidence[symbol.name]['hex'] = data.hex()
    (args.output / 'native-disassembly.txt').write_text('\n'.join(assembly) + '\n')
    (args.output / 'native-symbols.json').write_text(json.dumps(evidence, indent=2) + '\n')
    records, inputs = [], []
    # Threshold fixtures validate the inspected ABI and strict comparisons, not tracking quality.
    for score in (0.0, 0.5, 0.5001, 1.0):
        points = np.zeros((87, 3), np.float32)
        points[:, 2] = score
        records.append({'fixture_score': score, 'expected': gates(points)})
        inputs.append(np.concatenate((np.zeros(3, np.float32), points.ravel())))
    for path in sorted(args.evaluation.glob('frame-*-person-*-pose.raw')):
        match = re.fullmatch(r'frame-(\d+)-person-(\d+)-pose.raw', path.name)
        rows = np.fromfile(path, np.float32)
        if rows.size != 174 * 640 or not np.isfinite(rows).all():
            raise ValueError('Invalid preserved pose tensor')
        x, y = rows.reshape(174, 640).reshape(2, 87, 640)
        scores = np.clip(np.minimum(x.max(axis=1), y.max(axis=1)), 0, 1)
        points = np.column_stack((x.argmax(axis=1) / 2, y.argmax(axis=1) / 2, scores)).astype(np.float32)
        records.append({'frame': int(match[1]), 'rank': int(match[2]),
            'seconds': int(match[1]) / 30, 'expected': gates(points),
            'confident_points': int(np.count_nonzero(scores > 0.5)),
            'confident_face_points': int(np.count_nonzero(scores[list(FACE_ROWS)] > 0.5)),
            'body_confident_points': int(np.count_nonzero(scores[:21] > 0.5))})
        inputs.append(np.concatenate((np.zeros(3, np.float32), points.ravel())))
    if len(records) <= 4:
        raise ValueError('No public inference evidence')
    batch = args.output / 'pose-gates-input.raw'
    np.stack(inputs).astype(np.float32).tofile(batch)
    remote = '/data/local/tmp/unica-pose-gates-' + args.output.name
    def shell(command):
        return subprocess.check_output([args.adb, 'shell', 'su -c ' + shlex.quote(command)], text=True).strip()
    if shell('sha256sum /system/lib64/libauto-reframing-arm64-v8a.so').split()[0] != LIBRARY_SHA256:
        raise ValueError('Device ABI hash mismatch')
    shell('test ! -e ' + shlex.quote(remote) + ' && mkdir ' + shlex.quote(remote))
    for local in (args.probe, batch):
        windows = subprocess.check_output(['wslpath', '-w', str(local.resolve())], text=True).strip()
        subprocess.check_call([args.adb, 'push', windows, remote + '/'], stdout=subprocess.DEVNULL)
    shell('chmod 755 ' + shlex.quote(remote + '/' + args.probe.name))
    result = shell(shlex.join(['env', 'LD_LIBRARY_PATH=/system/lib64:/vendor/lib64',
        remote + '/' + args.probe.name, '/system/lib64/libauto-reframing-arm64-v8a.so',
        remote + '/' + batch.name]))
    lines = result.splitlines()
    if len(lines) != len(records):
        raise ValueError('Unexpected native result count')
    for index, (record, line) in enumerate(zip(records, lines)):
        actual_index, pose, face = map(int, line.split())
        if actual_index != index or [bool(pose), bool(face)] != record['expected']:
            raise ValueError('Native gate disagrees with inspected contract')
        record['native_pose_gate'], record['native_face_gate'] = bool(pose), bool(face)
    report = {'library_sha256': LIBRARY_SHA256, 'records': records,
        'gap_records': [r for r in records if 3 <= r.get('seconds', -1) <= 4.8],
        'limitations': ['Stateless 87-point validators only; not complete production association.',
            'Saved isolated v19 tensors are not captured Gallery pose tensors.',
            'Pose coordinates remain in input space; these two gates inspect confidence only.',
            'Horizontal rank is not independently annotated identity.']}
    (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print('Native/reference agreement:', len(records), '/', len(records))
    print(json.dumps(report['gap_records'], indent=2))


if __name__ == '__main__':
    main()
