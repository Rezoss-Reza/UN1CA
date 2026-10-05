#!/usr/bin/env python3
"""Archive tested model artifacts and structural evidence, without SDK files."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

from audit_models import inspect


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--inventory', type=Path, required=True)
    parser.add_argument('--humanpet', type=Path, required=True)
    parser.add_argument('--docsegment', type=Path, required=True)
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    source = Path(__file__).resolve().parent
    shutil.copy2(source / 'README.txt', args.output / 'README.txt')
    shutil.copy2(args.inventory, args.output / 'inventory.json')
    inventory = json.loads(args.inventory.read_text())
    lines = ['path\tweight_status\tstatic_bytes\tempty_statics\tinputs\toutputs']
    for row in inventory:
        graphs = row.get('graphs', [])
        lines.append('\t'.join((row['relative_path'], row.get('weight_status', 'reader_error'),
                                str(sum(g['static_bytes'] for g in graphs)),
                                str(sum(g['empty_statics'] for g in graphs)),
                                json.dumps([g['inputs'] for g in graphs]),
                                json.dumps([g['outputs'] for g in graphs]))))
    (args.output / 'inventory.tsv').write_text('\n'.join(lines) + '\n')
    for name, folder in (('humanpet', args.humanpet), ('docsegment', args.docsegment)):
        config = json.loads((folder / 'experiment.json').read_text())
        device = json.loads((folder / 'device-results.json').read_text())
        model = folder / config['candidate']
        if device['candidate_sha256'] != hashlib.sha256(model.read_bytes()).hexdigest():
            raise ValueError('Model changed after device testing: ' + name)
        inspection = inspect(model)
        if any(g['empty_statics'] for g in inspection['graphs']):
            raise ValueError('Candidate has missing portable parameters')
        # This SDK writes zero CRC fields. Preserve its serialization and report
        # missing CRCs; a nonzero declared CRC mismatch is a separate failure.
        declared = {entry['name']: entry['declared_crc'] for entry in inspection['entries']}
        if any(declared[entry] != 0 for entry in inspection['zip_crc_errors']):
            raise ValueError('Nonzero declared CRC mismatch: ' + name)
        destination = args.output / name
        destination.mkdir()
        (destination / 'graph-inspection.json').write_text(json.dumps(inspection, indent=2) + '\n')
        files = [model, folder / 'experiment.json', folder / 'host-results.json',
                 folder / 'device-results.json', folder / 'device-profile.txt', folder / 'inspect.log']
        if name == 'humanpet':
            files += [folder / 'native-results.json', folder / 'humanpet.onnx']
            files += sorted(folder.glob('*-dsp-detections.jpg'))
            files += [args.upstream / 'LICENSE.md']
        for file in files:
            shutil.copy2(file, destination / file.name)
    entries = []
    for path in sorted(args.output.rglob('*')):
        if path.is_file():
            entries.append(hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + str(path.relative_to(args.output)))
    (args.output / 'SHA256SUMS').write_text('\n'.join(entries) + '\n')
    print('Archived', len(entries), 'files:', args.output)


if __name__ == '__main__':
    main()
