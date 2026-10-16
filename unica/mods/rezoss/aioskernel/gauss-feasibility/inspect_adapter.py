#!/usr/bin/env python3
"""Inventory the observed Samsung LoRA FlatBuffer against QNN context I/O.

This parser handles only the observed container layout. It does not recover
the opaque decoder's internal graph or base weights. Export is opt-in.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import struct


class Container:
    def __init__(self, data):
        self.data = data

    def read(self, fmt, offset):
        size = struct.calcsize(fmt)
        if offset < 0 or offset + size > len(self.data):
            raise ValueError('Container offset outside file')
        return struct.unpack_from(fmt, self.data, offset)

    def reference(self, offset):
        return offset + self.read('<I', offset)[0]

    def fields(self, offset):
        vtable = offset - self.read('<i', offset)[0]
        size, object_size = self.read('<HH', vtable)
        if size < 4 or size > 1024 or size % 2 or offset + object_size > len(self.data):
            raise ValueError('Invalid table dimensions')
        slots = self.read('<' + 'H' * ((size - 4) // 2), vtable + 4)
        if any(slot >= object_size for slot in slots):
            raise ValueError('Invalid table field')
        return [offset + slot if slot else None for slot in slots]

    def vector(self, offset):
        start = self.reference(offset)
        size = self.read('<I', start)[0]
        if size > len(self.data) - start - 4:
            raise ValueError('Invalid byte vector size')
        return self.data[start + 4:start + 4 + size], start + 4

    def entries(self):
        fields = self.fields(self.read('<I', 0)[0])
        if len(fields) != 1 or fields[0] is None:
            raise ValueError('Unrecognized root layout')
        vector = self.reference(fields[0])
        count = self.read('<I', vector)[0]
        if count > 100000 or vector + 4 + 4 * count > len(self.data):
            raise ValueError('Invalid entry count')
        seen = set()
        for index in range(count):
            fields = self.fields(self.reference(vector + 4 + 4 * index))
            if len(fields) != 4 or any(field is None for field in fields):
                raise ValueError('Unrecognized entry layout')
            name = self.vector(fields[0])[0].decode('utf-8')
            dtype = self.vector(fields[1])[0].decode('ascii')
            data, offset = self.vector(fields[2])
            if name in seen or len(data) != self.read('<I', fields[3])[0]:
                raise ValueError('Duplicate name or payload length mismatch')
            seen.add(name)
            yield name, dtype, data, offset


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('adapter', type=Path)
    parser.add_argument('context_json', type=Path)
    parser.add_argument('new_output_directory', type=Path)
    parser.add_argument('--export-tensor', help='Exact adapter tensor name to export locally')
    args = parser.parse_args()
    if args.new_output_directory.exists():
        parser.error('Output directory already exists')
    data = args.adapter.read_bytes()
    context = json.loads(args.context_json.read_text())['info']
    graphs = []
    for item in context['graphs']:
        graph = item['info']
        graphs.append((graph['graphName'], {t['info']['name']: t['info'] for t in graph['graphInputs']}))
    rows = []
    exported = None
    for name, dtype, payload, offset in Container(data).entries():
        row = dict(name=name, dtype=dtype, bytes=len(payload), offset=offset,
                   sha256=hashlib.sha256(payload).hexdigest(), bindings=[])
        match = re.fullmatch(r'([qkvo])_lora_d_(\d+)_([AB])', name)
        if match:
            native_name = f'{match[1]}_lora_{match[3]}_weights_d_{match[2]}'
            for graph_name, inputs in graphs:
                if native_name not in inputs:
                    continue
                tensor = inputs[native_name]
                params = tensor['quantizeParams']
                valid = (dtype == 'w16' and tensor['dataType'] == 'QNN_DATATYPE_UFIXED_POINT_16'
                         and len(payload) == 2 * math.prod(tensor['dimensions'])
                         and params['quantizationEncoding'] == 'QNN_QUANTIZATION_ENCODING_SCALE_OFFSET')
                if not valid:
                    raise ValueError(f'Unverified tensor binding: {name}/{graph_name}')
                row['bindings'].append(dict(graph=graph_name, tensor=native_name,
                                            shape=tensor['dimensions'], quantization=params['scaleOffset']))
        if name == args.export_tensor:
            if not row['bindings']:
                raise ValueError('Cannot export an unverified tensor')
            exported = payload
        rows.append(row)
    if args.export_tensor and exported is None:
        parser.error('Requested tensor not found')
    report = dict(adapter_sha256=hashlib.sha256(data).hexdigest(),
                  context_json_sha256=hashlib.sha256(args.context_json.read_bytes()).hexdigest(),
                  entries=len(rows), mapped_entries=sum(bool(row['bindings']) for row in rows),
                  unmapped_entries=[row['name'] for row in rows if not row['bindings']], tensors=rows)
    args.new_output_directory.mkdir(parents=True)
    (args.new_output_directory / 'adapter-report.json').write_text(json.dumps(report, indent=2) + '\n')
    if exported is not None:
        (args.new_output_directory / 'selected-tensor.u16').write_bytes(exported)
    print(json.dumps({key: value for key, value in report.items() if key != 'tensors'}, indent=2))


if __name__ == '__main__':
    main()
