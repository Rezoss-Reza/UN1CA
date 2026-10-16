#!/usr/bin/env python3
# Copyright (c) 2026 Rezoss
# SPDX-License-Identifier: GPL-3.0-or-later

"""Build an unsigned, isolated AIOS 1.4.28 V73 candidate; never installs it.

Source buildenv.sh first. SDK is an extracted official QAIRT 2.44.0.260225
directory containing lib/. Output must be a new absolute directory under /tmp.
The normal ROM customization and OfflineLanguageModel experiments are unchanged.
"""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import zipfile

RUNTIME = {
    "lib/aarch64-android/libQnnHtp.so": "090e993822564851eab1405aff171643b21e644e3f696c95c96f2732aaed813a",
    "lib/aarch64-android/libQnnSystem.so": "7e69258e1278cc9b2bb62dbc6e2a52c227a100d6505a13fd6324a87993d0bba8",
    "lib/aarch64-android/libQnnHtpV73Stub.so": "ef0d0cbbdd423a81c19fb0f60d1451b602bcd60c08b3564f1c82e632fb59f157",
    "lib/aarch64-android/libQnnHtpNetRunExtensions.so": "950421ba9354c68e93fd73aaf37842d5963464292aaaf36aa327cc002a8f0064",
    "lib/hexagon-v73/unsigned/libQnnHtpV73Skel.so": "11c76e1d9bcc8a3168e47f73e48baba0da8e1ca03b7c429e4a5777eab5d20c5a",
}


def run(*args: str) -> None:
    subprocess.run(args, check=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    mod = Path(__file__).resolve().parent
    repo = mod.parents[3]
    apk = repo / "prebuilts/samsung/m3qxxx/system/priv-app/AIOSKernelService/AIOSKernelService.apk"
    if hashlib.sha256(apk.read_bytes()).hexdigest() != "fca2f25b65a4db919b162d02decd6dba8b6d4eafc95bc43b36c5a1c606a19112":
        parser.error("unsupported source APK")
    for rel, digest in RUNTIME.items():
        if hashlib.sha256((args.sdk / rel).read_bytes()).hexdigest() != digest:
            parser.error(f"runtime differs from validated QAIRT 2.44: {rel}")
    out = args.output
    if not out.is_absolute() or not out.resolve().is_relative_to(Path('/tmp')) or out.exists():
        parser.error("--output must be a new absolute directory under /tmp")
    out.mkdir()
    decoded = out / "decoded"
    tool = str(repo / "out/tools/bin/apktool")
    framework = str(repo / "out/tools/apktool/framework")
    run(tool, "d", "-j", "2", "-p", framework, "-o", str(decoded), str(apk))
    for patch in sorted((mod / "AIOSKernelService.apk").glob("*.patch")):
        with patch.open("rb") as stream:
            subprocess.run(["patch", "--dry-run", "--batch", "--fuzz=0", "-p1", "-d", str(decoded)], stdin=stream, check=True)
        with patch.open("rb") as stream:
            subprocess.run(["patch", "--batch", "--no-backup-if-mismatch", "--fuzz=0", "-p1", "-d", str(decoded)], stdin=stream, check=True)
    libs = decoded / "lib/arm64-v8a"
    for source, helper in (("libsnap_qnn.so", "patch_qnn_v73_device.py"), ("libssneural_vndk.so", "patch_sm8550_chipset.py")):
        fixed = out / source
        run("python3", str(mod / helper), str(libs / source), str(fixed))
        shutil.copyfile(fixed, libs / source)
    for rel in RUNTIME:
        src = args.sdk / rel
        dst = decoded / "assets/ssgen/libs" if "Skel" in src.name else libs
        shutil.copyfile(src, dst / src.name)
    # Remove only superseded members of this newly decoded temporary copy.
    (libs / "libQnnHtpV81Stub.so").unlink()
    (decoded / "assets/ssgen/libs/libQnnHtpV81Skel.so").unlink()
    candidate = out / "AIOSKernelService-v73-unsigned.apk"
    run(tool, "b", "-j", "2", "-p", framework, "-o", str(candidate), str(decoded))
    with zipfile.ZipFile(candidate) as archive:
        if archive.testzip() is not None:
            raise SystemExit("APK CRC validation failed")
        for rel, digest in RUNTIME.items():
            name = Path(rel).name
            member = f"assets/ssgen/libs/{name}" if "Skel" in name else f"lib/arm64-v8a/{name}"
            assert hashlib.sha256(archive.read(member)).hexdigest() == digest, member
        assert not any("V81" in name for name in archive.namelist())
    print(f"Unsigned candidate: {candidate}")
    print("Run probe_qnn_v73.c separately. APK namespace, model loading and inference require validation.")


if __name__ == "__main__":
    main()
