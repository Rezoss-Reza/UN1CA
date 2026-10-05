#!/usr/bin/env python3
"""Run native tracker replay only on hash-matched public Gallery frame blobs."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import struct
import subprocess

PIN = 'e8921a043f2050d69d11f906820a400054c835bebd0f743bff7a33405c42f835'


def main():
    parser = argparse.ArgumentParser()
    for name in ('records', 'probe', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--adb', default='/mnt/c/platform-tools/adb.exe')
    parser.add_argument('--serial', default='R5CW72R7SDF')
    parser.add_argument('--video-defaults', action='store_true')
    parser.add_argument('--unit-features', action='store_true')
    parser.add_argument('--live-config', type=Path)
    args = parser.parse_args()
    manifest = json.loads((args.records / 'manifest.json').read_text())
    if not manifest['matched_saved_v19_records'] or not manifest['public_test_only']:
        raise ValueError('Not the authorized public v19 capture')
    args.output.mkdir(parents=True, exist_ok=False)
    parameter_file = None
    if args.live_config:
        snapshots = [json.loads(line) for line in args.live_config.read_text().splitlines() if line.strip()]
        objects = [obj for snapshot in snapshots for obj in snapshot.get('objects', [])]
        unique = {tuple(obj['tracker_parameter_words']) for obj in objects}
        if len(unique) != 1 or any(obj['tracker_dimensions'] != [768, 1280] for obj in objects):
            raise ValueError('Missing, conflicting, or unexpected live tracker configuration')
        words = unique.pop()
        if len(words) != 40:
            raise ValueError('Wrong parameter count')
        parameter_file = args.output / 'parameters.bin'
        parameter_file.write_bytes(struct.pack('<40I', *words))
    batch = args.output / 'input.bin'
    with batch.open('xb') as stream:
        stream.write(struct.pack('<2I', 0x31525046, len(manifest['frames'])))
        for frame in manifest['frames']:
            data = (args.records / frame['file']).read_bytes()
            if hashlib.sha256(data).hexdigest() != frame['sha256']:
                raise ValueError('Changed public blob')
            stream.write(struct.pack('<qI', frame['timestamp_us'], len(data)))
            stream.write(data)
    def shell(command):
        return subprocess.check_output([args.adb, '-s', args.serial, 'exec-out',
                                       'su -c ' + shlex.quote(command)], stderr=subprocess.PIPE)
    library = '/system/lib64/libauto-reframing-arm64-v8a.so'
    if shell('sha256sum ' + library).decode().split()[0] != PIN:
        raise ValueError('Device native hash mismatch')
    remote = '/data/local/tmp/unica-tracker-' + args.output.name
    shell('test ! -e ' + shlex.quote(remote) + ' && mkdir ' + shlex.quote(remote))
    for path in (args.probe, batch, *([parameter_file] if parameter_file else [])):
        windows = subprocess.check_output(['wslpath', '-w', str(path.resolve())], text=True).strip()
        subprocess.check_call([args.adb, '-s', args.serial, 'push', windows, remote + '/'], stdout=subprocess.DEVNULL)
    shell('chmod 755 ' + shlex.quote(remote + '/' + args.probe.name))
    try:
        command = ['env', 'LD_LIBRARY_PATH=/system/lib64:/vendor/lib64',
                   remote + '/' + args.probe.name, library, remote + '/' + batch.name]
        if args.video_defaults:
            command.append('video-defaults')
        if args.unit_features:
            command.append('unit-features')
        if parameter_file:
            command.append('parameters=' + remote + '/' + parameter_file.name)
        result = shell(shlex.join(command))
    except subprocess.CalledProcessError as error:
        (args.output / 'failed-stdout.txt').write_bytes(error.output)
        (args.output / 'failed-stderr.txt').write_bytes(error.stderr)
        raise
    (args.output / 'raw-stdout.txt').write_bytes(result)
    report = json.loads(result)
    if len(report['frames']) != len(manifest['frames']):
        raise ValueError('Incomplete replay')
    report['input_sha256'] = hashlib.sha256(batch.read_bytes()).hexdigest()
    report['library_sha256'] = PIN
    report['constructor_parameters'] = 'VideoTrack' if args.video_defaults else 'Tracker'
    report['feature_normalization_experiment'] = args.unit_features
    report['live_config_sha256'] = hashlib.sha256(args.live_config.read_bytes()).hexdigest() if args.live_config else None
    report['limitations'] = ['Native constructor defaults, not captured live Gallery parameter memory.',
        'Packed Gallery poses have integer coordinate/score rounding.',
        'Post-update clean-feature query uses all frame boxes and no occlusion boxes; diagnostic only.']
    if args.live_config:
        report['limitations'][0] = 'Parameters captured during fresh analysis with installed ReID v2, not the earlier v19 session.'
    report['mismatches'] = []
    for index, (frame, source) in enumerate(zip(report['frames'], manifest['frames'])):
        expected = [box['track_id'] for box in source['boxes']]
        if frame['timestamp_us'] != source['timestamp_us']:
            raise ValueError('Timestamp mismatch')
        if frame['replay_ids'] != expected:
            report['mismatches'].append({'frame': index, 'timestamp_us': frame['timestamp_us'],
                                         'expected': expected, 'actual': frame['replay_ids']})
    (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print('Frames:', len(report['frames']), 'mismatches:', len(report['mismatches']),
          'normal cleanup:', report['normal_cleanup'])
    print(json.dumps(report['mismatches'][:5], indent=2))


if __name__ == '__main__':
    main()
