#!/usr/bin/env python3
"""Compare read-only baksmali output, including FanCam consumer methods."""
import argparse
import hashlib
import json
from pathlib import Path
import re


def normalize_source(text):
    return re.sub(r'^\.source .*\n', '', text, flags=re.MULTILINE)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--decoded', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Refusing to overwrite evidence')
    def collect(prefix):
        files = {}
        for number in (1, 2):
            root = args.decoded / (prefix + str(number))
            for path in root.rglob('*.smali'):
                key = str(path.relative_to(root))
                if key in files:
                    raise ValueError('Duplicate decoded class')
                files[key] = normalize_source(path.read_text())
        if not files:
            raise ValueError('Empty decode')
        return files
    donor, device = collect('donor'), collect('device')
    pattern = re.compile(r'MediaContextAnalyzer|[Rr]efram|KenBurn|[Ff]an[Cc]am|TARGET_TRACKING')
    changed, relevant = [], []
    for name in sorted(donor.keys() | device.keys()):
        a, b = donor.get(name), device.get(name)
        if a != b:
            changed.append(name)
        if pattern.search((a or '') + (b or '')):
            def methods(text):
                return {match[1]: match[0] for match in re.finditer(
                    r'^\.method ([^\n]+)\n.*?^\.end method', text or '', re.MULTILINE | re.DOTALL)}
            donor_methods, device_methods = methods(a), methods(b)
            relevant.append({'class': name, 'identical': a == b,
                'donor_sha256': hashlib.sha256(a.encode()).hexdigest() if a is not None else None,
                'device_sha256': hashlib.sha256(b.encode()).hexdigest() if b is not None else None,
                'changed_methods': [key for key in sorted(donor_methods.keys() | device_methods.keys())
                    if donor_methods.get(key) != device_methods.get(key)]})
    report = {'donor_classes': len(donor), 'device_classes': len(device),
              'changed_classes': changed, 'fancam_related_classes': relevant,
              'limitations': 'Instruction-text comparison without debug information or .source map IDs; not resource or runtime parameter equality.'}
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print('Classes:', len(donor), len(device), 'changed:', len(changed))
    print('FanCam-related:', len(relevant), 'changed:', sum(not r['identical'] for r in relevant))
    print('Changed classes:', changed[:50])


if __name__ == '__main__':
    main()
