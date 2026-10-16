#!/usr/bin/env python3
# Copyright (c) 2026 UN1CA contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Build the dm3q storage hardening APEX in a fresh temporary workspace.

Requires the sourced ROM build environment and the same sudo/mount and signing
tools used by unica/patches/bluetooth. Never writes its input or out/target.
"""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile


BASELINE = "dc2ccfb8bd58f6510c313ea9b79b767a2c55e6eecd70fe1ce04f3bfbe1facd96"
CAPEX_BASELINE = "385ea299eba361b8d47d819e6fe5aef2c4073d3d3d4ba92324f80bd981cd52bc"


def run(*args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def verify_contents(original, signed, changed=()):
    with zipfile.ZipFile(original) as before, zipfile.ZipFile(signed) as after:
        def entries(archive):
            return {i.filename for i in archive.infolist() if not i.is_dir()
                    and not i.filename.startswith("META-INF/") and i.filename != "stamp-cert-sha256"}
        names = entries(before)
        if names != entries(after) or after.testzip() is not None:
            raise ValueError("APK entries changed unexpectedly")
        for name in names - set(changed):
            if before.read(name) != after.read(name):
                raise ValueError(f"Unrelated APK entry changed: {name}")


def build(source, work):
    repo = Path(os.environ["SRC_DIR"])
    tools = repo / "out/tools/bin"
    mod = Path(__file__).resolve().parents[1]
    if not work.is_dir() or any(work.iterdir()):
        raise ValueError("Use an empty temporary workspace")
    if repo / "out" in work.resolve().parents:
        raise ValueError("Workspace must be outside out/")
    if hashlib.sha256(source.read_bytes()).hexdigest() != CAPEX_BASELINE:
        raise ValueError("Unknown MediaProvider APEX; revalidate against the new firmware")
    cert = "unica" if os.environ.get("ROM_IS_OFFICIAL") == "true" else "aosp"
    certificate = repo / f"security/{cert}_platform.x509.pem"
    key = repo / f"security/{cert}_platform.pk8"
    avb_key = repo / "security/avb/testkey_rsa4096.pem"
    for required in (certificate, key, avb_key):
        if not required.is_file():
            raise FileNotFoundError(required)
    with zipfile.ZipFile(source) as capex:
        (work / "original.apex").write_bytes(capex.read("original_apex"))
    with zipfile.ZipFile(work / "original.apex") as apex:
        for name in ("apex_payload.img", "apex_manifest.pb"):
            (work / name).write_bytes(apex.read(name))
    mounted = work / "mounted"
    mounted.mkdir()
    payload = work / "payload"
    run("sudo", "-n", "mount", "-o", "loop,ro", work / "apex_payload.img", mounted)
    try:
        # Generate exact ownership/mode/SELinux metadata before copying. Do not
        # substitute generic labels for executable files inside the APEX.
        metadata = r'''
import os, pathlib, re, stat, sys
root=pathlib.Path(sys.argv[1]); dest=pathlib.Path(sys.argv[2])
paths=[root]+sorted(root.rglob('*'))
fs=[]; labels=[]
for path in paths:
    rel=path.relative_to(root).as_posix()
    if rel=='lost+found' or rel.startswith('lost+found/'):
        continue
    rel='' if rel=='.' else rel
    info=path.lstat()
    label=os.getxattr(path,'security.selinux',follow_symlinks=False).decode().rstrip('\0')
    # This baseline has no file capabilities; fail rather than silently drop
    # them if a future payload introduces a capability-bearing executable.
    if 'security.capability' in os.listxattr(path,follow_symlinks=False):
        raise ValueError(f'Unexpected file capabilities: {path}')
    fs.append(f'{rel or "/"} {info.st_uid} {info.st_gid} {stat.S_IMODE(info.st_mode):o} capabilities=0x0\n')
    # libselinux's literal-prefix matcher does not treat escaped hyphens as
    # Python's regex engine does. Hyphens outside character classes are literal.
    escaped=re.escape('/'+rel).replace('\\-', '-')
    labels.append(f'{escaped} {label}\n')
(dest/'fs_config').write_text(''.join(fs))
(dest/'file_contexts').write_text(''.join(labels))
'''
        run("sudo", "-n", "python3", "-c", metadata, mounted, work)
        run("sudo", "-n", "cp", "-a", mounted, payload)
    finally:
        run("sudo", "-n", "umount", mounted)
    run("sudo", "-n", "chown", "-hR", f"{os.getuid()}:{os.getgid()}", payload,
        work / "fs_config", work / "file_contexts")
    lost = payload / "lost+found"
    if lost.is_dir():
        lost.rmdir()  # Refuse to discard unexpected contents.
    apks = list(payload.glob("priv-app/MediaProviderGoogle@*/MediaProviderGoogle.apk"))
    if len(apks) != 1:
        raise ValueError("Expected exactly one MediaProviderGoogle APK")
    apk = apks[0]
    if hashlib.sha256(apk.read_bytes()).hexdigest() != BASELINE:
        raise ValueError("Unknown MediaProvider baseline; revalidate storage patch before updating")
    decoded = work / "decoded"
    run(tools / "apktool", "d", "-r", "--no-debug-info", apk, "-o", decoded)
    patch = mod / "storage/MediaProviderGoogle.apk/0001-Normalize-FUSE-directory-paths.patch"
    for dry in (True, False):
        args = ["patch", "--fuzz=0", "-p1", "-N", "--forward", "-d", decoded, "-i", patch]
        if dry:
            args.append("--dry-run")
        run(*args)
    run(tools / "apktool", "b", decoded, "-o", work / "media-unsigned.apk")
    with zipfile.ZipFile(work / "media-unsigned.apk") as rebuilt:
        if rebuilt.testzip() is not None:
            raise ValueError("Corrupt rebuilt APK")
        dex = rebuilt.read("classes.dex")
        if len(dex) < 112 or not dex.startswith(b"dex\n"):
            raise ValueError("Missing or invalid MediaProvider DEX")
    run(tools / "signapk", certificate, key, work / "media-unsigned.apk", work / "media-signed.apk")
    verify_contents(apk, work / "media-signed.apk", changed=("classes.dex",))
    shutil.copyfile(work / "media-signed.apk", apk)
    # The companion picker requests MediaProvider's signature-only
    # MANAGE_CLOUD_MEDIA_PROVIDERS permission; keep their signing identities equal.
    pickers = list(payload.glob("priv-app/PhotopickerGoogle@*/PhotopickerGoogle.apk"))
    if len(pickers) != 1:
        raise ValueError("Expected exactly one companion PhotoPicker APK")
    run(tools / "signapk", certificate, key, pickers[0], work / "picker-signed.apk")
    verify_contents(pickers[0], work / "picker-signed.apk")
    shutil.copyfile(work / "picker-signed.apk", pickers[0])
    verifier = mod / "tools/VerifySignedPackage.java"
    run("java", "--class-path", tools / "signapk.jar", verifier, certificate, apk, pickers[0])
    for package in (apk, pickers[0]):
        run(tools / "zipalign", "-c", "-p", "4", package)
    run(repo / "scripts/build_fs_image.sh", "ext4", "--no-avb", "-o", work / "patched.img",
        "-p", "system", payload, work / "file_contexts", work / "fs_config")
    salt = hashlib.sha256((work / "apex_manifest.pb").read_bytes()).hexdigest()
    run(tools / "avbtool", "add_hashtree_footer", "--do_not_generate_fec", "--algorithm",
        "SHA256_RSA4096", "--hash_algorithm", "sha256", "--key", avb_key, "--prop",
        "apex.key:com.android.mediaprovider", "--salt", salt, "--image", work / "patched.img")
    run(tools / "avbtool", "extract_public_key", "--key", avb_key, "--output", work / "apex_pubkey")
    run(tools / "avbtool", "verify_image", "--image", work / "patched.img", "--key", avb_key)
    with zipfile.ZipFile(work / "original.apex") as original, zipfile.ZipFile(work / "unsigned.apex", "w") as output:
        for entry in original.infolist():
            if entry.filename.startswith("META-INF/"):
                continue
            replacement = {"apex_payload.img": work / "patched.img", "apex_pubkey": work / "apex_pubkey"}.get(entry.filename)
            output.writestr(entry, replacement.read_bytes() if replacement else original.read(entry))
    install = work / "install/system/system/apex"
    install.mkdir(parents=True)
    output = install / "com.google.android.mediaprovider.apex"
    run(tools / "signapk", "-a", "4096", "--align-file-size", certificate, key,
        work / "unsigned.apex", output)
    run("java", "--class-path", tools / "signapk.jar", verifier, certificate, output)
    run(tools / "zipalign", "-c", "4096", output)
    with zipfile.ZipFile(output) as result:
        if result.testzip() is not None:
            raise ValueError("Corrupt signed APEX")
    print(f"Validated storage APEX: {output}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Usage: source buildenv.sh dm3q; build_storage_apex.py <stock capex> <empty /tmp dir>")
    build(Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve())
