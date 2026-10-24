#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Validate hook definitions in exact offline APKs, not method-ID references."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile
from dex_inventory import Dex

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("snapshot", type=Path, help="Folder containing artifacts.json and APK copies")
parser.add_argument("--output", type=Path, help="Write machine-readable evidence")
args = parser.parse_args()
profiles = json.loads((Path(__file__).resolve().parent.parent / "targets.json").read_text())
artifacts = json.loads((args.snapshot / "artifacts.json").read_text())
results = []
for package, profile in profiles.items():
    matches = [r for r in artifacts if r["package"] == package and r["sha256"] == profile["sha256"]]
    if len(matches) != 1:
        raise SystemExit("Missing or ambiguous pinned APK: " + package)
    path = Path(matches[0]["local_path"])
    if hashlib.sha256(path.read_bytes()).hexdigest() != profile["sha256"]:
        raise SystemExit("APK hash mismatch: " + package)
    found = {signature: [] for signature, _ in profile["targets"]}
    with zipfile.ZipFile(path) as apk:
        for member in apk.namelist():
            if not re.fullmatch(r"classes\d*\.dex", member):
                continue
            dex = Dex(apk.read(member))
            for index, flags, code in dex.declared():
                signature = dex.signature(index)
                if signature in found:
                    found[signature].append({"dex": member, "access": flags, "code_offset": code,
                                             "concrete": bool(code) and not (flags & 0x500)})
    for signature, definitions in found.items():
        valid = len(definitions) == 1 and definitions[0]["concrete"]
        results.append({"package": package, "sha256": profile["sha256"], "target": signature,
                        "valid": valid, "definitions": definitions})
        print("PASS" if valid else "FAIL", package, signature)
if args.output:
    args.output.write_text(json.dumps({"targets": results,
        "limitation": "Offline declarations and concrete code only; not runtime execution proof."},
        indent=2) + "\n")
raise SystemExit(0 if all(r["valid"] for r in results) else 1)
