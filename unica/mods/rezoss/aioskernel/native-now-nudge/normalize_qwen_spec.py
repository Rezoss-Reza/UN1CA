#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Convert only pinned export runtime options; preserve graph and weight bytes."""
import argparse,hashlib,pathlib,struct,sys,types
p=argparse.ArgumentParser();p.add_argument('executorch');p.add_argument('original');p.add_argument('reference_smolvlm');p.add_argument('output');a=p.parse_args()
root=pathlib.Path(a.executorch).resolve();out=pathlib.Path(a.output)
if out.exists():raise SystemExit('Refusing existing output')
b=bytearray(pathlib.Path(a.original).read_bytes());source=pathlib.Path(a.reference_smolvlm).read_bytes()
assert hashlib.sha256(b).hexdigest()=='075b732e5e908e9e3ed86e2e14961aac3f2f11fefba31f0b89fc22f1b8472e30','Unexpected Qwen export'
assert hashlib.sha256(source).hexdigest()=='5c2a99c16f5a30f7a89d110aa529f6b819a4369b0a1a7f390435534a9aa586de','Unexpected reference export'
for name in ('executorch','executorch.exir','executorch.exir._serialize','executorch.exir._serialize.generated'):
 m=types.ModuleType(name);m.__path__=[str(root.joinpath(*name.split('.')[1:]))];sys.modules[name]=m
from executorch.exir._serialize.generated.executorch_flatbuffer.Program import Program
for name,module in list(sys.modules.items()):
 if name.startswith('executorch.exir._serialize.generated.executorch_flatbuffer.'):
  for key,value in list(vars(module).items()):
   if isinstance(value,types.ModuleType) and hasattr(value,key):setattr(module,key,getattr(value,key))
ref=Program.GetRootAs(source).ExecutionPlan(0).Delegates(0).CompileSpecs(0)
assert ref.Key()==b'qnn_compile_spec';spec=bytes(ref.ValueAsNumpy());program=Program.GetRootAs(b);changes=[]
for i in range(program.ExecutionPlanLength()):
 plan=program.ExecutionPlan(i)
 for j in range(plan.DelegatesLength()):
  delegate=plan.Delegates(j)
  for k in range(delegate.CompileSpecsLength()):
   c=delegate.CompileSpecs(k);assert c.Key()==b'qnn_compile_spec';offset=c._tab.Offset(6)
   changes.append((c._tab.Vector(offset),c.ValueLength()))
assert len(changes)==2
for pos,n in changes:
 assert len(spec)<=n
 b[pos:pos+n]=spec+b'\0'*(n-len(spec));struct.pack_into('<I',b,pos-4,len(spec))
assert hashlib.sha256(b).hexdigest()=='59e727d3174d84cd198a9707c2e52e9866e1c9fe0db63039474f32c71cdb009e'
# Exact output hash bounds the complete transformation to reviewed configuration bytes.
out.write_bytes(b);print(hashlib.sha256(b).hexdigest(),out)
