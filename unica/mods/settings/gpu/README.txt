Advanced GPU Tuning (Konabess) - dm3q
===================================

Entry: UN1CA Settings, below Battery Saving in the power settings group.
ROM-native Settings page and on-demand static root worker. No Magisk/KernelSU
root manager, shell commands, networking or automatic boot-time tuning.
Concept: libxzr/KonaBess. This is an independent implementation, not its APK.
The page keeps One UI 9 grouped cards, theme colors and UN1CA transparency.

Exact compatibility
-------------------
Target inspected: S23 Ultra / dm3q, vendor firmware S918BXXUAZZI8, Adreno740v2.
Vendor_boot: header v4, board SRPVH05C010, page size 4096, 96 MiB partition.
Normalized entire DTB-set SHA256:
99066fde3eede3bd5e17ba0a07182ebf06fda2fbf775003890c938af4e8fe23b.
msm_kgsl.ko SHA256:
16cb910e20983d9ff90c8eb11eb075310af50411d45853e7925282ac240f3ecc.
Only the inspected GPU-bin subtree can differ; SKU selection and all other DTB
content remain pinned. Actual driver and fixed partition mappings are checked:
vendor_boot -> /dev/block/sda28; vbmeta -> /dev/block/sde20.
The worker requires top-level raw vbmeta verification-disabled bit 2 already
set; it never changes that partition. Unknown firmware/layout/driver refuses
writes. Profiles remain available when the current firmware is unsupported.
See compatibility.txt for exact shipped-driver findings and remaining unknowns.

Editor capabilities
-------------------
Table selector: table 0 (SKU-specific), table 1 (zero-SKU fallback). The driver
selects the active table; the selector only chooses which saved draft to edit.
Both tables are reviewed and written together on explicit Apply.

Power levels: add/remove contiguous entries, with 2..15 rows per table and
initial/minimum indices inside the table. Removal renumbers rows and adjusts
references. Both tables must fit the original 2988-byte FDT subtree; additions
require available space. Added defaults are draft values, not recommendations.
The GMU has 16 DCVS slots including its separate off state, hence 15 usable rows.
The driver can clamp initial minimum below initial/default to the final row.

Frequency: full positive u32 Hz, entered as MHz with up to six decimal places.
No forced 0.1 MHz rounding. Frequencies strictly descend. The u32 bound is a
storage limit, not a supported clock range. Gen7 converts Hz to kHz by integer
division, and actual clock synthesis/support still needs runtime testing.

Voltage request: named KonaBess Kalama levels plus raw 1, and custom integers
1..480. This includes 96,72,48,16,1. Requests outside the stock set
52,56,60,64,80,128,144,192,224 appear in RED with warnings in selectors, preference
rows and import/Apply reviews. Requests need not be monotonic to be represented.
The driver looks up the first command-DB GFX level >= the requested value;
multiple requests can map to the same corner, and values above its available
range fail table construction. They are NOT millivolts. Raw 1 is experimental,
not a named standard corner. Neither red nor stock values guarantee stability
at a selected frequency. The bound 480 is an editor guard, not hardware proof.

Bus/ACD: raw u32 bus requests must satisfy min <= frequency <= max. This firmware
has ten DDR bus entries (0..9). The exact Samsung driver clamps out-of-range bus
requests to 9 on every row except the final row. Requests such as 15/16 are kept
and shown in red with that explanation; the final row must use valid 0..9 values
because it bypasses the clamp. Optional ACD is a raw u32 bitfield
(decimal or 0x hex, or none). Modified bus/ACD values are highlighted. These can
freeze the GPU or prevent booting. No arbitrary properties or SKU changes.

Profiles
--------
Back up profile exports a shareable UN1CA JSON v2 file and reads it back to
verify it. Import profile accepts UN1CA JSON or original konabess:// exports.
The upstream format is Base64 + gzip + UTF-8 JSON, not encryption. Import is a
bounded typed parser: 32 KiB input/expanded data, bounded JSON/tree depth and
node counts, known Kalama chip, exact Samsung SKU selectors, exactly two bins,
valid indexed rows and known header/property names. No imported commands,
paths, DTS includes, shell/dtc execution or automatic flashing are supported.
A separate OPP/microvolt-table export is refused for this target.

Import shows both complete tables and red warnings before Load profile. Loading
only saves the draft. Apply independently checks compatibility, backup/export,
current-image hash and all native bounds before writing anything.
Original XDA export OC930_UV_all actually stores 730 MHz; the description is not
proof of the stored values. It passes without substituting its 96/72 requests.
The first XDA export is also supported intact, including its raw bus request 15
(the driver clamps it; the editor displays a warning). The earlier generic
konabess-0221050129.txt still fails: its final table-0 row requests bus maximum
16, and that row bypasses the driver clamp. No requests are silently replaced.
Missing initial-min-pwrlevel in an upstream header becomes the final row index,
matching the kernel's default when that property is absent.

