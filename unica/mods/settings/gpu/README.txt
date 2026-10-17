Advanced GPU Tuning (Konabess) - dm3q trial
========================================

Entry: UN1CA Settings, immediately below Battery Saving.
This is a ROM-native implementation inspired by libxzr/KonaBess, not a bundled
KonaBess APK. It does not require Magisk, KernelSU or an external root manager.
It edits the existing ten GPU frequency/RPMh pairs and preserves all other
device-tree properties. It does not add/remove power levels or modify ACD/bus
configuration. No tuning or partition writing runs automatically during boot.

Compatibility gate
------------------
dm3q, vendor_boot header v4 / SRPVH05C010, 96 MiB partition, source DTB set
SHA256 99066fde3eede3bd5e17ba0a07182ebf06fda2fbf775003890c938af4e8fe23b.
Only the ten frequency/level properties in Kalama v2 table0 may differ from
that known DTB set. The complete driver module must match SHA256
16cb910e20983d9ff90c8eb11eb075310af50411d45853e7925282ac240f3ecc.
Studied vendor firmware: S918BXXUAZZI8; driver and DTBs also matched the
connected phone. These are exact content gates, not an Android-version claim.

The worker checks the actual top-level vbmeta header for verification-disabled
bit 2. It never changes top-level vbmeta. Spoofable Android locked/green
properties are not used as evidence. Fixed dm3q mappings are checked:
vendor_boot -> /dev/block/sda28; vbmeta -> /dev/block/sde20.
An unfamiliar firmware, layout, driver or partition mapping refuses writes.
After a firmware update, compatibility must be reviewed before extending gates.

Image editing
-------------
The existing fixed-width u32 DTB values are changed in a copy of the CURRENT
image. The ramdisk, header, other DTBs, SKU selectors, ACD/bus properties and
partition size remain intact. The now-invalid embedded Samsung AVB signature
is replaced with an unsigned, SHA256-verified hash footer using the existing
salt and rollback index. This requires verification to already be disabled.
AOSP avbtool checks the produced footer/hash in host tests. This is not a
Samsung signature and is not intended for a locked/verified stock device.

Input limits are 124.8-850 MHz, in 0.1 MHz steps, descending frequencies, and
non-increasing RPMh requests drawn from the stock set of known level numbers.
These are validation limits, NOT safe overclock/undervolt recommendations.
The driver can round corner requests up. RPMh level numbers are not millivolts.
Voltage stability, clock synthesis, GMU/ACD interaction and power savings must
be measured on the actual silicon. Unsuitable values can prevent booting.

First trial
-----------
1. Install a ROM build containing this integration. Confirm OrangeFox recovery
   is accessible and keep a matching full ROM available.
2. Open the page and create/verify the original backup. About 320 MiB free on
   /cache is required. The original image is never replaced by a tuned image.
3. Save rollback ZIP to storage accessible from recovery, preferably PC/USB.
   The page reopens the saved file and verifies its complete SHA256 before
   enabling Apply. Retain it independently of encrypted Android user storage.
4. Make one small change, review the exact changes and confirm Apply. Wait for
   partition readback verification. Nothing reboots automatically.
5. Reboot manually only after success. Test stability before further changes.

Rollback
--------
While Android works: Restore original vendor_boot, wait for verification, then
reboot. Stale images from an unrelated update are rejected. A write failure
attempts immediate restoration of the original. If both writes fail, the
durable transaction journal/candidate permit an explicit restore retry; Apply
and the page's Reboot button remain disabled while a transaction is incomplete.

If Android cannot start: boot OrangeFox, mount Cache, and flash
/cache/unica-gpu/rollback.zip. Alternatively flash the exported rollback ZIP.
Do NOT wipe Cache before using its copy. The ZIP contains the full original
image, SHA256, a static ARM64 restore helper and an update-binary script. It
checks image compatibility, checksum, partition size/mapping and the original
image header before writing. It reads back the entire partition and verifies
the result. It never reboots automatically. If the restore fails, stay in
recovery and restore the matching firmware/ROM manually.

No boot-success watchdog is claimed: Android cannot automatically restore an
image if the modified GPU configuration prevents the kernel/Android from
starting. Recovery is the independent rollback path. No software can guarantee
recovery from every power loss, failed storage device or unavailable recovery.
Do not reuse this rollback ZIP after changing firmware; it contains the complete
pre-trial vendor_boot image, including that build's ramdisk/DFE modifications.

Protected files
---------------
/data/misc/unica_gpu/requests/request  - bounded Settings request
/data/misc/unica_gpu/responses/response.json
/cache/unica-gpu/original.img         - immutable original backup
/cache/unica-gpu/original.sha256
/cache/unica-gpu/rollback.zip          - recovery-flashable independent backup
/cache/unica-gpu/rollback.sha256
/cache/unica-gpu/exported              - verified export acknowledgement
/cache/unica-gpu/applied.sha256        - last transaction target
/cache/unica-gpu/writing               - durable in-progress journal
/cache/unica-gpu/candidate.img         - verified staged transaction image

Only the root init worker writes the backup/partition. Settings writes requests
and reads responses/rollback ZIP. It has no new block write permission. The
worker has specific vendor_boot read/write and vbmeta read-only labels; legacy
block consumers retain their previous permissions when these two nodes are
relabelled. No shell, Binder, networking or Linux capabilities are granted.
Other targets remove the GPU native payload/service and receive no new policy.

Build and verification (from repository root)
--------------------------------------------
unica/mods/settings/gpu/build.sh
unica/mods/settings/gpu/tests/run.sh

The build needs the existing Android SDK/NDK, javac, D8, baksmali and smali.
Tests use a source vendor_boot image, regular temporary files and a compatible
SELinux compiler. Set SECILC=/path/to/secilc if it is not on PATH. The tests
copy current out/target policies into /tmp; they never modify generated output.
Optional first argument to tests/run.sh selects another temporary image copy.
All testing artifacts are retained under /tmp/unica-gpu-*.

gpu/build.sh emits an additive SecSettings smali patch, the static native worker
and the static recovery helper. The Settings mod's standard overlay, metadata
and customize.sh policy installer include them in the next ROM build. Existing
customize.sh, overlays and metadata were backed up before changing them.

Runtime UI/SELinux/OrangeFox operation and a real modified-image boot remain
unverified until the updated ROM is installed and a controlled trial is run.
No full ROM build, flash, reboot or GPU mutation was performed during development.
Offline validation on 2026-10-17 passed image/AVB checks against both the stock
image and the exact image copied from the phone; 49 transaction assertions
covered missing/exported backups, replay, stale images, corrupted originals,
failed writes, failed immediate rollback and manual restoration. The generated
ZIP passed CRC/content/SHA256 checks. The combined policy compiled using init's
runtime -N mode, and its installer was idempotent. The additive patch applied
with --fuzz=0; Java/D8, combined Settings DEX assembly, static ARM64 payloads and
a complete temporary SecSettings APK rebuild/ZIP integrity check passed using
out/tools/bin/apktool and the S918BXXUAZZI8-tagged framework cache. Unrelated
existing non-positional resource-format warnings remain unchanged.

Attribution
-----------
Concept and platform investigation: https://github.com/libxzr/KonaBess
Upstream studied: ff387dc7f630d7460c7a6561f83f71846217ee27.
This worker implements fixed binary edits independently; it does not copy the
upstream app's shell commands or Java table editor. SHA256 uses the existing
AOSP libavb implementation under external/android-tools/vendor/avb/libavb.
Its copyright/license are reproduced in NOTICE.txt alongside this source.
