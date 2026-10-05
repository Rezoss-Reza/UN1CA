Camera and Document Scan DLC compatibility pass - 2026-10-04
==========================================================
Evidence: out/archived/UN1CA-Camera-SNPE210-20261004/
SDK: /mnt/c/Qualcomm/AIStack/SNPE/2.10.40.4
Reported version: SNPE v2.10.40.230516212155_54203

Scope and result
----------------
The SDK is a conversion/compiler toolchain, not Samsung's original model
source or weights. Its converters accept trained ONNX/TFLite/etc. models;
it does not provide a generic newer-DLC-to-older-DLC downgrade.

audit_snpe210.py verified current donor hashes and complete path coverage
against the earlier modern-SDK portable-weight inventory. Every distinct
container was then opened in an isolated SNPE 2.10 reader subprocess.
124 donor paths, 116 unique DLCs: zero readable by SNPE 2.10.
Reader logs and input/output contracts are retained in donor-audit/.
The reused weight audit is tied to verified SHA256, not inferred filenames:
114 unique models have missing/stripped portable static tensors.
Only DocSegment retains trained portable weights. aiisp_dummy_model is an
untrained reshape and is not a usable replacement for camera processing.

Document Scan
-------------
DocDewarping, DocDeblur, ForSegment, ForInpaint, ICglobal/ICmask/IClocal,
ShadowExtractor/ShadowRemoval, TextReflectionRemoval, DogEarDetection,
ScannerFilter and LineDetection cannot be rebuilt from the available donor
containers using 2.10. Original trained sources or unstripped graphs are
needed. Missing weights cannot be recovered by changing cache metadata.

01-7_DocumentScan_DocSegment_v1.dlc is a different case: it retains weights,
but the 2.10 reader rejects its newer container. The earlier SDK 2.44/V73
numeric experiment is NOT a 2.10 port. A graph/operator/encoding translation
or original export is required; no such translation was completed here.
Native preprocessing, output classes and the uFxp16 versus S23U uFxp8
consumer contract still require validation even after a successful rebuild.

Current customize.sh correctly keeps target-firmware GPU/TFLite dewarping.
The donor ss_magnet and enhanced-document payloads remain unchanged; this
audit does NOT establish they work or fix their existing crash warning.

Other camera models
-------------------
The same reader/weight blockers affect portrait depth/bokeh/segmentation,
stereo, DVS, foundational segmentation, autofocus, AI tone mapping, night
AIISP, Single Take, and related donor models. See per-model graphs in the
report rather than applying a blanket rename or inference-runtime swap.
Camera .so libraries are compiled native consumers, not DLCs. The SNPE SDK
cannot convert those libraries; ABI and preprocessing/postprocessing need
separate exact-library checks. No native library was replaced or patched.

Completed isolated rebuild
--------------------------
humanpet-v2/UN1CA-humanpet-SM8550-SNPE210-experimental.dlc
SHA256: f055902476940bd6c3f25c87f9bd3353c34f31c806b1c40cd4170c87939f9179
Source ONNX SHA256:
4d5b76ed6a30bd13fa87995c2629ba8aa1e847403987a8d6ce71d70c93ffa376
This is our public pretrained YOLOv7-tiny replacement, NOT Samsung weights.
Input images float NHWC [1,320,320,3]; output [1,6300,29,1].
Class slots: dog/cat/human only; all remaining category slots are zero.
Its existing joint-score contract was preserved. Locator's repaired
conditional-score gate must not be assumed to apply to this consumer.
8-bit per-axis weights, 16-bit activations, 32-bit biases, enhanced
quantization, fresh HTP_V73_SM8550_8MB cache.

Ten public/blank samples passed the installed vendor SNPE DSP-only probe.
Relative RMSE <=0.02285 against saved ONNX, cosine >=0.99978.
Samsung native decoder class/count/coordinate checks passed unchanged.
Minimum float/DSP box IoU 0.95329, acceptance gate unchanged at 0.8.
Humans, sample dog and cats detected; horses/sudoku/blank remain empty.
The white-dog photo remains incorrectly classified as cat, as in the float
model. Faces, gestures and other Samsung classes are not implemented.
This was one execution per sample, NOT a sustained/thermal/session test.
The direct probe uses _Exit to bypass its standalone vendor atexit issue;
normal cleanup, Camera integration and stock Detection SNAP ABI are untested.
It is NOT a validated production Camera or MCA replacement. At the user's
explicit experimental request, ROM integration now replaces Detection.dlc
on dm3q only with the explicitly named HumanPetDetection experimental v1
payload. Other devices retain the donor; Locator/ReID stay unchanged.
No direct Camera consumer paths or Document Scan files are redirected.

Safety and reproducibility
--------------------------
The conversion pass changed no installed system/runtime/Camera files; only
isolated /data/local/tmp inference was used, with no private camera frames.
The later requested ROM integration changed customize.sh and source install
payloads only, not out/fw or out/target. Stable Detection.dlc needs no XML
rename. Source backups and the original donor Detection payload/alias are
in rom-integration-backup/. Diagnostic-script backups are in source-backup/.
humanpet/ is an abandoned first attempt made with the wrong host Python
(missing numpy); humanpet-v2/ contains the completed build and tests.
Model/checkpoint provenance and license limitations remain as documented
in README.txt. Review upstream GPLv3/model/dataset redistribution terms.

Run the rebuild helper with the existing ML venv, not system Python:
/tmp/fancam-locator-sm8550-20261003/venv/bin/python \
  unica/mods/rezoss/fancam/experimental/rebuild_snpe210.py \
  --sdk /mnt/c/Qualcomm/AIStack/SNPE/2.10.40.4 \
  --python36 /tmp/fancam-snpe210-python/bin/python \
  --source /tmp/s26u-dlc-audit-20261003/humanpet-v1 \
  --destination /tmp/unique-humanpet-snpe210-output \
  --model humanpet --enhanced-quant \
  --runtime-libs /tmp/doc-dewarp-sm8550-sdk/deps/usr/lib/x86_64-linux-gnu

Verification:
python3 -m py_compile unica/mods/rezoss/fancam/dlc_audit/audit_snpe210.py \
  unica/mods/rezoss/fancam/dlc_audit/verify_humanpet.py \
  unica/mods/rezoss/fancam/experimental/rebuild_snpe210.py \
  unica/mods/rezoss/fancam/experimental/test_snpe210_device.py
python3 unica/mods/rezoss/fancam/experimental/test_install.py
git diff --check
