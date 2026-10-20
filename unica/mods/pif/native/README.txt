ROM PIF native Build/property backend (dm3q, source SDK 37)
========================================================

Scope
-----
Matches the enabled features of the measured Inject configuration: complete
Build identity and process-local native property virtualization in exact
com.google.android.gms.unstable. Play Store Build spoofing is optional and off
by default. SDK_INT, provider replacement and platform-signature spoofing are
not implemented. The existing native TEE Simulator/Settings keybox backend is
unchanged. Native mode does not install the legacy DroidGuard certificate-chain
blocker, matching Inject with spoofProvider=false.

The native mapping implementation is pinned upstream GPL-3.0 code; provenance
and license are in source/vendor. This is a ROM JNI integration, not a Zygisk
module. It does not reproduce FORCE_DENYLIST_UNMOUNT, root hiding or module
boot scripts. Existing ROM prophide/root components retain their responsibilities.

Startup and fallback
--------------------
The SDK-37 ZygoteInit.zygoteInit hook runs only in a specialized child, after its
name is assigned and before RuntimeInit.commonInit/application startup. A Java
name/enable guard prevents library loading in the parent zygote, system_server
and unrelated apps. Native property reads are covered before app initialization;
this is later than Inject's preAppSpecialize callback and requires device proof
under the actual app SELinux/linker/seccomp environment.

Failure to load/initialize the library is logged as UnicaPIF; the existing legacy
Java PIF path remains available. Native mapping validation failures roll back to
the original file mappings. No system property files or global values are written.

Controls (read at process startup; restart/boot needed for changes)
----------------------------------------------------------------
persist.sys.unica.pif           existing master switch, default true
persist.sys.pif.spoof_build     default true
persist.sys.pif.spoof_props     default true (GMS unstable only)
persist.sys.pif.vending_build   default false (exact Play Store main process)

Existing persist.sys.pif identity, fingerprint, security_patch and first_api_level
inputs are reused. Fingerprint/security-patch/first-API defaults are generated
from framework.jar/0001-Introduce-PlayIntegrityHooks.patch during the native build.

In native mode, null/empty identity fields now mean AUTO: derive BRAND, PRODUCT,
DEVICE, RELEASE, ID, INCREMENTAL, TYPE and TAGS from the fingerprint, rather than
leave a mixture of stock Samsung fields and a Pixel fingerprint. Explicit values
win. Only the Google bluejay profile supplies automatic Google / Pixel 6a
manufacturer/model defaults. Other devices require those explicit inputs.
Literal null security_patch or first_api_level still skips those optional
inputs. Board first API remains 32, matching the upstream property virtualizer.

Property coverage
-----------------
ro.build.fingerprint, ro.build.id, ro.product.device, ro.product.name,
ro.product.brand, ro.product.model, ro.build.version.security_patch,
ro.product.first_api_level, ro.board.first_api_level, init.svc.adbd, sys.usb.state.
Private file-backed mappings preserve property addresses and original VMA identity.
Shell getprop and other processes retain their own/global view. Vendor API level
and ro.product.manufacturer are not added to upstream's property override set.

Build and checks
----------------
NDK 28.2.13676358, Android SDK/build-tools 37.0.0, JDK, project Apktool required.

    python3 unica/mods/pif/native/build_native.py
    python3 unica/mods/pif/native/build_native.py --check
    python3 unica/mods/pif/native/tests/run.py
    bash -n unica/mods/pif/customize.sh
    git diff --check

Builds occur under /tmp, never in out/fw or out/target. Both ARM64 and ARM32
payloads and the generated smali PATCH are checked in as build inputs. The build
hash manifest rejects stale source, preceding PIF patches or modified payloads.
Original customize.sh and regenerated patch/payload backups are kept locally.
The installer copies only the named .so files, not their backups.

Host tests cover real private remapping of synthetic property files, long-value
demotion, original backing-file preservation, unrelated fields, rollback,
invalid areas, oversized values and descriptor cleanup, with UBSan. A host JVM
with -Xcheck:jni executes the actual Java startup guard and JNI Build bridge:
GMS success, parent/unrelated exclusions, master-off, Vending off/opt-in,
property-failure fallback and Build-off. The host property API is a fixture;
these tests do not prove Android namespace/SELinux support or an Integrity verdict.

Deployment boundary
-------------------
Rebuild/flash/boot and fresh request testing are still required. Disable the
external Inject module for a controlled ROM-only test and enable the ROM PIF
switch after the new framework/libraries are installed. Do not run both backends
in one process: overlapping private remaps and Build writes invalidate comparison.
Do not erase app data or keybox material. Verify UnicaPIF startup logs and
process-local native values, plus fresh TEE readiness and the actual verdict.
