#!/usr/bin/env python3
"""Extract exact native ReID decoder evidence without modifying firmware."""
import argparse
import hashlib
import json
from pathlib import Path
import struct

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from elftools.elf.elffile import ELFFile

LIBRARY_SHA256 = 'e8921a043f2050d69d11f906820a400054c835bebd0f743bff7a33405c42f835'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--library', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    digest = hashlib.sha256(args.library.read_bytes()).hexdigest()
    if digest != LIBRARY_SHA256:
        raise ValueError('Unreviewed native decoder library')
    args.output.mkdir(parents=True, exist_ok=False)
    report = {'library_sha256': digest, 'functions': {}, 'constants': {}}
    assembly = []
    with args.library.open('rb') as stream:
        elf = ELFFile(stream)
        symbols = elf.get_section_by_name('.dynsym')
        disassembler = Cs(CS_ARCH_ARM64, CS_MODE_ARM)

        def data_at(address, size):
            for section in elf.iter_sections():
                start = section['sh_addr']
                if start <= address and address + size <= start + section['sh_size']:
                    return section.data()[address - start:address - start + size]
            raise ValueError('Unmapped virtual address')

        pairs = struct.unpack('<128i', data_at(0x2a0b8, 512))
        report['skeleton_87'] = [list(pairs[i:i + 2]) for i in range(0, len(pairs), 2)]

        for symbol in symbols.iter_symbols():
            name, address, size = symbol.name, symbol['st_value'], symbol['st_size']
            if not address or not size:
                continue
            if any(token in name for token in ('PoseRTMCC', 'PoseEstimatorRTMCC', 'calculate_cosine_distance',
                                               'KEYPOINT_NAME', 'preprocess_crop_letterbox', 'verify_pose',
                                               'verify_face', 'keypoint_activation', 'draw_pose',
                                               'get_frontal_face_ratio', 'hand_bbox', 'face_bbox', 'create_face_box',
                                               'OperatorBox', 'tracking6Target', 'tracking7Tracker')):
                content = data_at(address, size)
                if symbol['st_info']['type'] == 'STT_FUNC':
                    report['functions'][name] = {'address': hex(address), 'bytes': size}
                    assembly.append('\n' + name + ' @ ' + hex(address))
                    assembly.extend('%08x  %s %s' % (i.address, i.mnemonic, i.op_str)
                                    for i in disassembler.disasm(content, address))
                else:
                    report['constants'][name] = {'address': hex(address), 'bytes': size,
                                                'int32': list(struct.unpack('<' + 'i' * (size // 4), content))
                                                if size % 4 == 0 else None, 'hex': content.hex()}
    (args.output / 'native-contract.json').write_text(json.dumps(report, indent=2) + '\n')
    (args.output / 'native-disassembly.txt').write_text('\n'.join(assembly) + '\n')
    print('Functions:', len(report['functions']), 'constants:', len(report['constants']))
    for name, value in report['constants'].items():
        print(name, value)


if __name__ == '__main__':
    main()
