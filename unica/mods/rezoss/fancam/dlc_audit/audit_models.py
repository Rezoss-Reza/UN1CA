#!/usr/bin/env python3
"""Inventory donor DLC contracts and portable weights without editing firmware."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile


def inspect(path):
    from qti.aisw.dlc_utils import modeltools

    report = {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
              'size': path.stat().st_size, 'graphs': []}
    with zipfile.ZipFile(path) as archive:
        errors = []
        for name in archive.namelist():
            try:
                archive.read(name)
            except zipfile.BadZipFile:
                errors.append(name)
        report['zip_crc_errors'] = errors
        report['zip_crc_error'] = errors[0] if errors else None
        report['entries'] = [{'name': i.filename, 'bytes': i.file_size, 'declared_crc': i.CRC}
                             for i in archive.infolist()]
    reader = modeltools.IrDlcReader()
    reader.open(str(path))
    try:
        for name in reader.get_ir_graph_names():
            graph = reader.get_ir_graph(name)
            tensors = {}
            for op in graph.get_ops():
                for tensor in op.inputs():
                    if tensor.is_static_tensor() and tensor.name() not in tensors:
                        tensors[tensor.name()] = graph.get_static_tensor(tensor.name()).get_data_size()
            report['graphs'].append({
                'name': name, 'ops': len(graph.get_ops()),
                'statics': len(tensors), 'empty_statics': sum(size == 0 for size in tensors.values()),
                'static_bytes': sum(tensors.values()),
                'inputs': [{'name': t.name(), 'shape': t.dims(), 'dtype': str(t.data_type())}
                           for t in graph.get_input_tensors_to_graph()],
                'outputs': [{'name': t.name(), 'shape': t.dims(), 'dtype': str(t.data_type())}
                            for t in graph.get_output_tensors_of_graph()],
            })
    finally:
        reader.close()
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--inspect', type=Path)
    parser.add_argument('--donor', type=Path)
    parser.add_argument('--stock', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--sdk', type=Path)
    parser.add_argument('--runtime-libs', type=Path)
    parser.add_argument('--python-libs', type=Path)
    args = parser.parse_args()
    if args.inspect:
        print(json.dumps(inspect(args.inspect)))
        return
    for key in ('donor', 'stock', 'output', 'sdk', 'runtime_libs', 'python_libs'):
        if getattr(args, key) is None:
            parser.error('Missing --' + key.replace('_', '-'))
    args.output.mkdir(parents=True, exist_ok=False)
    env = os.environ.copy()
    env['PYTHONPATH'] = str(args.sdk.resolve() / 'lib/python')
    env['LD_LIBRARY_PATH'] = ':'.join(map(str, (
        args.sdk.resolve() / 'lib/x86_64-linux-clang',
        args.runtime_libs.resolve(), args.python_libs.resolve())))
    stock = {}
    for path in sorted(args.stock.rglob('*.dlc')):
        stock.setdefault(path.name, []).append(path)
    results = []
    # Isolate SDK reader crashes to a single model and save progress after each one.
    for path in sorted(args.donor.rglob('*.dlc')):
        row = {'source': str(path.resolve()), 'relative_path': str(path.relative_to(args.donor))}
        try:
            process = subprocess.run([sys.executable, str(Path(__file__).resolve()),
                                      '--inspect', str(path.resolve())], env=env,
                                     capture_output=True, text=True, timeout=180)
            row['reader_exit'] = process.returncode
            if process.returncode == 0:
                row.update(json.loads(process.stdout.strip().splitlines()[-1]))
                row['weight_status'] = ('portable' if row['graphs'] and
                                        all(g['empty_statics'] == 0 for g in row['graphs'])
                                        else 'stripped_or_partial')
            else:
                row['error'] = (process.stdout + process.stderr)[-2000:]
        except (ValueError, subprocess.TimeoutExpired, zipfile.BadZipFile) as error:
            row['error'] = str(error)
        row['stock_same_filename'] = [str(p) for p in stock.get(path.name, [])]
        row['stock_identical'] = [str(p) for p in stock.get(path.name, [])
                                  if hashlib.sha256(p.read_bytes()).hexdigest() == row.get('sha256')]
        results.append(row)
        (args.output / 'inventory.json').write_text(json.dumps(results, indent=2) + '\n')
        print(len(results), path.name, row.get('weight_status', 'reader_error'),
              'identical_stock=' + str(len(row['stock_identical'])), flush=True)
    print('Inspected', len(results), 'DLCs; report:', args.output / 'inventory.json')


if __name__ == '__main__':
    main()
