# SPDX-License-Identifier: GPL-3.0-or-later
import importlib.util, pathlib, tempfile, zipfile, subprocess, os
root=pathlib.Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('sha',root/'scripts/internal/package_sha.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
with tempfile.TemporaryDirectory(prefix='unica-sha-test-') as temp:
 t=pathlib.Path(temp); p=t/'package'; (p/'META-INF/com/google/android').mkdir(parents=True)
 marker=t/'writes'; (p/(m.WRAPPER+'.unica')).write_text('#!/bin/sh\necho write >> "'+str(marker)+'"\n'); (p/(m.WRAPPER+'.unica')).chmod(0o755)
 (p/'system.new.dat.br').write_bytes(os.urandom(1024*1024)); (p/'payload with spaces').write_bytes(b'hello')
 m.prepare(p)
 def archive(name,change=None,drop=None):
  out=t/name
  with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
   for f in p.rglob('*'):
    if f.is_file():
     n=f.relative_to(p).as_posix()
     if n!=drop:z.writestr(n,b'corrupt' if n==change else f.read_bytes())
  return out
 good=archive('good.zip');m.finish(good)
 signapk=root/'out/tools/bin/signapk'
 if signapk.exists():
  signed=t/'signed.zip'
  subprocess.run([str(signapk),'-w',str(root/'security/aosp_testkey.x509.pem'),str(root/'security/aosp_testkey.pk8'),str(good),str(signed)],check=True)
  m.finish(signed)
  print('Real signapk packaging: PASS')
 def run(z,success):
  wrapper=t/'wrapper.sh'; wrapper.write_text((p/m.WRAPPER).read_text().replace('TMPDIR="/tmp/unica-installer"','TMPDIR="'+str(t/'work')+'"'))
  r=subprocess.run(['sh',str(wrapper),'3','',str(z)],capture_output=True,text=True)
  assert (r.returncode==0)==success,(r.returncode,r.stdout,r.stderr)
  assert marker.exists()==success,(r.stdout,r.stderr)
  if marker.exists():marker.unlink()
  print(z.name, 'PASS',r.stdout.strip().replace('\n',' | '))
 run(good,True)
 run(archive('corrupt.zip',change='system.new.dat.br'),False)
 run(archive('missing.zip',drop='system.new.dat.br'),False)
 run(archive('manifest.zip',change=m.MANIFEST),False)
 run(archive('stripped.zip',drop=m.MANIFEST),False)
 bad=archive('build-corrupt.zip',change='system.new.dat.br')
 try:m.finish(bad)
 except ValueError:print('Build rejects changed payload: PASS')
 else:raise AssertionError('accepted changed payload')
 # Verify the integrity manifest survives the OTA signed-entry contract.
 spec=importlib.util.spec_from_file_location('ota',root/'scripts/internal/ota_metadata.py');ota=importlib.util.module_from_spec(spec);spec.loader.exec_module(ota)
 ota.KEY=t/'key.pem';ota.TRUST=t/'trust.der'
 for label,stamp in [('old',100),('new',200)]:
  with zipfile.ZipFile(t/(label+'.zip'),'w') as z:z.writestr('build_info.txt',f'version={label}\ntimestamp={stamp}\n')
 ota.package(p,t/'old.zip',t/'new.zip')
 m.prepare(p)
 ota.package(p,t/'old.zip',t/'new.zip')
 ota_zip=archive('ota.zip');m.finish(ota_zip)
 import json,base64,hashlib
 metadata=json.loads(base64.b64decode(json.loads((p/'unica/ota/update.json').read_bytes())['payload']))
 with zipfile.ZipFile(ota_zip) as z:
  for entry in z.infolist():
   if entry.filename=='unica/ota/update.json':continue
   assert metadata['entries'][entry.filename]==hashlib.sha256(z.read(entry)).hexdigest()
 print('OTA signed manifest includes integrity list and final wrapper: PASS')

# Exercise the actual option parsing without starting a ROM build.
with tempfile.TemporaryDirectory(prefix='unica-sha-cli-') as temp:
 t=pathlib.Path(temp); (t/'scripts/utils').mkdir(parents=True)
 (t/'scripts/utils/build_utils.sh').write_text('LOGE() { echo "$*" >&2; }\n')
 helper=t/'scripts/build_release_ota.sh'
 helper.write_text('#!/bin/bash\necho "OTA:$UNICA_PACKAGE_SHA:$*"\n');helper.chmod(0o755)
 text=(root/'scripts/make_rom.sh').read_text().split('\nif $FORCE; then')[0]
 parser=t/'parse.sh';parser.write_text(text+'\necho "FULL:$UNICA_PACKAGE_SHA"\n')
 env=dict(os.environ,SRC_DIR=str(t),TARGET_CODENAME='dm3q',UNICA_PACKAGE_SHA='true')
 for flags,expected in [(['-r','-sha'],'FULL:true'),(['-sha','-r'],'FULL:true'),(['-r'],'FULL:false'),(['-ota','-sha'],'OTA:true:--build-auto'),(['-sha','-ota'],'OTA:true:--build-auto'),(['-ota'],'OTA:false:--build-auto')]:
  result=subprocess.run(['bash',str(parser),*flags],env=env,capture_output=True,text=True)
  assert result.returncode==0 and expected in result.stdout,(flags,result.stdout,result.stderr)
 for flags in [['-sha'],['-ota','-r','-sha']]:
  assert subprocess.run(['bash',str(parser),*flags],env=env,capture_output=True).returncode!=0
 print('Full ROM/OTA option parsing, both orders, opt-in reset and invalid combinations: PASS')
