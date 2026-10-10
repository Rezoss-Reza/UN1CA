# SPDX-License-Identifier: GPL-3.0-or-later
import base64,importlib.util,json,pathlib,subprocess,tempfile,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[5]
spec=importlib.util.spec_from_file_location('metadata',ROOT/'scripts/internal/ota_metadata.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
with tempfile.TemporaryDirectory() as tmp:
 p=pathlib.Path(tmp);m.KEY=p/'key.pem';m.TRUST=p/'trust.der';m.ensure_key()
 for name,stamp in [('old',100),('new',200)]:
  with zipfile.ZipFile(p/(name+'.zip'),'w') as z:z.writestr('build_info.txt',f'version={name}\ntimestamp={stamp}\n')
 directory=p/'package';directory.mkdir();(directory/'system.new.dat').write_bytes(b'payload')
 m.package(directory,p/'old.zip',p/'new.zip')
 outer=json.loads((directory/'unica/ota/update.json').read_bytes());raw=base64.b64decode(outer['payload']);signature=base64.b64decode(outer['signature'])
 (p/'raw').write_bytes(raw);(p/'sig').write_bytes(signature)
 subprocess.run(['openssl','pkey','-pubin','-inform','DER','-in',str(m.TRUST),'-out',str(p/'pub.pem')],check=True,capture_output=True)
 cmd=['openssl','dgst','-sha256','-verify',str(p/'pub.pem'),'-signature',str(p/'sig'),str(p/'raw')]
 assert subprocess.run(cmd,capture_output=True).returncode==0
 assert json.loads(raw)['base_timestamp']=='100'
 assert 'unica/ota/update.properties' in json.loads(raw)['entries']
 (p/'raw').write_bytes(raw+b' ');assert subprocess.run(cmd,capture_output=True).returncode!=0
 archive=p/'OTA_test.zip'
 with zipfile.ZipFile(archive,'w') as z:
  for f in directory.rglob('*'):
   if f.is_file():z.write(f,f.relative_to(directory).as_posix())
 m.sidecar(archive);record=json.loads(base64.b64decode(json.loads(pathlib.Path(str(archive)+'.ota.json').read_bytes())['payload']))
 assert record['filename']=='OTA_test.zip' and record['size']==archive.stat().st_size and len(record['sha256'])==64
 m.KEY.unlink()
 try:m.ensure_key()
 except ValueError:pass
 else:raise AssertionError('Lost key silently replaced')
 print('PASS: signed metadata, tamper rejection, protected recovery identity, Drive record and lost-key refusal')
