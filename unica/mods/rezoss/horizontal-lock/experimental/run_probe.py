#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Bounded native probe, with exact firmware checks and mode-2 rollback."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import sys

ADB = '/mnt/c/platform-tools/adb.exe'
COMPONENTS = '/vendor/lib64/camera/components/'
HASHES = {
    'com.qti.node.eisv3.so': '7e4f72bc10082e074424fe6f5a9d411b1740d2b9b3a9df3cec8ae4f1d4ec26a7',
    'com.qti.eisv3.so': '26dce4357773abe512592f4400e45e35d863ac53c1d78708c873ade1d4792abc',
}

def remote(command):
    return [ADB, 'shell', 'su -c ' + shlex.quote(command)]

def read(command):
    return subprocess.run(remote(command), check=True, capture_output=True, text=True).stdout

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--seconds', type=int, default=180, choices=range(1, 181))
    parser.add_argument('--enable-180', action='store_true', help='Experimental mode-2 init override; requires Camera restart at cleanup')
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Output already exists; use a fresh evidence file')
    for name, expected in HASHES.items():
        actual = read('sha256sum ' + shlex.quote(COMPONENTS + name)).split()[0]
        if actual != expected:
            raise RuntimeError('Unsupported firmware: ' + name)
    providers = [line.split() for line in read('ps -A -o PID,NAME').splitlines() if line.split()[-1:] == ['vendor.samsung.hardware.camera.provider-service_64']]
    if len(providers) != 1:
        raise RuntimeError('Expected exactly one Samsung camera provider')
    pid = int(providers[0][0])
    tids = read(f'ls /proc/{pid}/task').split()
    for tid in tids:
        if not tid.isdecimal():
            raise RuntimeError('Invalid task identifier')
        status = read(f'cat /proc/{pid}/task/{tid}/status')
        if 'TracerPid:\t0' not in status:
            raise RuntimeError('Provider already traced; refusing to interfere')
    device_binary = '/data/local/tmp/s23-horizon-frame-trace'
    subprocess.run([ADB, 'push', str(args.binary.resolve()), device_binary], check=True, stdout=subprocess.DEVNULL)
    expected = hashlib.sha256(args.binary.read_bytes()).hexdigest()
    if read('sha256sum ' + device_binary).split()[0] != expected:
        raise RuntimeError('Probe transfer checksum mismatch')
    read('chmod 755 ' + device_binary)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    command = f'HORIZON_TRACE_SECONDS={args.seconds} HORIZON_ENABLE_180={int(args.enable_180)} {device_binary} {pid}'
    armed = False
    rows = []
    with args.output.open('x') as output:
        proc = subprocess.Popen(remote(command), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        try:
            for line in proc.stdout:
                output.write(line)
                output.flush()
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    print(line.rstrip(), flush=True)
                    continue
                rows.append(row)
                if row['event'] == 'armed':
                    armed = True
                if row['event'] != 'eis3_frame':
                    print(line.rstrip(), flush=True)
            result = proc.wait()
        except KeyboardInterrupt:
            # Tell the native tracer to restore instructions and detach gracefully.
            tracer = next((r['tracer_pid'] for r in rows if r['event'] == 'armed'), None)
            if tracer:
                read(f'kill -TERM {int(tracer)}')
                for line in proc.stdout:
                    output.write(line)
                    output.flush()
                    try:
                        row = json.loads(line)
                        rows.append(row)
                    except json.JSONDecodeError:
                        pass
                proc.wait(timeout=10)
            raise
        finally:
            # An init override remains in the algorithm context after hooks detach.
            # Restart Camera only after restoration has been reported by the probe.
            restored = any(r['event'] == 'code_restored' and r['verified'] for r in rows)
            detached = any(r['event'] == 'detached' and r['failures'] == 0 for r in rows)
            if args.enable_180 and armed and restored and detached:
                read('am force-stop com.sec.android.app.camera')
                read('am start -a android.media.action.STILL_IMAGE_CAMERA')
            elif armed and not (restored and detached):
                print('Native cleanup unverified; do not start another probe. Inspect provider before continuing.', file=sys.stderr)
    if result or not (restored and detached):
        raise RuntimeError('Probe did not finish with verified cleanup')
    print('Capture saved: ' + str(args.output), flush=True)

if __name__ == '__main__':
    main()
