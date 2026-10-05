Exact-clip My FanCam tracking investigation - 2026-10-05
=======================================================
Input: out/archived/original_source.mp4, 720x1280, 30 fps, 14.953651 seconds.
Evidence, source backups and rejected candidates:
out/archived/UN1CA-FanCam-Tracking-20261005/

Installed models/libraries were hash-verified:
Locator v3 b43091ef9885cc7b3c136a69ce702d0afcc49d7fd5e5a9c748d2a389ed0e7ea6
ReID v2 f9ce6a502fa9ad1b33b5d20bfdd33159c9040613a5f737cc1f18ee8313788c2b
auto-reframing e8921a043f2050d69d11f906820a400054c835bebd0f743bff7a33405c42f835
MCA bedfc253a4c577d6917f2a79412c1c98d7c43cf5b2101406de9e970e526fabd7
SNPE 835a653e15ebda246a9d646b3176b485563518da4b72b9ef5de270ae790228e2
No installed models, native libraries, ROM payloads or customize.sh changed.
No mount, instrumentation server or SELinux change was made in this run.

1. Actual Gallery tracking evidence
----------------------------------
The user reopened this exact clip and selected the middle dancer. Read-only
capture of the latest analysis pair matched its duration and dimensions.
Media-content hashes are not present in those records; correspondence is
based on the user-triggered run, geometry/time and matching box overlays.
No private media titles or full logs are persisted by the capture utility.
150 sampled Gallery frames span 0..14.866666 seconds.

At the beginning, left/middle/right dancers have IDs 1/2/0 respectively.
Middle ID 2's postprocessed record ends at 3.4 seconds (35 entries; 32 raw
detected entries). At 3.5 seconds, middle and right persons are detected but
untracked (ID -1). Middle later receives ID 3; right receives ID 4.
ID 3 briefly appears on the right before following the middle person, so
fragmentation and association ambiguity are both present.
Left ID 1's postprocessed record covers the entire clip.
The selected-middle ID is inferred from user selection and initial position;
the selection API/log marker was not captured. Do not label it API-confirmed.

summary/gallery-track-overlay.jpg overlays actual boxes/IDs on source frames.
The raw boxes are person-sized, not a single group-sized detection. Arms and
adjacent dancers can overlap an ordinary rectangular person bounding box.
The large UI selection region is NOT proven to be the raw Locator box.
Shrinking every detector box is not an evidence-backed repair.

REFRAME_BY_ID/BOX_INFO contains subject-track boxes, NOT final editor crop
rectangles. Final crop margins/zoom keyframes were not captured. This is a
partial completion of the requested box/ID/crop comparison, not crop proof.

2. Native preprocessing and coordinate restoration
-------------------------------------------------
447 source frames decoded; 30 timestamps sampled at 0.5-second intervals.
29 timestamps have three host Locator detections; one has four. There are
91 native person-crop inputs tested through installed stock MCA/SNAP ReID.
Locator inference here is host ONNX on Samsung-preprocessed tensors; actual
production detections/IDs are separately captured from Gallery.

preprocess_probe.cpp now optionally calls the pinned crop-letterbox ABI.
Disassembly of PoseEstimatorRTMCC::estimate shows an expansion of source
width/height /64 before calling preprocess_crop_letterbox_flag(...,1).
That crop entrypoint applies another width/height /64 before rounding and
clamping the ROI. The probe follows both steps, without patching the library.
Returned scale, integer padding and xyxy bounds are retained alongside inputs.

Native PoseRTMCC restores coordinates as:
(decoded_coordinate - padding) * scale + crop_origin
The extended native_reid_probe accepts the exact 32-byte PreprocessInfo.
All 91 crop coordinate comparisons match the native decoder within 0.001 px.
No coordinate-restoration defect is demonstrated by this isolated RGB test.

IMPORTANT: Gallery records width 768 while the original video is 720 wide,
both 1280 high. This may represent decoder stride/padding, but actual YUV
conversion and valid-width propagation have NOT been captured. The isolated
probe uses true source width 720, so it does not prove Gallery's frame-layout
contract is correct. Do not patch width or claim a stride fix from this alone.

3. Accuracy and improvement trials
---------------------------------
Baseline installed ReID: all 91 exact native crops fail the appearance cosine
gate >=0.98 against ONNX. Minimum cosine 0.773945. Pose RMSE gate <=0.20 fails
on 87/91 crops. The earlier ten-public-crop appearance success did not cover
this input distribution. Host/native coordinate equality is not pose accuracy.

v17: same trained/adapted ONNX, SNPE 2.10, fresh V73 cache. Calibration retains
the original ten public crops and adds six native crops at 0 and 2 seconds.
These six crops are excluded from clip holdout statistics. No fitting of
identity weights or thresholds on this clip is performed.
Appearance gate failures decrease to 1/85 held-out crops; minimum 0.977011.
Pose still fails on 82/85 holdout crops and 14/16 combined regression inputs.
The original ten-input suite retains its original acceptance thresholds.

v18: original trained ONNX with exact HardSigmoid lowering, constant division
lowering, cloned shared conv parameters and rank-four pose concatenation;
dynamic division retained with mixed 8-bit encodings instead of reciprocal
power. Conversion, quantization, V73 preparation and installed SNAP inference
pass, but accuracy does not: 1/85 appearance and 82/85 pose failures remain.
Both trials are REJECTED for rollout. Neither replaces the installed ReID.

Appearance diagnostic: first 128 OSNet coordinates give 19/87 first-template
identity mismatches on the 29 three-person timestamps. A deterministic mean
of four 128-coordinate groups gives 8/87; the full learned 512D feature gives
7/87. These are host-only proxy comparisons, not Samsung's temporal tracker.
Horizontal reference labels assume no dancer order crossing; they are not
ground-truth annotations. Mean reduction is a promising experiment, not a
trained/validated 128D embedding. No reduction change was shipped.

4. Crop policy
--------------
Native-policy disassembly includes OperatorBox target/merge/fill/smoothing
and tracker matching/expiry functions. get_target_reframe calls target-data
selection, merged-box generation and fill_boxes. A constant 300000 is divided
by its supplied long parameter before merging; units/configuration are not
yet established. fill_boxes can repeat the preceding box across gaps.
These findings do NOT establish the final editor zoom-out policy.

The requested condition, "track stays correct but crop expands," is not met:
the selected-middle track fragments. Tracking accuracy is the first proven
failure to address before editing crop margins, smoothing or lost-target
policy. A full-frame fallback is plausible but not confirmed by captured
final crop rectangles. No speculative native crop-policy patch was made.

Verification and cleanup
-----------------------
Native preprocessing/ReID probes compile with -Wall -Wextra -Werror.
Parser regression fixtures test signed 16-bit IDs (reserved bytes are -1),
pose-vector lengths and truncation; they are NOT synthetic model acceptance.
Rebuild helpers, probes and diagnostic scripts are not install-time hooks.
Only isolated /data/local/tmp models were used, with normal SNAP dispose/
destructor per process. _Exit still bypasses standalone vendor atexit, as
documented previously. No longevity or Camera/Gallery candidate test passed.

python3 unica/mods/rezoss/fancam/experimental/test_tracking_records.py
python3 unica/mods/rezoss/fancam/experimental/test_install.py
bash -n unica/mods/rezoss/customize.sh
git diff --check

Next unresolved work: reliable pose quantization and a calibrated 128D
appearance embedding, then a production temporal-identity test, plus actual
frame-valid-width and final editor crop/keyframe capture. Do not call this
investigation a completed tracking/cropping repair.
