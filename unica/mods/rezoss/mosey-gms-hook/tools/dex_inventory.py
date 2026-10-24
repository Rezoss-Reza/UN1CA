# SPDX-License-Identifier: GPL-3.0-or-later
# Offline DEX declaration/prototype parser. Does not execute target code.
import struct,zipfile,re,json,pathlib
class Dex:
 def __init__(self,b):
  self.b=b
  self.strings=[]
  for i in range(self.u32(56)):
   o=self.u32(self.u32(60)+4*i);_,o=self.uleb(o);self.strings.append(b[o:b.index(0,o)].decode(errors='replace'))
  self.types=[self.strings[self.u32(self.u32(68)+4*i)] for i in range(self.u32(64))]
  self.protos=[]
  for i in range(self.u32(72)):
   _,r,p=struct.unpack_from('<III',b,self.u32(76)+i*12)
   args=[] if not p else [self.types[struct.unpack_from('<H',b,p+4+2*j)[0]] for j in range(self.u32(p))]
   self.protos.append('('+''.join(args)+')'+self.types[r])
  self.methods=[struct.unpack_from('<HHI',b,self.u32(92)+i*8) for i in range(self.u32(88))]
 def u32(self,o):return struct.unpack_from('<I',self.b,o)[0]
 def uleb(self,o):
  v=0;s=0
  while True:
   x=self.b[o];o+=1;v|=(x&127)<<s
   if not x&128:return v,o
   s+=7
 def signature(self,idx):
  c,p,s=self.methods[idx];return self.types[c]+'->'+self.strings[s]+self.protos[p]
 def declared(self):
  for i in range(self.u32(96)):
   row=struct.unpack_from('<IIIIIIII',self.b,self.u32(100)+i*32)
   if not row[6]:continue
   o=row[6];cnt=[]
   for _ in range(4):v,o=self.uleb(o);cnt.append(v)
   for _ in range(cnt[0]+cnt[1]):_,o=self.uleb(o);_,o=self.uleb(o)
   for count in cnt[2:]:
    idx=0
    for _ in range(count):
     delta,o=self.uleb(o);idx+=delta;access,o=self.uleb(o);code,o=self.uleb(o)
     yield idx,access,code
 def string_candidates(self,pattern):
  wanted={i:s for i,s in enumerate(self.strings) if pattern.search(s)}
  for idx,access,code in self.declared():
   if not code:continue
   units=struct.unpack_from('<'+'H'*self.u32(code+12),self.b,code+16)
   refs=set()
   for i,x in enumerate(units[:-1]):
    if x&255==0x1a and units[i+1] in wanted:refs.add(units[i+1])
    if x&255==0x1b and i+2<len(units):
     j=units[i+1]|units[i+2]<<16
     if j in wanted:refs.add(j)
   if refs:yield self.signature(idx),[(j,wanted[j]) for j in sorted(refs)]
