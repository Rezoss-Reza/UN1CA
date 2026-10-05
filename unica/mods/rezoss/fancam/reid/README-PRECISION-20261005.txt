My FanCam decoder/precision/appearance follow-up - 2026-10-05
==========================================================
Evidence: out/archived/UN1CA-FanCam-Tracking-20261005/
No ROM model payload, native library, alias/XML or customize.sh changed.
out/fw/ and out/target/ were not edited. No global inference runtime swap.

1. Valid width and padded stride
-------------------------------
FrameLayoutProbe.java decoded all 447 frames of original_source.mp4 using
c2.qti.avc.decoder. All 15 sampled Image records have dimensions/crop
720x1280, Y rowStride 768, chroma rowStride 768 and pixelStride 2.
The output format is width=720, height=1280, crop-right=719,
crop-bottom=1279, stride=768, slice-height=1280.
decoder-layout.txt preserves metadata only, no decoded media.

mca_decoder_layout_probe.cpp calls the hash-pinned Samsung MCA VideoDecoder
constructor, initDecoder, startDecoder, decodeOneFrame, getOutputFormat,
releaseDecodedBuffer and destructor in an isolated process. It deliberately
does not call worker-dependent run()/doDecode() with a null SharedContext.
MCA itself reports the same 720 valid width and 768 stride on this clip.
mca-decoder-layout.txt and decoder-native-disassembly.txt retain the evidence.
Pinned copyToAlignedBuffer uses distinct output-width and input-stride fields
and copies rows. No production width change is justified from the 768 header
alone. Gallery's caller/frame-serialization boundary is still unobserved.

A bounded process-scoped Frida attach, with Video Editor foregrounded and
full KernelSU root, returned 'agent connection closed unexpectedly'. Its
server PID was stopped and the dedicated ADB forward removed. Video Editor
remained running. No live tensors or crop keyframes were obtained; no SELinux
policy change, process restart or model mount was made for this attach.

2. Conversion versus quantization/runtime
----------------------------------------
compare_precision.py feeds the same 16 saved regression/native crops into
ONNX, float.dlc on stock SNPE CPU, quantized.dlc on CPU and cached DLC on DSP.
precision-v18/report.json:
  float CPU: 16/16 pose gates pass; max relative RMSE 0.00001828;
             all 174 row argmax locations identical to ONNX on every input.
  quantized CPU: 16/16 pose gates pass; max relative RMSE 0.077692.
  cached DSP: 14/16 pose gates fail; max relative RMSE 0.358893.
This demonstrates a DSP quantized-path error, not a float conversion failure.
Direct-loader probes use _Exit to avoid standalone vendor atexit crashes;
this comparison is not session cleanup or Gallery acceptance evidence.

inspect_precision_boundary.py exposes intermediate tensors and restores
actual runtime NHWC outputs to ONNX NCHW where required. The first debug
pose-head-boundary-middle report did not restore these two 4D layouts;
use pose-head-layout-corrected instead. Those apparent 4D errors were a
diagnostic layout mistake, not evidence of a model preprocessing defect.

pose-norm-boundary-middle identifies the earliest major error:
  pose/401 squared features: CPU RMSE 0.02943, DSP RMSE 0.03356.
  pose/402 ReduceSum:        CPU RMSE 0.00452, DSP RMSE 0.31539.
Downstream normalization and attention amplify this error. Later squared
attention also differs. This does not establish every failing DSP operator.

adapt_reid_contract.py replaces both last-axis RMS ReduceSum operators with
MatMul against constant ones. Shape/order and float semantics are preserved:
max float pose difference 0.000001371 on all sixteen original inputs.
No thresholds were reduced; no synthetic detection or pose fixture replaced
learned inference. Failed conversion attempts are retained separately.

IMPORTANT reference correction:
export_reid.native_pose previously discarded scores above 1 as zero.
The inspected native decode_rtmcc_image instead clamps them to 1, while
negative scores clamp to 0. The helper now uses clip(confidence, 0, 1).
Earlier reports interpreting over-one peaks as invalid native scores are
superseded by this correction. Three explicit diagnostic fixtures pass,
and all sixteen real candidate outputs match the native decoder afterward.
This changes our Python diagnostic, not Samsung's implementation.

