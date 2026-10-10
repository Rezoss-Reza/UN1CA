#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Signed updater metadata. Private publisher key lives outside the checkout."""
import base64, hashlib, json, os, pathlib, subprocess, sys, zipfile
ROOT = pathlib.Path(__file__).resolve().parents[2]
TRUST = ROOT / 'unica/mods/settings/ota/trust.der'
KEY = pathlib.Path(os.environ.get('UNICA_OTA_SIGNING_KEY', str(pathlib.Path.home()/'.config/unica/ota-signing.pem')))

def ensure_key():
    if not KEY.exists():
        if TRUST.exists():
            raise ValueError('Publisher key missing; restore the original OTA signing key')
        KEY.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(['openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:3072','-out',str(KEY)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        KEY.chmod(0o600)
    public = subprocess.check_output(['openssl','pkey','-in',str(KEY),'-pubout','-outform','DER'])
    if TRUST.exists() and TRUST.read_bytes()!=public:
        raise ValueError('Publisher key does not match the ROM updater trust key')
    if not TRUST.exists():
        TRUST.parent.mkdir(parents=True,exist_ok=True); TRUST.write_bytes(public)
    return public

def envelope(payload):
    ensure_key()
    raw=json.dumps(payload,sort_keys=True,separators=(',',':')).encode()
    signature=subprocess.run(['openssl','dgst','-sha256','-sign',str(KEY)],input=raw,capture_output=True,check=True).stdout
    return json.dumps({'payload':base64.b64encode(raw).decode(),'signature':base64.b64encode(signature).decode()},separators=(',',':')).encode()

def props(z):
    return dict(l.split('=',1) for l in z.read('build_info.txt').decode().splitlines() if '=' in l)

def hash_stream(f):
    h=hashlib.sha256()
    while chunk:=f.read(1024*1024): h.update(chunk)
    return h.hexdigest()

def package(directory,old,new):
    with zipfile.ZipFile(old) as a,zipfile.ZipFile(new) as b: source,target=props(a),props(b)
    metadata={'schema':1,'device':'dm3q','base_timestamp':source['timestamp'],'target_timestamp':target['timestamp'],
              'base_version':source['version'],'target_version':target['version'],'entries':{}}
    for f in sorted(directory.rglob('*')):
        if not f.is_file(): continue
        name=f.relative_to(directory).as_posix()
        if name in ('unica/ota/update.json','unica/ota/update.properties'): continue
        with f.open('rb') as stream: metadata['entries'][name]=hash_stream(stream)
    # Properties must be covered by the signature as recovery uses these fields.
    p=directory/'unica/ota/update.properties'; p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text('device=dm3q\nbase_timestamp='+metadata['base_timestamp']+'\ntarget_timestamp='+metadata['target_timestamp']+'\n')
    metadata['entries']['unica/ota/update.properties']=hashlib.sha256(p.read_bytes()).hexdigest()
    (p.parent/'update.json').write_bytes(envelope(metadata))

def sidecar(archive):
    with zipfile.ZipFile(archive) as z:
        inner=json.loads(z.read('unica/ota/update.json'))
        payload=json.loads(base64.b64decode(inner['payload']))
    payload.pop('entries')
    payload.update(filename=archive.name,size=archive.stat().st_size)
    with archive.open('rb') as stream: payload['sha256']=hash_stream(stream)
    output=pathlib.Path(str(archive)+'.ota.json')
    with output.open('xb') as f: f.write(envelope(payload))
    print('Signed Drive catalog record: '+str(output))

if __name__=='__main__':
    if sys.argv[1]=='key': ensure_key()
    elif sys.argv[1]=='package': package(*(pathlib.Path(p) for p in sys.argv[2:]))
    elif sys.argv[1]=='sidecar': sidecar(pathlib.Path(sys.argv[2]))
