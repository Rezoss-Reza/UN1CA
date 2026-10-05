Experimental My FanCam ROM payloads for dm3q / SM8550
===================================================
customize.sh installs these local learned replacements only on dm3q:
Locator.dlc -> 07-21_Video_ReframingTargetLocator_UN1CA_Experimental_v3_SM8550-SNPE210.dlc
Reid.dlc -> 07-22_Video_ReframingReID_UN1CA_Experimental_v21_SM8550-SNPE210.dlc
Both use SNPE 2.10 with fresh SM8550/V73 caches.
ReID v21 repairs the unit-vector appearance contract used by Samsung's
dot-product matcher. All 16 regression/native decoder checks and 1,920 SNAP
executions passed. Gallery preview retained the middle dancer's processed
track through a full 15-second public clip, and export-completed callbacks
were captured. The user reported the latest test successful and explicitly
requested ROM integration. No acceptance thresholds were relaxed.
EXPERIMENTAL: this is single-clip evidence, not general FanCam compatibility.
One non-detected frame at 13.1 seconds remains in the processed track, and
the separate held-out pose-accuracy outlier remains. See
../reid/README-GALLERY-V21-20261005.txt and README-ASSOCIATION-20261005.txt
in the same reid folder for current evidence. README-SNPE210.txt describes
the earlier rejected ReID v11 experiment, not the active v21 payload.
The original S26U SM8850 donor prebuilts remain untouched for rollback.
Other target codenames retain the original donor installation behavior.

No XML filename edit is required: the native loader consumes Locator.dlc and
Reid.dlc, and no long-filename references were found in the donor/repo XML.
The existing target-tracking floating feature and permissions are unchanged.

Locator passed stock MCA/SNAP inference on seven inputs, 120 frames each,
and Samsung native decoding, including blank/horse negative samples.
Locator v3 corrects class scores from joint confidence to conditional person
probability. The native VideoDetection 0.95 threshold is unchanged.
In the live user test, Gallery analysis now returned two boxes in
all 25 quick-analysis frames and 1..5 boxes in all 110 full-analysis frames.
Earlier ReID v11/v19 trials lost the middle dancer at 3.5 seconds and expanded
the crop. The v21 normalization repair reproduces that failure with live
Gallery parameters and removes it in native replay and the latest Gallery
trial. Exported pixels/crop keyframes have not been independently inspected.
This is not a claim of complete My FanCam compatibility. ReID facial semantics remain
provisional; difficult crops can lack confident pose. See ../locator/README.txt
and ../reid/README.txt for model provenance, tests and limitations.
Review upstream model/dataset terms before public redistribution. YOLOv7 code
and OSNet license texts are included here; these do not replace that review.

Verify (from repository root):
bash -n unica/mods/rezoss/customize.sh
python3 unica/mods/rezoss/fancam/experimental/test_install.py
git diff --check

Tests exercise the actual UN1CA copy/delete helpers only inside /tmp, including
repeat installs, symlink targets, artifact hashes and file/SELinux metadata.
No out/fw, out/target or live-device files are modified by these tests.
