#!/usr/bin/env python3
# Copyright (c) 2026 Rezoss
# SPDX-License-Identifier: GPL-3.0-or-later

"""Retarget the original AIOS 1.4.28 wrapper; requires QNN core >= 2.29.

Never combine with patch_qnn_backend_v73_fallback.py: backend/device/perf
callbacks must execute. REA addresses use image base 0x100000; these are file
offsets in the original ELF (SHA256 below). No instruction sizes change.
"""
import argparse
import hashlib
from pathlib import Path

ORIGINAL_SHA256 = "cf5021fdfa25a5aaa554061acbb98af3adb5aabfc560c458ac1235b981672538"
EDITS = (
    (0x8C8A8, "01030054", "1f2003d5", "reject every loader error before logging/callbacks"),
    (0x8C924, "280a8052", "28098052", "initializeBackend: V81 -> V73"),
    (0x8D2D8, "290a8052", "29098052", "CreateDevice: V81 -> V73"),
    (0x61850, "0000000057000000", "000000002b000000", "device SOC: SM8850 (87) -> SM8550 (43)"),
)


def patch(data: bytes) -> bytes:
    restored = bytearray(data)
    for offset, old_hex, new_hex, label in EDITS:
        old, new = bytes.fromhex(old_hex), bytes.fromhex(new_hex)
        if data[offset:offset + len(old)] not in (old, new):
            raise ValueError(f"unexpected bytes at 0x{offset:x}: {label}")
        restored[offset:offset + len(old)] = old
    if hashlib.sha256(restored).hexdigest() != ORIGINAL_SHA256:
        raise ValueError("unsupported or previously bypass-patched libsnap_qnn.so")
    output = bytearray(data)
    for offset, old_hex, new_hex, label in EDITS:
        new = bytes.fromhex(new_hex)
        output[offset:offset + len(new)] = new
        print(f"0x{offset:x}: {label}")
    return bytes(output)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output already exists; choose a new path")
    try:
        output = patch(args.input.read_bytes())
    except ValueError as exc:
        parser.error(str(exc))
    with args.output.open("xb") as stream:
        stream.write(output)


if __name__ == "__main__":
    main()
