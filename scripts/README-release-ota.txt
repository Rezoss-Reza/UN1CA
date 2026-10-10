Block-delta OTA from two full dm3q recovery release ZIPs

    source buildenv.sh dm3q
    unica make_rom -ota

The shortcut records the newest existing full dm3q out/UN1CA_*.zip as its OTA
baseline, using embedded release timestamps, not filesystem modification times.
It then runs make_rom -f -r with a fresh ROM_BUILD_TIMESTAMP, ensuring a fresh
full ROM build even when the completed-build cache would otherwise be reused.
It generates the delta against the exact newly created full ZIP and creates
out/OTA_UN1CA_OneUI9_Rezoss_ddmmyy_hhmmss.zip using Europe/Paris time.
One existing full release is required. Ambiguous baseline timestamps or more
than one newly created release stop generation. Incremental ZIPs are excluded.
The shortcut must be used alone. Existing releases are retained; if delta
generation fails, the newly built full release is also retained.

To package two already existing releases without rebuilding:

    unica build_release_ota --auto

Explicit paths remain available:

    unica build_release_ota /path/old.zip /path/new.zip /path/update.zip

Inputs are full BLOCK release ZIPs produced by make_rom -r (or -z), with
version-4 transfer lists, new.dat or new.dat.br, Android metadata and dynamic
partition operations. The target ROM release timestamp must be newer. Output
must not already exist. No ROM rebuild or previous target-files archive is needed.

Configure TARGET_CACHE_PARTITION_SIZE in target/dm3q/config.sh first. The helper
uses the existing UN1CA img2sdat generator, source range verification, stash
space check, dynamic resizing and ZIP signing. Normal builds sign; debug builds
use the existing unsigned behavior. Kernel images are shipped whole.

Reconstruction preserves new/zero blocks as sparse care ranges and keeps erased
or unspecified blocks unknown. Original file block maps are absent from release
ZIPs: generated maps use the tool's fallback block groups, so compression may
be less efficient than deltas from original target-files archives.

Temporary reconstruction and packaging use an isolated /tmp directory, cleaned
on normal exit or errors. Allow substantial disk space for extracted data,
partition images and intermediate ZIPs. Input ZIPs and out/fw, out/target are
not modified. Publishing never overwrites an existing output.

The target release's updater binaries, bundled DFE files and installer ending
are retained. Support is currently limited to the standard dm3q installer.
Vendor is always shipped as a full payload because DFE rebuilds its blocks
on-device. Other partitions retain source verification and block deltas. Install
over the exact old release with recovery /cache mounted and enough free space.
Changes to other source partitions can still fail verification. The bundled DFE
maintainer runs after installation; its recovery capabilities remain required.

Validation performed: synthetic sparse reconstruction, complete unsigned and
signed packaging, offline bsdiff application with target-byte comparison,
bundled updater/DFE byte comparison, and written-block reconstruction from a
real release's Brotli vendor_dlkm payload. Real two-release generation and
recovery installation remain separate validation steps.

Before each logical partition write, the installer asserts unmap_partition
and map_partition to replace recovery-created read-only mappings with writable
updater mappings. This includes unchanged-size partitions and shrink writes.
Source verification still precedes writes. A failed remap aborts installation.

New release-ZIP OTAs include signed updater metadata, strict base identity and
a 5-second Volume Down cancellation gate. A .zip.ota.json signed catalog record
is generated alongside the ZIP as an optional catalog. The browser/local ZIP
flow requires uploading only the OTA ZIP to the public Drive folder. See
unica/mods/settings/ota/README.txt for key setup and bootstrap requirements.
