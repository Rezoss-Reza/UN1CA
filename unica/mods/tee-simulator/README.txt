UN1CA TEE Simulator ROM backend
==============================

Scope: dm3q, SDK 37, ARM64. Other targets skip this mod.
Upstream source and all five native dependency revisions: upstream.lock.json.
Bundled source: source/. GPL-3.0-or-later; see source/LICENSE and source/NOTICE.

Settings interface
------------------
The existing Samsung Settings menu is unchanged, including XML import, Google
root checks, revocation validation, clear, and the persistent switch:
  Settings.Secure, owner user 0: unica_keybox_data
  System property: persist.sys.unica.keybox

RomApp reads the existing setting through SettingsProvider's shell interface.
XML never appears in command arguments or logs, and is not copied to another
on-disk keybox file. SettingsProvider continues to own the persistent XML.
Secure-settings file events trigger a refresh; a 30-second fallback handles
missed events and newly installed apps. The switch is checked in-process every
second. Read failures disable new interception rather than keeping active scope.

Backend
-------
init launches a root app_process with a dedicated enforcing SELinux domain;
there is no dependency on Magisk, KernelSU, APatch, a WebUI, or resetprop.
The native library is injected into keystore2 and routes KeyMint transactions.
Owner-user app UIDs 10000-19999 are eligible, including Play services. Other
Android users and privileged/system UIDs keep their normal hardware path.
Only attestation requests and ATTEST_KEY creation are eligible for new simulation.
Ordinary unattested app keys and symmetric keys remain on the normal HAL path.
This request-level scope replaces the old Java SamsungKeystoreUtils stack check;
vendor-specific attestation callers still require device validation.

Default mode is upstream patch: retain the real hardware key and re-sign the
attestation when possible. Upstream software fallback and ATTEST_KEY support
remain. Software-generated keys do not gain real hardware protection.
Both EC-only RKP and EC+RSA signing keyboxes are supported by the pinned source.
ROM-reported patch levels are used; no calendar-based patch-level advancement.

Two small framework compatibility classes preserve Settings' backend-presence
check and the PIF API. They contain none of the old Java key generation/proxy/DB
implementation. PIF's customize.sh recognizes this active mod in build order.
PIF availability requires the original switch AND a recent native commit/heartbeat;
sys.unica.teesim.ready contains elapsedRealtime milliseconds, expires after 45s,
and is cleared on disconnect or a disabled/rejected configuration.

No existing-key purge, database migration, or certificate rewrite is launched by
the ROM daemon. Upstream WebUI/administration classes are not started. Imported
signing material is held in daemon/native memory and SettingsProvider only.

Key persistence and rollback
----------------------------
The old mod is disabled by unica/mods/keybox/disable. Its source patches are intact.
Old app-local unica_keystore.db entries are NOT migrated into keystore2. An app
using those software keys may need to recreate them; app data may be affected.
Do not enable both backends. Do not install another keystore injection module.

Disabling the Settings switch stops NEW interception. When XML remains imported,
a dormant signer/TA remains available for existing simulator blobs, including
after reboot. Clearing XML disables new interception and retains the last signer
in RAM only for the current daemon lifetime. After a reboot/daemon restart with
XML cleared, simulator-created keys require reimporting a compatible keybox.
No signer is retained as an extra on-disk keybox backup.

Frozen key-encryption state lives in /data/misc/unica-teesim/harvested.json, under
a root-only directory. Writes are atomic; a damaged or unwritable seed aborts
startup before injection instead of silently generating a replacement seed.
Do not delete this directory to troubleshoot existing keys.

Emergency recovery: stop unica-teesim from a privileged recovery/root shell,
create /data/misc/unica-teesim/disable, then reboot. The marker prevents injection.
For source rollback, disable this mod, remove the old keybox/disable marker, and
perform a clean ROM rebuild. Neither rollback path migrates keys between backends.

Build and verify
----------------
Checked-in ARM64 payload is in payload/; payload.sha256 verifies the binaries.
source.sha256 binds the payload to its source snapshot. customize.sh automatically
rebuilds changed sources in a fresh /tmp directory, then verifies the payload.
Unchanged sources reuse the existing payload. Modified/missing payload with
unchanged sources and decoded old Java hooks are rejected.
Automatic rebuilds require the backend toolchain listed below; failure stops
the ROM build before installation. Added and deleted source files also trigger a rebuild.
Use a CLEAN ROM build after switching backend; existing out/target was not edited.

Prerequisites for rebuilding: JDK 21; Android SDK 37; build-tools 37.0.0; NDK
28.2.13676358; CMake/Ninja; libclang (default /usr/lib/llvm-18/lib); Rust (tested
1.99.1), aarch64-linux-android target, cargo-ndk 4.1.2. Rust crates are locked;
JVM dependency versions and hashes are pinned. Native/D8 API floor is 35; the
daemon uses SDK 37 APIs. No binaries are compiled against source firmware inputs.

  python3 unica/mods/tee-simulator/build_backend.py
  PYTHONDONTWRITEBYTECODE=1 python3 -B unica/mods/tee-simulator/tests/test_integration.py
  bash -n unica/mods/tee-simulator/customize.sh unica/mods/pif/customize.sh
  (cd unica/mods/tee-simulator && sha256sum --check --status source.sha256)
  (cd unica/mods/tee-simulator/payload && sha256sum --check ../payload.sha256)
  git diff --check

