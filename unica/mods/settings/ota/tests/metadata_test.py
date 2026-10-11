# SPDX-License-Identifier: GPL-3.0-or-later
import base64,hashlib,importlib.util,json,pathlib,shutil,subprocess,tempfile,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[5]
spec=importlib.util.spec_from_file_location('metadata',ROOT/'scripts/internal/ota_metadata.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
with tempfile.TemporaryDirectory() as tmp:
 p=pathlib.Path(tmp);m.KEY=p/'key.pem';m.TRUST=p/'trust.der';m.ensure_key()
 for name,stamp in [('old',100),('new',200)]:
  with zipfile.ZipFile(p/(name+'.zip'),'w') as z:z.writestr('build_info.txt',f'version={name}\ntimestamp={stamp}\n')
 directory=p/'package';directory.mkdir();(directory/'system.new.dat').write_bytes(b'payload')
 cert=directory/'META-INF/com/android/otacert';cert.parent.mkdir(parents=True)
 cert.write_bytes((ROOT/'security/aosp_testkey.x509.pem').read_bytes())
 m.package(directory,p/'old.zip',p/'new.zip')
 outer=json.loads((directory/'unica/ota/update.json').read_bytes());raw=base64.b64decode(outer['payload']);signature=base64.b64decode(outer['signature'])
 (p/'raw').write_bytes(raw);(p/'sig').write_bytes(signature)
 subprocess.run(['openssl','pkey','-pubin','-inform','DER','-in',str(m.TRUST),'-out',str(p/'pub.pem')],check=True,capture_output=True)
 cmd=['openssl','dgst','-sha256','-verify',str(p/'pub.pem'),'-signature',str(p/'sig'),str(p/'raw')]
 assert subprocess.run(cmd,capture_output=True).returncode==0
 assert json.loads(raw)['base_timestamp']=='100'
 assert 'unica/ota/update.properties' in json.loads(raw)['entries']
 assert json.loads(raw)['entries']['META-INF/com/android/otacert']==hashlib.sha256(cert.read_bytes()).hexdigest()
 (p/'raw').write_bytes(raw+b' ');assert subprocess.run(cmd,capture_output=True).returncode!=0
 archive=p/'OTA_test.zip'
 with zipfile.ZipFile(archive,'w') as z:
  for f in directory.rglob('*'):
   if f.is_file():z.write(f,f.relative_to(directory).as_posix())
 if shutil.which('signapk'):
  signed=p/'signed.zip'
  subprocess.run(['signapk','-w',str(ROOT/'security/aosp_testkey.x509.pem'),str(ROOT/'security/aosp_testkey.pk8'),str(archive),str(signed)],check=True)
  with zipfile.ZipFile(signed) as z:
   entries=json.loads(raw)['entries']
   assert json.loads(z.read('unica/ota/update.json'))==outer
   for entry in z.infolist():
    if entry.is_dir() or entry.filename=='unica/ota/update.json':continue
    assert entry.filename in entries,entry.filename
    assert hashlib.sha256(z.read(entry)).hexdigest()==entries[entry.filename],entry.filename
  print('PASS: real signapk output retains signed certificate and payload hashes')
 m.sidecar(archive);record=json.loads(base64.b64decode(json.loads(pathlib.Path(str(archive)+'.ota.json').read_bytes())['payload']))
 assert record['filename']=='OTA_test.zip' and record['size']==archive.stat().st_size and len(record['sha256'])==64
 m.KEY.unlink()
 try:m.ensure_key()
 except ValueError:pass
 else:raise AssertionError('Lost key silently replaced')
 print('PASS: signed metadata, tamper rejection, protected recovery identity, Drive record and lost-key refusal')
