My FanCam detection contract repair - 2026-10-04
==============================================
The previous exported Locator multiplied objectness by class probability.
That matched upstream YOLO's combined confidence, but Samsung's decoder and
VideoDetection treat the fields separately. VideoDetection::process removes
scores below 0.95 before pose/ReID. Public dancing frames had joint scores
0.78..0.92 and conditional person probabilities above 0.999.

Repair: export conditional person probability in class field 7; retain
objectness in field 4 and every runtime threshold. Do not boost confidence,
lower Samsung's threshold, or fabricate detections. The native library is
unchanged. Stable host NMS now preserves candidate order on equal scores,
matching Samsung's stable list sort; coordinate tolerances are unchanged.

Locator v3 payload SHA256:
b43091ef9885cc7b3c136a69ce702d0afcc49d7fd5e5a9c748d2a389ed0e7ea6
SNPE 2.10 compiler; 8-bit per-axis weights, 16-bit activations, 32-bit biases,
enhanced quantization, fresh HTP_V73_SM8550_8MB cache.

Validation:
- Three focused contract regression tests and original-checkpoint reference.
- Seven stock MCA/SNAP inputs, 120 executions each; negative cases stay empty.
- Native decoder coordinate/score agreement; minimum reference IoU 0.9707.
- Twelve public-video cases: three timestamps, portrait/landscape, tightly
  packed and explicitly repacked padded strides, 120 executions each.
  Portrait frames detect two persons through the unchanged 0.95 class gate.
- Temporary Locator-only bind mount: actual Gallery now produces nonempty
  results, executes ReID, enters PREVIEW after selection, and reports export
  completion. No installed DLC was overwritten or vendor runtime replaced.
  User feedback: boxes appear and save-as-copy works, but selecting a person
  expands the crop too much. Cropping is NOT fixed. Later structural records
  show fragmented tracks, not duplicate valid IDs within a frame. Determining
  association versus crop-generation failure requires a separate investigation.
  The user used another video; no frames/tensors from it were captured.
  Temporary bind mount removed, original device Locator SHA256 restored:
  39888e55d6245980bd77e6009c869d0cc294c7a0c6d9b0193aee00612993756a.

Evidence/backups: out/archived/UN1CA-Detection-Repair-20261004.
ReID remains unchanged and accuracy-unverified. Duplicate/fragmented tracks,
occlusions, different videos, thermal behavior and export visual quality
must not be inferred from this limited positive test.

Probes:
preprocess_probe.cpp calls Samsung's pinned RGB preprocessing ABI. It first
repacks rows because that raw ABI accepts no stride. This is not proof that
Gallery's YUV conversion handles all decoder layouts.
PreprocessInfo padding uses signed integers, verified against Samsung's
convert2xyxy integer-to-float conversion. The corrected isolated-v3 report
records portrait padding [75, 0]; all twelve 120-frame cases passed again.
diagnose_detection.py compares identical frames through host ONNX, Samsung
preprocessing, stock SNAP, and native decoding. It also checks the 0.95 gate.
trace_editor_detection.py/.js are optional process-scoped read-only probes;
Frida attach failed on this firmware, so no live tensors were captured and
the diagnostic server/forwarding were stopped. Do not treat these as tested
instrumentation. Library/process checks and explicit public-clip opt-in are
required before attachment. They do not alter inference results.
capture_live_results.py records only hashes, frame/box counts and callback
counts; it never writes media names, raw analysis contents or full log lines.

Source/install checks:
bash -n unica/mods/rezoss/customize.sh
python3 unica/mods/rezoss/fancam/experimental/test_install.py
git diff --check

Contract regression tests require the existing model-development venv:
/tmp/fancam-locator-sm8550-20261003/venv/bin/python \
  unica/mods/rezoss/fancam/locator/test_contract.py