3. Fitted 128D appearance contract
---------------------------------
fit_appearance_projection.py uses pinned OSNet x0.25 MSMT17 learned weights.
It fits uncentered PCA of normalized full 512D teacher features on 1,406
person crops from a separate public pedestrian clip (OpenCV vtest.avi).
The 128D orthonormal projection retains 99.4639% training angular energy.
This is data-fitted teacher compression, NOT an identity-supervised 128D
ReID head. It has not been calibrated to Samsung's temporal association.
The fitted matrix, ONNX and all source hashes are retained in
appearance-fitted128/. Your three-dancer clip never fits the matrix.

Independent public dancing clip, 55 evaluation crops:
  first128 teacher-neighbor agreement 50.91%, pairwise cosine RMSE 0.07472.
  fitted128 agreement 98.18%, pairwise cosine RMSE 0.01282.
Exact clip, 91 saved Samsung-preprocessed person crops:
  first128 agreement 59.34%, fitted128 agreement 94.51%.
  fixed-template horizontal-rank proxy: first128 19/87 mismatches,
    mean4x128 8/87, fitted128 5/87, full512 teacher 7/87.
These are proxy teacher/identity comparisons, not manually annotated ground
truth or Samsung tracking. Training/external crop letterboxing is a host
approximation; exact-clip tensors retain the actual native preprocessing.

Public upstream references:
https://github.com/opencv/opencv/blob/4.x/samples/data/vtest.avi
https://github.com/KaiyangZhou/deep-person-reid
https://developer.android.com/reference/android/media/Image.Plane#getRowStride()
The downloaded training media is temporary, not an install payload.

4. Rebuilt candidates and rollout gates
---------------------------------------
v19c: fitted128 plus exact norm reduction, SNPE 2.10 and fresh SM8550/V73 cache.
Candidate SHA-256:
993fe6023f5d8e5787632f2c2f3f4b57af427ce8fe8ec8fe0cb60d0da2d0f2f8
  sixteen regression inputs: appearance/pose gates all pass, three executions
    per input, normal SNAP dispose/destructor.
  native decoder/cosine ABI/body-coordinate checks pass on all sixteen;
    five brightness identity pairs remain separated.
  longevity bundle: 120 executions per input, 1,920 total, reference gates
    pass and normal session cleanup. This is not an app longevity test.
  exact clip: 91 crops, six calibration crops excluded -> 85 held out.
    appearance passes 85/85, minimum cosine 0.983016.
    pose passes 84/85; frame 390 / 13.0 sec / left rank 0 fails RMSE 0.399673.
    sampled DSP fixed-template proxy has 6/87 mismatches, not track IDs.
The full holdout gate FAILS. Do not integrate as a validated ROM replacement.

v20: same graph with 32 extra calibration crops from independent vtest.avi,
without adding the failing dancer frame. Candidate SHA-256:
2e85397448d271ebc8d0c740b14e7ac15d2762a9b0d735b1a5819905937a4cd3
  regression bus-1-brightness pose fails RMSE 0.324975.
  held-out appearance passes 85/85, pose passes 84/85; same left dancer at
    13.0 sec worsens to RMSE 0.504418. Rejected; not installed.
v19c remains the better isolated experiment, not a completed FanCam repair.

5. Remaining work
-----------------
The requested production identity-continuity, final crop rectangles,
preview and export validation has NOT passed. No replacement was mounted.
A temporary experimental ReID test was requested separately, with its
remaining pose failure disclosed; do not activate without user approval.
Final crop keyframes remain unavailable through the failed instrumentation.
Keep investigating the outlier and remaining pose-head saturation/attention,
then require Gallery identity continuity before changing crop policy or
installing a ROM replacement. Existing installed ReID/Locator/SNPE hashes
were rechecked and remain unchanged.

Source backups: source-backup-next/export_reid.py and verify_native.py.
Diagnostics are not install-time hooks.
Verification:
  python3 -m py_compile unica/mods/rezoss/fancam/reid/*.py
  <ML-venv-python> unica/mods/rezoss/fancam/reid/test_pose_reference.py
  python3 unica/mods/rezoss/fancam/experimental/test_tracking_records.py
  python3 unica/mods/rezoss/fancam/experimental/test_install.py
  bash -n unica/mods/rezoss/customize.sh
  git diff --check
