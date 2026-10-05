#!/usr/bin/env python3
"""Disassemble pinned tracker functions with resolved AArch64 PLT calls."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from capstone.arm64 import ARM64_OP_IMM, ARM64_OP_MEM
from elftools.elf.elffile import ELFFile

PIN = 'e8921a043f2050d69d11f906820a400054c835bebd0f743bff7a33405c42f835'
MCA_PIN = 'bedfc253a4c577d6917f2a79412c1c98d7c43cf5b2101406de9e970e526fabd7'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--library', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--component', choices=('auto', 'mca'), default='auto')
    args = parser.parse_args()
    pin = PIN if args.component == 'auto' else MCA_PIN
    if hashlib.sha256(args.library.read_bytes()).hexdigest() != pin:
        raise ValueError('Unreviewed native ABI')
    args.output.mkdir(parents=True, exist_ok=False)
    with args.library.open('rb') as stream:
        elf = ELFFile(stream)
        symbols = elf.get_section_by_name('.dynsym')
        relocs = elf.get_section_by_name('.rela.plt')
        plt = elf.get_section_by_name('.plt')
        names = {s['st_value']: s.name for s in symbols.iter_symbols() if s['st_value']}
        got_names = {r['r_offset']: symbols.get_symbol(r['r_info_sym']).name for r in relocs.iter_relocations()}
        cs = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
        cs.detail = True
        stubs = list(cs.disasm(plt.data(), plt['sh_addr']))
        resolved = set()
        for index, instruction in enumerate(stubs[:-1]):
            load = stubs[index + 1]
            if instruction.mnemonic != 'adrp' or load.mnemonic != 'ldr':
                continue
            if instruction.operands[1].type != ARM64_OP_IMM or load.operands[1].type != ARM64_OP_MEM:
                continue
            got = instruction.operands[1].imm + load.operands[1].mem.disp
            if got in got_names:
                names[instruction.address] = got_names[got]
                if index and stubs[index - 1].mnemonic == 'bti':
                    names[stubs[index - 1].address] = got_names[got]
                resolved.add(got)
        if resolved != set(got_names):
            raise ValueError('Incomplete PLT relocation resolution')
        def data_at(address, size):
            for section in elf.iter_sections():
                if section['sh_addr'] <= address and address + size <= section['sh_addr'] + section['sh_size']:
                    return section.data()[address - section['sh_addr']:address - section['sh_addr'] + size]
            raise ValueError('Unmapped address')
        assembly, report = [], {'library_sha256': pin, 'functions': {}, 'plt_symbols': {hex(k): v for k, v in names.items()}}
        for symbol in symbols.iter_symbols():
            if symbol['st_info']['type'] != 'STT_FUNC' or not symbol['st_value'] or not symbol['st_size']:
                continue
            if not any(t in symbol.name for t in ('10VideoTrack', '8tracking', 'pack_pose',
                    '9FrameData', '15PackedFrameData', 'box_type', 'DetectionBox', 'TrackerConfig',
                    'conversion_operator', 'HumanPetTracker')):
                continue
            start, size = symbol['st_value'], symbol['st_size']
            data = data_at(start, size)
            report['functions'][symbol.name] = {'address': hex(start), 'bytes': size,
                                               'sha256': hashlib.sha256(data).hexdigest()}
            assembly.append('\n' + symbol.name + ' @ ' + hex(start))
            for instruction in cs.disasm(data, start):
                suffix = ''
                if instruction.mnemonic in ('bl', 'b') and instruction.op_str.startswith('#0x'):
                    target = int(instruction.op_str[1:], 16)
                    if target in names:
                        suffix = ' ; ' + names[target]
                assembly.append('%08x  %s %s%s' % (instruction.address, instruction.mnemonic, instruction.op_str, suffix))
        # Constructor loads this pinned table directly; interpretations remain offset-labelled.
        if args.component == 'auto':
            for address, size in ((0x29540, 160), (0x28cb0, 160)):
                table = data_at(address, size)
                report['default_table_' + hex(address)] = {'address': hex(address), 'hex': table.hex(),
                    'words': [{'offset': i, 'uint32': struct.unpack_from('<I', table, i)[0],
                               'float32': struct.unpack_from('<f', table, i)[0]} for i in range(0, size, 4)]}
    (args.output / 'contract.json').write_text(json.dumps(report, indent=2, allow_nan=False) + '\n')
    (args.output / 'disassembly.txt').write_text('\n'.join(assembly) + '\n')
    print('Pinned functions:', len(report['functions']))


if __name__ == '__main__':
    main()
