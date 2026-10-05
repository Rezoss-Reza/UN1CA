#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Exact dm3q EIS node: BL trampolines to a linked adapter, without ptrace."""
import argparse, hashlib, json, struct
from collections import Counter
from pathlib import Path
import lief
SHA='7e4f72bc10082e074424fe6f5a9d411b1740d2b9b3a9df3cec8ae4f1d4ec26a7'
# VA, original BLR, symbol, function-pointer register, initialization flag.
SITES=[(0x4c580,0xd63f02e0,'unica_hl_init',23,True),
       (0x44c94,0xd63f0280,'unica_hl_dist',20,False),
       (0x44e50,0xd63f0340,'unica_hl_stab',26,False),
       (0x49684,0xd63f02c0,'unica_hl_publish',22,False),
       (0x28708,0xd63f02c0,'unica_hl_drain_dist',22,False),
       (0x28984,0xd63f02c0,'unica_hl_drain_stab',22,False)]
def contents(b,va,n):return bytes(b.get_content_from_virtual_address(va,n))
def main():
 p=argparse.ArgumentParser();p.add_argument('source',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
 if a.output.exists():p.error('Refusing existing output')
 if hashlib.sha256(a.source.read_bytes()).hexdigest()!=SHA:p.error('Unsupported original EIS node')
 b=lief.parse(str(a.source));original_text=contents(b,0x21000,0x37478)
 original_relocations=Counter((r.address,int(r.type),r.addend,r.symbol.name if r.has_symbol else '') for r in b.relocations)
 packed=[(r.address,int(r.type)&0xffffff,r.addend,r.symbol.name if r.has_symbol else '') for r in b.dynamic_relocations if r.encoding==lief.ELF.Relocation.ENCODING.ANDROID_SLEB]
 # Preserve packed relocation semantics while remapping any reordered dynsym indices.
 b.remove(lief.ELF.DynamicEntry.TAG.ANDROID_RELA);b.remove(lief.ELF.DynamicEntry.TAG.ANDROID_RELASZ)
 old_rela=b.get_section('.rela.dyn');old_rela.name='.unica.original.reloc';old_rela.type=lief.ELF.Section.TYPE.PROGBITS
 for va,op,*_ in SITES:
  if contents(b,va,4)!=struct.pack('<I',op):raise RuntimeError('Instruction mismatch')
 if not b.relocate_phdr_table(lief.ELF.Binary.PHDR_RELOC.FILE_END):raise RuntimeError('Cannot relocate program headers without shifting code')
 b.add_library('libunica_horizon_adapter.so')
 data=lief.ELF.Segment();data.type=lief.ELF.Segment.TYPE.LOAD;data.flags=lief.ELF.Segment.FLAGS.R|lief.ELF.Segment.FLAGS.W;data.alignment=0x1000;data.content=[0]*0x1000;data=b.add(data)
 code=lief.ELF.Segment();code.type=lief.ELF.Segment.TYPE.LOAD;code.flags=lief.ELF.Segment.FLAGS.R|lief.ELF.Segment.FLAGS.X;code.alignment=0x1000;code.content=[0]*0x1000;code=b.add(code)
 records=[]
 for i,(va,op,name,reg,init) in enumerate(SITES):
  sym=lief.ELF.Symbol();sym.name=name;sym.type=lief.ELF.Symbol.TYPE.FUNC;sym.binding=lief.ELF.Symbol.BINDING.GLOBAL;sym.shndx=0;sym=b.add_dynamic_symbol(sym)
  got=data.virtual_address+i*8;stub=code.virtual_address+i*32
  # New regular RELA table includes the original packed relocation semantics.
  page_delta=(got>>12)-((stub+12)>>12)
  if not -(1<<20)<=page_delta<(1<<20):raise RuntimeError('ADRP range')
  adrp=0x90000010|((page_delta&3)<<29)|(((page_delta>>2)&0x7ffff)<<5)
  # mov x4, original function; x5=node; adrp/ldr/br tail-call the helper.
  words=[0xd503245f,0xaa0003e4|(reg<<16),0xd1032005 if init else 0xaa1303e5,
         adrp,0xf9400210|(((got&4095)//8)<<10),0xd61f0200]
  b.patch_address(stub,list(struct.pack('<6I',*words)))
  delta=stub-va
  if delta%4 or not -(1<<27)<=delta<(1<<27):raise RuntimeError('BL range')
  branch=0x94000000|((delta//4)&0x3ffffff);b.patch_address(va,list(struct.pack('<I',branch)))
  records.append({'site':va,'stub':stub,'got':got,'symbol':name,'branch':branch})
 b.add(lief.ELF.DynamicEntry(lief.ELF.DynamicEntry.TAG.RELA,data.virtual_address+0x100))
 b.add(lief.ELF.DynamicEntry(lief.ELF.DynamicEntry.TAG.RELASZ,(len(SITES)+len(packed))*24))
 config=lief.ELF.Builder.config_t();config.rela=False;config.android_rela=False
 b.write(str(a.output),config)
 # LIEF relocates e_phoff but retains the old PT_PHDR descriptor. Repair it.
 raw=bytearray(a.output.read_bytes());phoff=struct.unpack_from('<Q',raw,32)[0]
 entsize,num=struct.unpack_from('<HH',raw,54);assert entsize==56
 headers=[struct.unpack_from('<II6Q',raw,phoff+i*56) for i in range(num)]
 table_load=next(h for h in headers if h[0]==1 and h[2]<=phoff and phoff+num*56<=h[2]+h[5])
 for i,h in enumerate(headers):
  if h[0]==6:
   va=table_load[3]+phoff-table_load[2]
   struct.pack_into('<II6Q',raw,phoff+i*56,6,4,phoff,va,va,num*56,num*56,8)
 # The old program-header expansion overwrites notes even with FILE_END.
 # Restore all allocated note payloads, especially AArch64 BTI/PAC properties.
 original_raw=a.source.read_bytes()
 original_elf=lief.parse(str(a.source))
 for section in original_elf.sections:
  if section.name.startswith('.note.'):
   raw[section.offset:section.offset+section.size]=original_raw[section.offset:section.offset+section.size]
 a.output.write_bytes(raw)
 # Dynsym may be reordered by rebuilding; resolve the final indices by name.
 v=lief.parse(str(a.output))
 rela_offset=v.virtual_address_to_offset(data.virtual_address+0x100)
 for i,r in enumerate(records):
  index=v.dynsym_idx(r['symbol'])
  if index<0:raise RuntimeError('Missing helper import')
  struct.pack_into('<QQq',raw,rela_offset+i*24,r['got'],(index<<32)|1025,0)
 for i,(address,kind,addend,name) in enumerate(packed,len(records)):
  index=v.dynsym_idx(name) if name else 0
  if index<0:raise RuntimeError('Missing original relocation symbol')
  struct.pack_into('<QQq',raw,rela_offset+i*24,address,(index<<32)|kind,addend)
 a.output.write_bytes(raw);v=lief.parse(str(a.output))
 remaining=Counter((r.address,int(r.type),r.addend,r.symbol.name if r.has_symbol else '') for r in v.relocations)
 if original_relocations-remaining:raise RuntimeError('Original relocations changed')
 patched=bytearray(original_text)
 for r in records:patched[r['site']-0x21000:r['site']-0x21000+4]=struct.pack('<I',r['branch'])
 if contents(v,0x21000,len(patched))!=patched:raise RuntimeError('Original executable range changed unexpectedly')
 if any(s.flags & lief.ELF.Segment.FLAGS.X and s.flags & lief.ELF.Segment.FLAGS.W for s in v.segments):raise RuntimeError('RWX segment')
 for r in records:
  index=v.dynsym_idx(r['symbol']);entry=struct.unpack_from('<QQq',raw,rela_offset+records.index(r)*24)
  if entry!=(r['got'],(index<<32)|1025,0):raise RuntimeError('Import relocation mismatch')
 for symbol in original_elf.dynamic_symbols:
  if not symbol.name:continue
  final=v.get_dynamic_symbol(symbol.name)
  if not final or symbol.value!=final.value or str(symbol.symbol_version)!=str(final.symbol_version):raise RuntimeError('Original symbol ABI changed')
 for section in original_elf.sections:
  if section.name.startswith('.note.') and contents(v,section.virtual_address,section.size)!=bytes(section.content):raise RuntimeError('Security note changed')
 manifest={'original_sha256':SHA,'patched_sha256':hashlib.sha256(a.output.read_bytes()).hexdigest(),'sites':records,'adapter':'libunica_horizon_adapter.so'}
 a.output.with_suffix('.json').write_text(json.dumps(manifest,indent=2)+'\n');print(json.dumps(manifest,indent=2))
if __name__=='__main__':main()
