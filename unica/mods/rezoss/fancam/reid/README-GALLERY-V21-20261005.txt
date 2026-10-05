Experimental ReID v21 Gallery preview trial - 2026-10-05
=======================================================
Evidence: out/archived/UN1CA-FanCam-Tracking-20261005/gallery-v21-test/
Candidate SHA-256:
64de5d1f753c143aa02cf97799b739097f64c152b41ba3a3c3c06d9b7522fb06

The user explicitly requested the temporary Gallery test. Only the ReID
DLC was bind-mounted in PID 1's namespace. During that temporary test,
original system bytes, vendor libraries and ROM source were not overwritten.
Host/device original model backups and the explicit rollback state exist.
Video Editor alone was stopped for a new process. PID 14636 and PID 1 both
see the exact v21 hash, captured in tracks-after-preview.json.

The deliberate public copy Movies/UN1CA_FanCam_v21_Test.mp4 matches the
original_source.mp4 SHA-256:
d6a31c1335486b8f8294b55ef0d06b99b37cb898ae02795d55f099c6c559623b
Initial 3-second regions were not used as full-clip acceptance evidence.
The user extended the range to 0..14954000 us, selected the middle dancer
and reported that preview focus stays on that dancer. The process selection
marker confirms ID 2 at 19:28:24.801 local device log time.

The full-range public result has 150 analyzed frames. Gallery feature norms
are 0.97319..1.02838, unlike the earlier unnormalized v19 vectors. Only
vector hashes, norms, sign counts, boxes and IDs were saved, not embeddings.
The public result basenames are explicitly restricted during capture.

Preview tracking evidence
-------------------------
Raw spatial-rank proxy: left ID 1 in 150/150, right ID 0 in 150/150,
middle ID 2 in 149/150. At 13.1 sec the middle raw ID is -7, not a new
positive identity. All three processed subject tracks have 150 entries,
from 0 to 14866666 us. ID 2 has 149 detected entries and one non-detected
entry, with no fragmentation into the earlier IDs 3/4.
The first processed ID 2 box is [306,505,443,882]; the last is
[216,416,481,1193]. These are subject boxes, NOT final crop rectangles.
This confirms the earlier 3.0..4.8-second association failure is repaired
on this public clip, with user-observed sustained preview focus.

Remaining gates and state
-------------------------
Appearance normalization does not fix the separate pose-accuracy outlier
in the held-out 13-second left-dancer crop. Other clips, all-person visual
identity continuity, final native crop keyframes and export quality remain
separate validation gates. This remains an experimental ROM replacement.
After the user reported the latest trial successful, scoped editor logs
recorded eight onExportCompleted callbacks and no onExportFailed or
onEncodeError markers in the captured evidence. These are callbacks, not
proof of eight distinct exports; exported pixels were not independently
inspected. See gallery-v21-test/export-evidence.json.

ROM source integration - explicitly requested on 2026-10-05
----------------------------------------------------------
The exact tested candidate was installed into the source payload as:
07-22_Video_ReframingReID_UN1CA_Experimental_v21_SM8550-SNPE210.dlc
Reid.dlc points to it; customize.sh installs v21 and removes obsolete v2.
Locator v3, Detection, XML and vendor inference libraries are unchanged.
Experimental warnings and remaining accuracy limitations are preserved.
Source payloads, customize.sh and affected diagnostic documentation were
backed up under:
out/archived/UN1CA-FanCam-Tracking-20261005/rom-source-backup-v21-20261005/
Shell syntax and repeated-install tests passed for dm3q and other devices.

The temporary device mount was restored to the original model, SHA-256:
f9ce6a502fa9ad1b33b5d20bfdd33159c9040613a5f737cc1f18ee8313788c2b
Only Video Editor was stopped for rollback. Permanent v21 installation
requires rebuilding and flashing the ROM; neither was performed here.
