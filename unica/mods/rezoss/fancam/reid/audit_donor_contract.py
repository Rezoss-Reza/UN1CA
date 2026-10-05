#!/usr/bin/env python3
"""Read-only donor/device comparison and public-clip association-gap audit."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import shlex
import subprocess
import xml.etree.ElementTree as ET
import zipfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--donor-system', type=Path, required=True)
    parser.add_argument('--gallery', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--adb', default='/mnt/c/platform-tools/adb.exe')
    parser.add_argument('--serial', default='R5CW72R7SDF')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)

    def read_device(path):
        return subprocess.check_output([args.adb, '-s', args.serial, 'exec-out',
                                        'su -c ' + shlex.quote('cat ' + shlex.quote(path))])

    def command(text):
        return subprocess.check_output([args.adb, '-s', args.serial, 'shell',
                                        'su -c ' + shlex.quote(text)]).decode().strip()

    report = {'libraries': {}, 'configuration': {}, 'aliases': {}, 'gap': {},
              'limitations': ['Static code equality does not prove equal runtime parameters.',
                              'Subject BOX_INFO is not the final editor crop.',
                              'No donor inference or donor video is available.']}
    names = ('libauto-reframing-arm64-v8a.so', 'libmediacontextanalyzer.so')
    for name in names:
        local = args.donor_system / 'lib64' / name
        live = read_device('/system/lib64/' + name)
        (args.output / ('device-' + name)).write_bytes(live)
        donor = local.read_bytes()
        report['libraries'][name] = {'donor_sha256': digest(donor), 'device_sha256': digest(live),
                                     'identical': donor == live, 'donor_bytes': len(donor),
                                     'device_bytes': len(live)}
    configs = ('etc/default-permissions/default-permissions-com.sec.android.app.vepreload.xml',
               'etc/sysconfig/preinstalled-packages-com.sec.android.app.vepreload.xml')
    for relative in configs:
        donor = (args.donor_system / relative).read_bytes()
        live = read_device('/system/' + relative)
        ET.fromstring(donor)
        ET.fromstring(live)
        report['configuration'][relative] = {'identical': donor == live,
            'donor_sha256': digest(donor), 'device_sha256': digest(live)}
    tokens = ('TARGET_TRACKING', 'AUTO_FRAMING', 'VIDEO_CLIPPING')
    def features(data):
        return {node.tag: node.text for node in ET.fromstring(data)
                if any(token in node.tag for token in tokens)}
    report['configuration']['floating_features'] = {
        'donor': features((args.donor_system / 'etc/floating_feature.xml').read_bytes()),
        'device': features(read_device('/system/etc/floating_feature.xml'))}
    packages = command('pm path com.sec.android.app.vepreload').splitlines()
    if len(packages) != 1 or not packages[0].startswith('package:'):
        raise ValueError('Unexpected Video Editor APK layout')
    app_path = packages[0].removeprefix('package:')
    app = read_device(app_path)
    donor_app = (args.donor_system / 'app/VideoEditorLite_Dream_N/VideoEditorLite_Dream_N.apk').read_bytes()
    with zipfile.ZipFile(io.BytesIO(app)) as live_zip, zipfile.ZipFile(io.BytesIO(donor_app)) as donor_zip:
        def contents(archive):
            return {item.filename: digest(archive.read(item)) for item in archive.infolist()
                    if not item.is_dir() and not item.filename.startswith('META-INF/')}
        live_entries, donor_entries = contents(live_zip), contents(donor_zip)
    report['editor_app'] = {'device_path': app_path, 'device_sha256': digest(app),
        'donor_sha256': digest(donor_app), 'identical': app == donor_app,
        'changed_payload_entries': [name for name in sorted(live_entries.keys() | donor_entries.keys())
            if live_entries.get(name) != donor_entries.get(name)],
        'dex_and_native': {name: {'donor_sha256': donor_entries.get(name),
            'device_sha256': live_entries.get(name)}
            for name in sorted(live_entries.keys() | donor_entries.keys())
            if name.endswith(('.dex', '.so'))},
        'limitations': 'META-INF and APK signing block are excluded from payload-entry equality.'}
    for name in ('Locator.dlc', 'Reid.dlc', 'Detection.dlc', 'Pose.dlc', 'Keyword.dlc'):
        path = '/system/etc/mediacontextanalyzer/' + name
        donor = args.donor_system / 'etc/mediacontextanalyzer' / name
        target = command('readlink -f ' + shlex.quote(path))
        sha = command('sha256sum ' + shlex.quote(path)).split()[0]
        report['aliases'][name] = {'donor_target': donor.resolve().name,
            'donor_sha256': digest(donor.read_bytes()), 'device_target': Path(target).name,
            'device_sha256': sha}
    gallery = json.loads(args.gallery.read_text())
    selected = gallery['selected_ids_from_process_logs']
    report['gap']['selected_ids'] = selected
    report['gap']['frames'] = [frame for frame in gallery['frames']
                               if 3000000 <= frame['timestamp_us'] <= 4800000]
    report['gap']['subject_track_ranges'] = {key: {
        'start_us': values[0]['timestamp_us'], 'end_us': values[-1]['timestamp_us'],
        'entries': len(values)} for key, values in gallery['postprocessed_track_boxes'].items()}
    (args.output / 'comparison.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({key: value for key, value in report.items() if key != 'gap'}, indent=2))


if __name__ == '__main__':
    main()