Existing UN1CA v1 profiles migrate to v2. They supply only table 0; table 1 is
initialized from the pinned stock defaults. Review both tables before Apply,
especially if table 1 was already tuned. New exports retain both complete bins.

Edits save automatically to device-protected SharedPreferences
unica_gpu_profile in Settings. They persist across closing the page and ROM
updates that preserve Settings data. Wipes/clearing Settings data can remove
them, so export a profile copy. No profile is applied at boot or during an OTA.

ROM updates and rollback
------------------------
1. Open the page after updating. Saved draft values stay available.
2. If vendor_boot changed, Create backup for current ROM archives the old clean
   image/ZIP in protected /data/misc/unica_gpu/archives storage. Both complete
   files and their checksums are copied, synced and read back before removing
   any old cache copy. Existing cache archives are migrated the same way. A
   durable renewal journal blocks Apply/Restore while cache space is reclaimed,
   the current clean image and fresh ZIP are staged and verified, the live hash
   is rechecked and the fresh files are activated. It never flashes the older
   image to enable Apply.
3. Save the NEW rollback ZIP to recovery-accessible storage, preferably USB/PC.
   The page reads it back and verifies its complete SHA256. Old export approval
   is cleared. Apply remains blocked until the fresh export is verified.
4. Review the saved/imported values, confirm Apply and wait for complete image
   readback verification. Reboot manually only after success.

Backup refresh requires intact old backup files, a compatible completely stock
GPU subtree in the new image and no pending partition write. Tuned/unknown
subtrees are never silently recaptured as clean baselines. Interrupted refresh
can resume only against the same new image. Apply/Restore stay blocked until
it completes. Archives are retained in protected /data storage; retain matching
firmware and export the NEW rollback ZIP to PC/USB before Apply. Archive moves
resume after interruption, including partial copies and partial cache cleanup.
Insufficient /data space or any checksum mismatch preserves the cache originals.
The cache-space check accounts for remaining backup writes, a full GPU candidate
and a margin (330 MiB for an entirely fresh set). A complete matching backup is
verified without rewriting it or requiring fresh staging space. Unknown cache
files are never deleted. Do not wipe /cache during an interrupted operation.

If another ROM changes vendor_boot while an old backup-only refresh is pending,
Restart backup for current ROM becomes available only for a compatible stock
image with no partition-write journal. Confirming it verifies the old rollback,
snapshots the obsolete journal and all managed active/staged/cache-archive files
into /data/misc/unica_gpu/archives/refreshes/<journal-hash>-<current-hash>/,
and verifies a SHA256 receipt before any cache cleanup. A separate durable
restarting journal and cleanup receipt make deletion/staging/activation resumable.
Unknown root files are preserved. Apply/Restore remain blocked throughout; the
new rollback ZIP must be exported again. No image is flashed by this operation.
A further image change during the confirmed restart remains blocked for review.
Obsolete-refresh validation passed 392 assertions covering seven interruption
points, a full data partition, corrupted preserved files, incorrect confirmation
hashes, partition-write exclusion, zero-space activation retries and returning
to the original ROM. The existing 415 transaction assertions also passed. These
host fault suites used the temporary OpenSSL SHA adapter; a separate production
AVB SHA run verified the preserved 13-file receipt, old rollback and fresh
baseline. Independent hashlib/ZIP CRC checks verified both new-ROM and original-
ROM cases. No live partition writes or automatic restart is part of this path.

Local *.bak and *.bak.* files/directories stay in source checkouts. Module copy
and installer ZIP writers exclude them. Filesystem image builders use a filtered
temporary copy if an old work tree still contains backups, leaving the original
work tree untouched. Installer staging backups are quarantined before checksum
and signed OTA entry lists are generated. See scripts/tests/rom_payload_test.py.

While Android works: Restore original vendor_boot, wait for verification, then
reboot manually. Stale unrelated images are rejected. Failed writes attempt
immediate original restoration; if that also fails, the journal and candidate
remain for an explicit retry. The page blocks Apply/Reboot during a pending
partition write. No automatic boot-success watchdog is claimed.

If Android cannot start: boot OrangeFox, mount Cache and flash
/cache/unica-gpu/rollback.zip, or flash the saved matching rollback ZIP from
USB/PC storage. Do not wipe Cache first. The ZIP contains the full clean image,
checksum, static ARM64 helper and update-binary. It verifies the image, mapping,
partition size/header, then writes and reads back the entire partition. It
never reboots automatically. Stay in recovery if restoration fails and restore
matching firmware manually. Never use an archived old ZIP on a different ROM.

