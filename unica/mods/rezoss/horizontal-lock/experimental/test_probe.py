#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
import subprocess,shlex,re,json
from pathlib import Path
import argparse
parser=argparse.ArgumentParser()
parser.add_argument('--build-dir',type=Path,required=True)
parser.add_argument('--output-dir',type=Path,required=True)
options=parser.parse_args()
adb='/mnt/c/platform-tools/adb.exe';root=options.output_dir
root.mkdir(parents=True,exist_ok=False)
def args(s):return [adb,'shell','su -c '+shlex.quote(s)]
for local,remote in [('frame_trace','s23-horizon-frame-trace'),('mock','s23-horizon-frame-mock')]:
 subprocess.run([adb,'push',str((options.build_dir/local).resolve()),'/data/local/tmp/'+remote],check=True,capture_output=True)
 subprocess.run(args('chmod 755 /data/local/tmp/'+remote),check=True,capture_output=True)
for override,mode in [(0,0),(0,1),(0,2),(1,0),(1,1),(1,2)]:
 target=subprocess.Popen(args(f'/data/local/tmp/s23-horizon-frame-mock {mode}'),stdout=subprocess.PIPE,text=True)
 line=target.stdout.readline();pid,init,dist,stab=re.search(r'pid=(\d+) init=(0x\w+) dist=(0x\w+) stab=(0x\w+)',line).groups()
 try:
  tr=subprocess.Popen(args(f'HORIZON_TRACE_SECONDS=2 HORIZON_ENABLE_180={override} /data/local/tmp/s23-horizon-frame-trace {pid} --test {init} {dist} {stab}'),stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
  armed=tr.stdout.readline();assert json.loads(armed)['event']=='armed',armed
  subprocess.run(args(f'kill -USR1 {pid}'),check=True,capture_output=True)
  out,err=tr.communicate(timeout=8);assert tr.returncode==0,(out,err)
  rows=[json.loads(x) for x in (armed+out).splitlines()];root.joinpath(f'mock-{override}-{mode}.jsonl').write_text(armed+out)
  initrows=[x for x in rows if x['event']=='eis3_init' and x['stage']=='after'];assert initrows
  for x in initrows:
   assert x['result']==0
   assert [v['max_roll_deg'] for v in x['sensors']]==([180]*4 if override and mode==2 else [0,10,20,30])
  frames=[x for x in rows if x['event']=='eis3_frame'];assert {x['call'] for x in frames}=={'distortion','stabilize'}
  for x in frames:
   assert x['mode']==mode
   if x['stage']=='after':
    assert x['result']==0 and all(x[k]==1 for k in ('has_output','matrix_enable')) and len(x['output_prefix_hex'])==352
    if x['call']=='stabilize':
     assert all(x[k]==1 for k in ('grid_enable','alignment_enable','matrix_rows'))
     assert x['matrix_domain']==[2400,1352] and x['first_matrix']==[1,2,3,4,5,6,7,8,9]
   elif x['call']=='distortion':
    for sensor in ('gyro','accel','gravity'):
     assert x[sensor]['count']==2
     assert [v['timestamp_us'] for v in x[sensor]['samples']]==[1000,2000]
     assert x[sensor]['samples'][0]['values']==[1,2,3,4,5,6]
  assert any(x['event']=='code_restored' and x['verified'] for x in rows)
  assert rows[-1]['event']=='detached' and rows[-1]['failures']==0
  status=subprocess.run(args(f'cat /proc/{pid}/status'),capture_output=True,text=True).stdout;assert 'TracerPid:\t0' in status
  print('PASS override',override,'mode',mode,'init, sensor values/timestamps, both output paths, restore and timed detach',flush=True)
 finally:
  subprocess.run(args(f'kill -TERM {pid}'),capture_output=True);target.communicate(timeout=3)
