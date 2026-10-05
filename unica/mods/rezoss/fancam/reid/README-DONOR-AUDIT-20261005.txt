S26U donor-contract audit - 2026-10-05
====================================
Outcome: native FanCam stack matches the donor. Association failure remains.
No ROM payload, XML, customize.sh, installed app or inference library changed.
Only standalone diagnostics were pushed to /data/local/tmp and executed.
No editor restart, system bind mount or private media capture was required.

Evidence root:
out/archived/UN1CA-FanCam-Tracking-20261005/donor-contract-audit/
  full-comparison/comparison.json: live donor/device hashes, aliases, flags.
  editor-method-comparison.json: normalized decoded-class/method comparison.
  pose-gates/report.json: real native validator results on saved v19 tensors.
  pose-gates/native-disassembly.txt and native-symbols.json: static contracts.
Initial comparison and unnormalized app comparison are retained, not replaced.
The raw class comparison overstates changes due to R8 .source map IDs; use
editor-method-comparison.json, which excludes those non-executable IDs.

Live comparison
---------------
libauto-reframing-arm64-v8a.so:
  donor and installed SHA e8921a043f2050d69d11f906820a400054c835bebd0f743bff7a33405c42f835
libmediacontextanalyzer.so:
  donor and installed SHA bedfc253a4c577d6917f2a79412c1c98d7c43cf5b2101406de9e970e526fabd7
Video Editor default permissions and preinstalled-packages sysconfig match.
SEC_FLOATING_FEATURE_MMFW_SUPPORT_TARGET_TRACKING is TRUE in both.
VIDEO_CLIPPING_MODE differs: donor NPU,unifiedclipper,foundational_segmentation
versus installed NPU. This is a separate clipping feature; no demonstrated
causal link to selected-person track loss. Do not enable it as a guessed fix.
Locator, ReID and Detection aliases resolve to custom experimental DLCs.
Pose and Keyword still match donor DLCs; equality is NOT SM8550 compatibility.
The installed ReID is restored v2 (f9ce6a50...), not temporarily tested v19.

App qualification
-----------------
Installed Video Editor 7.5.01.27, donor 7.5.01.23. APKs and DEX differ.
Every bundled native library matches, including libmediatool.so.
Normalized disassembly: 13031 donor and 13032 installed classes; 50 classes
differ. Of 252 keyword-related classes, 13 differ. Keyword matches are broad
and do not imply all 13 implement tracking.
MediaContextAnalyzerAdapter, ReframeMapInfo, MCA binding/result parsers and
tracking-box structures have identical normalized decoded code.
ReframeFunction changes concern seek-time restoration, exit handling and
analytics; ReframeTimeline changes concern landscape UI timeline margins.
Those timeline margins are NOT video crop margins. No demonstrated change
in the native person-association implementation. Full app/resource/runtime
equivalence is not claimed, and no app downgrade is justified by this audit.

Native model-consumer contracts
------------------------------
Pinned native 87-point validators have confidence-only gates:
  verify_pose: at least 5 points with score strictly greater than 0.5.
  verify_face: at least 24 of 27 designated face points strictly above 0.5.
  face rows: 0,1,2,17,18,19,20,63..82.
These are NOT the complete production gates. Box-aware verify_pose also
counts confident points inside/outside an expanded detection rectangle.
is_clean_feature rejects invalid IDs, absent pose data, boundary problems,
failed box-aware pose verification, insufficient keypoint activation,
poor face/detection overlap, feature-time conflicts and occlusion/overlap.
Several thresholds are per-target parameters, not these basic validator
constants. Their actual Gallery runtime values are not captured here.

calculate_cosine_distance computes 1 - clamp(cosine, 0, 1), normalizing both
vectors internally. Signed fitted embeddings are not structurally rejected;
negative pairwise cosine saturates distance at 1. Donor embedding distribution
and identity calibration are unknown without executable donor inference.
Target::expired compares stored timestamps plus its supplied interval; do
not infer a fixed timeout or timestamp units from that helper alone.
Matching, filtering, memory and expiry symbols/disassembly are archived for
the next isolated association replay. No binary threshold patch is applied.

3.0..4.8-second evidence
-----------------------
Verified earlier v19 Gallery trial selected ID 2. Its subject track ends at
3.4 seconds. At 3.5 seconds the middle dancer remains detected at
[306,467,473,987] but raw ID is -1. Later IDs do not restore selected ID 2.
Source: gallery-v19-test/tracks-after-preview.json and hash verification.

New device probe calls the hash-pinned, stateless native verify_pose and
verify_face functions on 91 saved v19 isolated crop tensors plus 4 threshold
fixtures: 95/95 native results agree with the inspected reference.
At 3.0,3.5,4.0,4.5 seconds the middle dancer passes BOTH basic gates. The
left dancer fails the face gate at 4.0 seconds. These are saved isolated
outputs, NOT newly captured production Gallery poses or complete tracking.

Middle dancer's isolated first-frame template cosines:
  seconds   left      middle    right     own-versus-runner-up margin
  3.0       0.69549   0.92195   0.82042   0.10153
  3.5       0.70887   0.86993   0.84784   0.02210
  4.0       0.69296   0.89865   0.85426   0.04439
  4.5       0.71499   0.88359   0.82728   0.05631
Source: fitted-norm-v19c-evaluation/report.json. This is a template proxy,
not native temporal matching. A positive top-1 result does not establish
compatibility with Samsung's calibrated cost thresholds or pose matching.

Conclusion / next boundary
--------------------------
Evidence favors an association/model-consumer or production-preprocessing
mismatch over missing donor native logic. It does not prove which gate fails.
Next: replay full native matching with actual detection boxes, restored pose
coordinates and appearance memory at every analyzed frame around 3.0..4.8s.
Compare is_clean_feature decisions, box-aware pose/activation, combined cost
matrix and expiry/reacquisition, not just top-1 appearance or tensor RMSE.
Also compare actual Gallery YUV-derived tensors against isolated RGB crops
when reliable process-scoped capture is possible. Width720/stride768 was
proved at the decoder, not every later tracking boundary.
Only after ID 2 survives should crop fallback/keyframes be considered.
No v19 acceptance, full My FanCam repair or new ROM integration is claimed.

Verification (from repository root)
-----------------------------------
MLPY=/tmp/fancam-locator-sm8550-20261003/venv/bin/python
$MLPY unica/mods/rezoss/fancam/reid/test_donor_audit.py
python3 -m py_compile unica/mods/rezoss/fancam/reid/audit_donor_contract.py
python3 -m py_compile unica/mods/rezoss/fancam/reid/audit_pose_gates.py
python3 -m py_compile unica/mods/rezoss/fancam/reid/compare_decoded_editor.py
git diff --check
Native probe compiled with Android NDK -O2 -Wall -Wextra -Werror.
Audit tools refuse existing output paths to preserve evidence.
