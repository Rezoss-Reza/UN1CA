UN1CA early boot Volume Up to recovery

Restricted to dm3q by customize.sh. No root-manager module is needed.
The ROM build copies payload/system into /system, installs dedicated SELinux
policy and labels the native executable.

Startup: asynchronous init start at post-fs, before post-fs-data. The helper is
statically linked and does not require /data, APEX or framework startup.
It discovers devices advertising KEY_VOLUMEUP and samples current key state
every 20 ms. A continuous 600 ms hold within the seven-second startup window
requests recovery reboot. An already-held button qualifies. Init also imposes
a nine-second watchdog. No input grab is used. Missing inputs or ioctl errors
never trigger recovery.

Recovery reboot is ENABLED. The helper sets only the fixed boolean property
sys.unica.volumeup.recovery=1. Init responds with sys.powerctl=reboot,recovery.
The helper has no direct power-control permission, sys_boot capability,
shell execution or configurable command. A rejected request is logged and the
helper exits without retrying. Normal boot continues if no hold is detected.
Risk: a false detection or stuck Volume Up button can repeatedly enter recovery.

Emergency disable from recovery:
1. Mount /metadata WITHOUT formatting or wiping it (use recovery's Mount menu).
2. In the recovery terminal or recovery ADB shell, run:
     touch /metadata/unica-volumeup.disabled
3. Reboot normally. The marker is checked before input detection on every boot.
   The marker is not checked again after the seven-second detector has started.
   If checking the marker is denied, the helper also exits without rebooting.
To re-enable, remove only that marker from recovery:
     rm /metadata/unica-volumeup.disabled
No writes to /metadata are performed by the helper itself.
If recovery cannot mount /metadata, restore the previous working ROM image.

Build the helper only when its source changes:
  bash unica/mods/volumeup-action/build.sh
Uses ANDROID_NDK_HOME or ~/Android/Sdk/ndk/28.2.13676358. Previous changed
binaries and temporary output are retained in /tmp/unica-volumeup-build.*.
The ROM customization checks build.sha256 to reject stale native artifacts.

Offline validation (no ROM build; policy copies are made under /tmp):
  bash -n unica/mods/volumeup-action/{build,customize}.sh
  SECILC=/path/to/android-compatible/secilc python3 unica/mods/volumeup-action/tests/verify.py out/target/dm3q/work_dir
Requires g++ and an Android-compatible secilc with nlmsg extended-permission
support. Policy compilation uses Android runtime settings with neverallow
checks disabled; this is not a full platform SELinux assertion check.

Validation performed on 2026-10-18:
- Recovery-enabled ARM64 static helper compiled with NDK 28.2.
- Host tests passed hold threshold, release/reset and initially-held-key handling.
- Fixed request/property/init wiring and restricted policy checks passed.
- Policy installer passed idempotence; exact combined ROM policy compiled.
- The earlier DIAGNOSTIC binary ran temporarily under su on SM-S918B, discovered
  an input and timed out normally. This does not test the recovery-enabled binary.
The recovery-enabled binary has NOT been run on the phone. No ROM build,
flash or reboot was performed. Boot-stage timing, dedicated-domain access,
emergency marker and actual recovery reboot remain unverified on-device.

After installing a future ROM build containing this mod:
- Boot without Volume Up: expect normal boot and TIMEOUT kernel log.
- Hold Volume Up during the early window: expect reboot into recovery.
Kernel logs (retrieve soon after boot; ring buffer is not persistent):
  /mnt/c/platform-tools/adb.exe shell "su -c 'dmesg | grep UN1CA_VOLUMEUP'"
  /mnt/c/platform-tools/adb.exe shell "su -c 'dmesg | grep unica_volumeup'"

Build-time rollback: add disable in this mod folder, then regenerate from
clean normal build inputs. Already-generated outputs retain copied files.
Diagnostic implementation backup: /tmp/unica-volumeup-before-recovery.9jkStk/volumeup-action
This feature cannot recover bootloader/kernel failures before Android init.
