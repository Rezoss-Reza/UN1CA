#!/usr/bin/env python3
"""Generate an isolated cfg80211 header with the measured stock callback slot.
Does not change the reconstructed kernel source or output.
"""
from pathlib import Path
import hashlib, subprocess, tempfile
root = Path(__file__).resolve().parent
base = Path('/home/rezoss79/dev/sembilan/out/archived/wondertap-port-20261023/build-5.15.220/kernel/include/net/cfg80211.h')
expected = 'ea18def5387ca567274f3a9c23105ed4f2dcfd3de6f4e822490eb6560ee9ac87'
if hashlib.sha256(base.read_bytes()).hexdigest() != expected:
    raise SystemExit('Kernel cfg80211 header changed; recheck stock ABI before rebuilding')
work = Path(tempfile.mkdtemp(prefix='s23u-cfg80211-header.'))
target = work / 'include/net/cfg80211.h'
target.parent.mkdir(parents=True)
target.write_bytes(base.read_bytes())
for name in ['0009-Match-stock-cfg80211-ops-callback-layout.patch', '0010-Match-stock-external-auth-transmit-address-layout.patch', '0011-Restore-stock-SAE-auth-random-TA-feature-id.patch']:
    patch = root / 'patches' / name
    for options in [['--dry-run'], []]:
        subprocess.run(['patch', *options, '--fuzz=0', '-p1', '-i', str(patch)], cwd=work, check=True, stdout=subprocess.DEVNULL)
output = root / 'vendor-compat/include/net/cfg80211.h'
output.parent.mkdir(parents=True, exist_ok=True)
if output.exists() and output.read_bytes() != target.read_bytes():
    raise SystemExit('Existing generated compatibility header differs; preserved for review')
if not output.exists():
    output.write_bytes(target.read_bytes())