Image handling
--------------
The worker parses/renders only the known GPU-bin subtree. Removing entries uses
standard FDT_NOP padding, so DTB/partition size, ramdisk, header, unrelated nodes
and other DTBs remain intact. The known stock subtree template and normalized
whole-DTB-set hash protect everything outside the editable region. Initial/SKU
header shapes are checked, and SKU values are immutable. Both bins must fit the
original space; the worker does not grow/repack the DTB or change the ramdisk.

The invalidated embedded Samsung AVB signature is replaced by an unsigned,
SHA256-verified footer preserving the existing salt and rollback index. AOSP
avbtool verifies the result offline. This requires verification already disabled
and is not a Samsung signature or a locked-stock-device feature.

Protected files and permissions
-------------------------------
/data/misc/unica_gpu/requests/request       bounded Settings IPC
/data/misc/unica_gpu/responses/response.json
/cache/unica-gpu/original.img              active clean current-trial baseline
/cache/unica-gpu/original.sha256
/cache/unica-gpu/rollback.zip
/cache/unica-gpu/rollback.sha256
/cache/unica-gpu/exported                  verified export acknowledgement
/cache/unica-gpu/applied.sha256            last transaction target
/cache/unica-gpu/writing                   durable partition-write journal
/cache/unica-gpu/candidate.img
/cache/unica-gpu/renewing                  durable backup refresh journal
/cache/unica-gpu/pending/<new-hash>/
/data/misc/unica_gpu/archives/<old-hash>-<zip-hash>/
/cache/unica-gpu/archives/                legacy copies, moved only after verification

Only the root init worker writes backups/partitions. Settings only exchanges
bounded IPC and reads the rollback ZIP. Dedicated SELinux labels grant specific
vendor_boot read/write, vbmeta read-only and backup-file operations. Existing
block consumers retain their previous permissions. No new shell, networking,
Binder or capabilities; this extension requires no extra SELinux permissions.
Other targets remove the GPU native payload/service and receive no new policy.

Build and verification (repository root)
----------------------------------------
source buildenv.sh dm3q
unica/mods/settings/gpu/build.sh
JSON_JAR=/path/to/host-json.jar SECILC=/path/to/compatible-secilc \
    unica/mods/settings/gpu/tests/run.sh

Optional first test argument selects an exact original vendor_boot copy.
The existing SDK/NDK, javac, D8, baksmali and smali build an additive GPU-only
SecSettings patch and static ARM64 worker/recovery helper. The Settings mod
includes these in the next ROM build. No full ROM build is needed to validate
them. Policy tests copy generated policy into /tmp and compile like init (-N).
Tests retain artifacts under /tmp/unica-gpu-* and never write real partitions.

Host JSON test dependency (Android-derived implementation):
https://repo.maven.apache.org/maven2/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar
SHA256 dfb7bae2f404cfe0b72b4d23944698cb716b7665171812a0a4d0f5926c0fac79.
Android supplies org.json at runtime; this JAR is not shipped in Settings.
The profile tests also run against org.json 20240303. They cover old-profile
migration, exact upstream round-trip, compression bombs, nested/unknown input,
u32 overflow, experimental requests, changed bins and FDT-space constraints.
Native tests cover two-bin edits, structural padding, all requested voltage
values, image/AVB preservation, IPC, export gates, failed writes, manual recovery
and interrupted archive/refresh transactions. Storage tests additionally cover
partial /data copies, partial cache cleanup, full /data or /cache, corrupted
archives, legacy archive migration, unrelated-file preservation and symlink
rejection. Fresh-backup activation resumes with zero simulated cache space
after staging, partial activation or complete activation; 30 targeted checks
passed using production AVB SHA. The full storage regression suite passed
415 assertions with a host-only OpenSSL SHA adapter; generated image and ZIP
digests were independently checked using Python hashlib. Four symlink guards
and unrelated-file preservation also passed. The shipped ARM64 binaries use
production AVB SHA, not this temporary host adapter. Earlier editor validation:
4059 image,
226 transaction and 85 profile assertions passed, alongside AVB, independent
dtc parsing, zero-fuzz patch application, full temporary SecSettings APK rebuild
and combined SELinux checks. Both XDA exports passed the actual Java-to-native
IPC pipeline with all fields preserved and original-image restoration verified
using regular temporary files. No full ROM build, flash, reboot or runtime GPU
mutation was performed. UI rendering and a device trial remain pending.

Attribution
-----------
https://github.com/libxzr/KonaBess
Upstream inspected: ff387dc7f630d7460c7a6561f83f71846217ee27.
The bounded native editor and upstream-profile parser are independently written.
AOSP libavb supplies SHA256; its copyright/license is in NOTICE.txt.
