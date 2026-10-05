S23U Horizontal Lock ROM experiment
==================================
Enabled by rezoss/customize.sh for dm3q only. Requires the exact original
S918BXXUAZZI8 EIS node and algorithm hashes; another firmware aborts installation.
The install script uses UN1CA copy/metadata helpers and checks every payload.
No ptrace process, software breakpoint, boot service, donor tuning, or S26U
camera binary is installed. Existing unrelated rezoss changes remain intact.

Installed files:
 /vendor/lib64/camera/components/com.qti.node.eisv3.so
 /vendor/lib64/libunica_horizon_adapter.so
 /vendor/etc/camera/unica_horizon_lock.conf
 /vendor/etc/camera/unica_horizon_lock/stock-eisv3.so

The node gains six direct-call trampolines: initialization, distortion,
stabilization, CHI perspective publication, and distortion/stabilization during
recording drain. The original algorithm calls and return values are retained.
The library keeps orientation by frame ID and matches delayed EIS output to its
CHI publication. Per-node state is bounded, old contexts are evicted, and a
sensor-delivery gap over500ms reacquires orientation for another recording.
This gap heuristic is not a verified recording-start vendor tag implementation.
Mode changes reset frame history, and publication rechecks the current mode.

Defaults for the fixed-crop ROM trial (2026-10-05):
 mode2 only; sensor0; one centered perspective matrix; 2400x1352 domain;
 USB-right landscape, starting near level;45-degree roll target;
 accelerometer low-pass with120ms time constant; fixed2x additional crop.
Shaking remains a known limitation. Fixed crop removes the adapter's per-frame
zoom adjustment on successfully corrected frames; skipped/uncorrected frames
and the stock camera transform can still produce visible transitions. This is not a full
180-degree/S26U-equivalent implementation. No front/portrait/other-sensor support
is claimed. Physical crop calibration and ICA grid/alignment composition still
need validation with the actual saved video.

Config defaults: enabled=1 and fixed_crop=1. This selects constant2x additional
crop above the stock camera crop, including while level. It has not been verified
in saved camera video. The numeric S26U crop has NOT been recovered;2x is our
provisional trial budget, not a donor value or a claim of matched field of view.
See S26U-CROP-COMPARISON.txt for the donor setup-time margin selection evidence.
Set fixed_crop=0 in the source payload config to restore adaptive crop; update
its SHA256SUMS entry before rebuilding. That variant has known zoom pumping.
Normal stabilization and ordinary Super Steady bypass the correction.
The ROM adapter handles drain output in the synthetic test; live drain behavior
must still be checked after flashing.

Checks completed:
 - Original executable bytes preserved except six4-byte calls; original symbol
   values/versions, relocation semantics, BTI/PAC notes retained; no RWX segment.
 - Patched node loaded on S23U in a standalone vendor namespace. ChiNodeEntry
   and all six trampolines passed argument/return compatibility tests.
 - Native synthetic tests: mode0/1 untouched, mode2 corrected130frames across
   two recordings with30-frame delay/drain and gap reset; current-mode bypass.
 - Build installer exercised with actual UN1CA helpers in /tmp: payload hashes,
   metadata, original backup, repeated application, unsupported-firmware rejection,
   other-device skip and disable marker.
These are not full ROM-build, flash, provider SELinux-domain, live-camera or
saved-video proof. A loader/ABI incompatibility can prevent Camera from opening.
No vendor library on the connected phone was replaced by these standalone tests.

Build the ROM normally:
 source buildenv.sh dm3q
 make_rom -f -z

After flashing, test regular Video and Super Steady first, then Horizontal Lock
in landscape with USB on the right. Start level and tilt gently15degrees each
way; inspect both the middle and last second of the saved clip. Native logs:
 /mnt/c/platform-tools/adb.exe logcat -d -s UnicaHorizon:I '*:S'
An init line enabled=1/mode=2 and writes counters establish adapter execution;
only saved video establishes leveling quality.
For this trial, init must also show fixed_crop=1 and correction logs zoom=2.0000.
Compare the initial crop with the middle of the saved clip; watch for crop jumps
at startup, during stationary holds, after returning level, and at recording end.

Runtime disable (root required), then close/reopen Camera:
 /mnt/c/platform-tools/adb.exe shell "su -c 'touch /data/vendor/camera/unica_horizon_lock.disable; chmod 644 /data/vendor/camera/unica_horizon_lock.disable; restorecon /data/vendor/camera/unica_horizon_lock.disable'"
Remove only that marker and reopen Camera to enable again. Missing config keeps
the adapter disabled. This marker cannot repair a linker failure; for a full
rollback, create horizontal-lock/disable in the source and rebuild/flash the ROM,
or flash the previously working ROM. The original node is also preserved inside
/vendor/etc/camera/unica_horizon_lock for an offline recovery/image rollback.

Rebuild just the native payload into a FRESH temporary directory:
 python3 -m venv /tmp/hl-rom-venv
 /tmp/hl-rom-venv/bin/pip install -r unica/mods/rezoss/horizontal-lock/rom/requirements.txt
 HORIZON_PYTHON=/tmp/hl-rom-venv/bin/python \
   bash unica/mods/rezoss/horizontal-lock/rom/build.sh /tmp/hl-rom-new \
   out/fw/SM-S918B_EUX/vendor/lib64/camera/components/com.qti.node.eisv3.so
 /tmp/hl-rom-venv/bin/python \
   unica/mods/rezoss/horizontal-lock/rom/run_device_tests.py --build-dir /tmp/hl-rom-new
 bash unica/mods/rezoss/horizontal-lock/rom/test_install.sh
Rebuilding does not overwrite the checked-in payload. A refreshed payload needs
its SHA256SUMS, manifest, and installer patched-node hash updated together.