Build work defaults to /tmp/unica-teesim-build. Missing native dependencies are
fetched by exact revision; --dependency-cache can reuse local clones. A previous
payload is preserved under /tmp/unica-teesim-payload-backup-* before replacement.
The initial build's isolated Rust toolchain is under /tmp/sembilan-teesim-toolchain;
its cargo/bin must be on PATH, with RUSTUP_HOME and CARGO_HOME set to that tree if
reusing it. The build process never installs host packages or modifies the phone.

Validation boundary
-------------------
Host: Kotlin/DEX build, ARM64 native build, strict patch dry-run/application,
compatibility smali assembly (API 35), payload ZIP integrity, native symbol
resolution against the current ROM including the runtime APEX, eight integration
tests, shell syntax, and boot-mode CIL compilation against the current platform,
system_ext, product, vendor and versioned mappings.

Policy compilation uses init's -N mode (neverallow checking disabled); it is not
a full source-policy neverallow certification. The existing mixed platform/vendor
policy also contains neverallow conflicts. No permissive domain is introduced.

Runtime repair validated on 2026-10-16
-------------------------------------
The initially flashed build contained the backend but failed to activate it.
Device evidence found missing system_server -> unica_teesim fd/fifo permissions
for SettingsProvider shellCommand, and missing unica_teesim -> keystore datagram
sendto for SCM_RIGHTS. The injector resumed a blocking recvmsg BEFORE sendmsg;
an EACCES on the send then left keystore's main thread running/blocking instead
of stopped, defeating register/socket cleanup and leaking descriptors on retry.

The source now queues the descriptor before resuming the target and uses a
nonblocking receive. Sender failures can safely close the remote socket while
the target remains stopped. Process discovery rejects nonnumeric /proc names
before stat, and injection/settings failure loops now back off. The narrow IPC
permissions are in policy/unica-teesim.cil.

The corrected payload was built, eight host tests passed, and boot-mode CIL
compilation passed. A removable KernelSU hotfix was installed for the current
ROM: module id unica_teesim_runtime_fix. Its temporary packaging scripts were retired
on 2026-10-17 after live verification of the built-in ROM backend without the module.
Remove this temporary module after flashing a ROM with the source repair.

For the live test the simulator service was stopped, replacement files were
bind-mounted, and keystore2 was restarted once to clear its blocked main thread
and leaked descriptors. KernelSU's live patch route reported a policy-copy
allocation failure; the same three narrow allow rules were applied to a copy
of the CURRENT live policy and loaded atomically, preserving its existing
root-manager rules. SELinux remained Enforcing throughout. This was a runtime
test, not a full ROM flash or a reboot-persistence test.

Observed live: library mapped in keystore2, authenticated control socket,
configuration acknowledgement applied=1/failed=0, fresh readiness heartbeat,
and Duck Detector's newly generated attestation requests handled in patch mode
with real hardware key blobs retained. A fresh Duck Detector scan changed its
Bootloader result from Unlocked/Danger to Clear: "Locked and attested verified".
Screenshots and hotfix ZIP: out/archived/tee-simulator-runtime-fix-20261016/.
Duck's separate root/mount/native-TEE findings remain; this is not a claim of
undetectability or a physical bootloader relock. No app-data clear, keybox change,
database purge, or key migration was performed.

Still untested: reboot persistence, XML import/toggle timing, both EC-only and
dual-keybox inputs, biometric-bound keys, banking apps, Play Integrity, and
keybox rotation on the rebuilt ROM. Host tests and one detector's bootloader
result do not establish these behaviors.

Retiring the temporary runtime module
------------------------------------
Duck Detector can observe the manually installed hotfix bind mount from an
isolated process, even when its normal process sees a different mount view.
The durable fix is a ROM containing the corrected injector/JAR and CIL rules,
followed by disabling/removing unica_teesim_runtime_fix and rebooting. Do not
remove it from the old ROM before the replacement is flashed: that restores
the broken injector and removes the hotfix policy rules on reboot.

The dm3q installer now packages the verified payload hashes and, after a
successful ROM/DFE installation, runs a guarded recovery cleanup. It checks the
newly installed injector/JAR/library hashes, init service, and three runtime
repair policy rules before adding a disable marker to the exact
unica_teesim_runtime_fix module in modules/ and modules_update/. It does not delete
modules, signer state, imported XML, or other user data. Read-only system mounts
opened for verification are released before the installer returns.

An old ROM, missing policy/service, unavailable data, or unexpected module layout
keeps the hotfix enabled. If recovery reports a skipped cleanup, disable the
module manually in KernelSU only after the corrected ROM is flashed. Reboot to
clear the old temporary mounts and use the built-in files. Check that
sys.unica.teesim.ready is fresh, SELinux is Enforcing, and a fresh Duck scan still
verifies attestation. Review the isolated-process mount evidence again; other
modules may produce additional findings. No unmount-only workaround is used.

Host-only migration checks (no ROM build and no device access):
  PYTHONDONTWRITEBYTECODE=1 python3 -B unica/mods/tee-simulator/tests/test_hotfix_retirement.py
Recovery execution, the next flash, and the resulting mount view remain untested.

On 2026-10-16 Duck was removed only from the owner's HMA protected targets.
All other HMA settings were preserved. Its fresh Dangerous Apps result changed
from HMA-style concealment/Danger to Warning for two visible risky packages.
The HMA policy is user data and survives a normal ROM update without a wipe;
reimporting an older HMA policy can reintroduce the protected target.

2026-10-17 live verification: temporary module absent from modules/ and
modules_update/, all three installed backend hashes match payload/, service
running with fresh readiness heartbeat, SELinux Enforcing, native library
mapped in keystore2, and successful patch-mode attestations in current-boot logs.
This does not establish every banking app, keybox rotation, or biometric flow.
