#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Synthetic 30-frame EIS delay to CHI payload mutation and rollback."""
import argparse,json,re,shlex,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--build-dir',type=Path,required=True);p.add_argument('--output-dir',type=Path,required=True);a=p.parse_args();a.output_dir.mkdir(parents=True,exist_ok=False)
adb='/mnt/c/platform-tools/adb.exe'
def remote(c):return [adb,'shell','su -c '+shlex.quote(c)]
for name in ('libunica_horizon_core.so','adapter_trace','adapter_pipeline_mock'):
 subprocess.run([adb,'push',str((a.build_dir/name).resolve()),'/data/local/tmp/'+name],check=True,capture_output=True)
 subprocess.run(remote('chmod 755 /data/local/tmp/'+name),check=True,capture_output=True)
for apply,mode in ((0,2),(1,0),(1,1),(1,2)):
 target=subprocess.Popen(remote(f'/data/local/tmp/adapter_pipeline_mock {mode}'),stdout=subprocess.PIPE,text=True)
 line=target.stdout.readline();pid,init,dist,stab,meta=re.search(r'pid=(\d+) init=(0x\w+) dist=(0x\w+) stab=(0x\w+) meta=(0x\w+)',line).groups()
 try:
  tr=subprocess.Popen(remote(f'HORIZON_APPLY={apply} HORIZON_TRACE_SECONDS=2 /data/local/tmp/adapter_trace {pid} --test {init} {dist} {stab} {meta}'),stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
  armed=tr.stdout.readline();assert json.loads(armed)['event']=='armed'
  subprocess.run(remote(f'kill -USR1 {pid}'),check=True,capture_output=True)
  out,err=tr.communicate(timeout=8);assert tr.returncode==0,(out,err)
  rows=[json.loads(l) for l in (armed+out).splitlines()];(a.output_dir/f'apply-{apply}-mode-{mode}.jsonl').write_text(armed+out)
  changes=[r for r in rows if r['event']=='adapter_publish'];changed=[r for r in changes if r['applied']]
  if mode==2:
   assert len(changes)>=35
   assert all(r['matched_matrix'] for r in changes)
   assert all(r.get('zoom')==2 for r in changes if r['status']==0)
   assert any(r['status']==0 and r.get('correction_deg',0)<-10 for r in changes)
   assert bool(changed)==bool(apply)
   if apply:assert len(changed)>=30
  else:assert not changed
  assert rows[-2]['event']=='code_restored' and rows[-2]['verified']
  assert rows[-1]['event']=='detached' and rows[-1]['failures']==0
  status=subprocess.run(remote(f'cat /proc/{pid}/status'),check=True,capture_output=True,text=True).stdout;assert 'TracerPid:\t0' in status
  print('PASS apply',apply,'mode',mode,'frame-matched publish:',len(changes),'writes:',len(changed),'verified cleanup',flush=True)
 finally:
  subprocess.run(remote(f'kill -TERM {pid}'),capture_output=True);remaining=target.communicate(timeout=3)[0];assert 'Assertion' not in remaining,remaining
