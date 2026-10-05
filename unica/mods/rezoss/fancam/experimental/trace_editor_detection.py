#!/usr/bin/env python3
"""Bounded, read-only capture of Video Editor tensors for the public clip."""
import argparse
import json
from pathlib import Path
import sys
import time
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--frida-path', type=Path)
    parser.add_argument('--address', default='127.0.0.1:27183')
    parser.add_argument('--pid', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--seconds', type=int, default=120)
    parser.add_argument('--public-clip-only', action='store_true', required=True)
    parser.add_argument('--adb', default='/mnt/c/platform-tools/adb.exe')
    args = parser.parse_args()
    if args.seconds < 1 or args.seconds > 300:
        parser.error('--seconds must be 1..300')
    if args.frida_path:
        sys.path.insert(0, str(args.frida_path))
    import frida
    expected = 'e8921a043f2050d69d11f906820a400054c835bebd0f743bff7a33405c42f835'
    checksum = subprocess.check_output([args.adb, 'shell', 'sha256sum',
        '/system/lib64/libauto-reframing-arm64-v8a.so'], text=True).split()[0]
    command = subprocess.check_output([args.adb, 'exec-out', 'su', '-c',
        'cat /proc/' + str(args.pid) + '/cmdline']).split(b'\0')[0]
    if checksum != expected or command != b'com.sec.android.app.vepreload':
        raise ValueError('Pinned ABI or Video Editor process mismatch')
    args.output.mkdir(parents=True, exist_ok=False)
    events = []

    def received(message, data):
        if message['type'] != 'send':
            print(json.dumps(message), flush=True)
            return
        item = message['payload']
        events.append(item)
        if data:
            name = item['stage'] + '-' + str(item['index']) + '.raw'
            (args.output / name).write_bytes(data)
        (args.output / 'events.json').write_text(json.dumps(events, indent=2) + '\n')
        print(json.dumps(item), flush=True)

    device = frida.get_device_manager().add_remote_device(args.address)
    session = device.attach(args.pid)
    script = session.create_script(Path(__file__).with_suffix('.js').read_text())
    script.on('message', received)
    try:
        script.load()
        time.sleep(args.seconds)
    finally:
        try:
            script.unload()
        finally:
            session.detach()


if __name__ == '__main__':
    main()
