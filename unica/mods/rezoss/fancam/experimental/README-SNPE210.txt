SNPE 2.10 rebuild experiment - 2026-10-04
=======================================
Input installer:
out/archived/qualcomm_neural_processing_sdk.2.10.40.4.Windows-AnyCPU.exe
Qualcomm QIK extraction includes Linux tools despite the Windows wrapper.
Extracted SDK: C:\Qualcomm\AIStack\SNPE\2.10.40.4
Reported compiler: SNPE v2.10.40.230516212155_54203
Device runtime: stock S23U SNPE 2.10.8.230606145717_54048
No vendor runtime/library was changed. A reversible Locator-only bind mount
was used for live Gallery testing; no installed system DLC was overwritten.
ROM source now contains conditional-score Locator (v3 filename) and ReID v11
(v2 filename), both explicitly labeled SM8550/SNPE210 experimental payloads.
ReID was selected by explicit user opt-in despite failed validation gates.
ROM source also installs Human/Pet Detection for dm3q by experimental opt-in:
07-03_Video_HumanPetDetection_UN1CA_Experimental_v1_SM8550-SNPE210.dlc
Detection.dlc points to this replacement; other devices retain the donor.
SHA256 f055902476940bd6c3f25c87f9bd3353c34f31c806b1c40cd4170c87939f9179.
Ten direct stock-SNPE/native-decoder samples passed; stock Detection SNAP/MCA
and Camera integration are untested. Only dog/cat/human classes are present;
the known white-dog-as-cat error remains. No camera library or Document Scan
DLC conversion is installed. See ../dlc_audit/README-SNPE210-CAMERA.txt.

Results
-------
2026-10-04 detection-contract repair:
Locator SHA256 b43091ef9885cc7b3c136a69ce702d0afcc49d7fd5e5a9c748d2a389ed0e7ea6.
Field 7 now carries conditional person probability, while field 4 retains
objectness. The previous joint-confidence export was incompatible with the
production VideoDetection score gate 0.95 (0x4a820/0x4a860 in the pinned auto-
reframing library). No runtime threshold or negative-image gate was relaxed.
All seven SNAP samples passed 120 executions each; native/reference minimum
IoU was 0.9707. Twelve public-video cases cover three timestamps, portrait/
landscape, packed/repacked-stride buffers, 120 executions each. Samsung input
tensors passed ONNX/SNAP comparisons and native coordinate/score gates.
Public-video portrait cases each detect two people above the unchanged 0.95
gate. Stable host NMS tie sorting now matches Samsung's stable list sorting.
Live Gallery: 25/25 quick frames have two boxes; 110/110 full frames have 1..5
boxes; ReID executes; selection enters PREVIEW; export-completed/saved-video
callbacks observed. The user confirmed save-as-copy but reported excessive
selected-person crop expansion on another video. A later 150-frame record
had three boxes in every frame, no duplicate valid IDs within frames, and
fragmented IDs across the sequence. Selected ID 2 survived only 32 frames.
That does not establish whether association or downstream crop generation
caused the expansion; cropping remains unresolved. ReID accuracy is still
unqualified. The temporary mount was removed and the installed Locator hash
was restored to 39888e55d6245980bd77e6009c869d0cc294c7a0c6d9b0193aee00612993756a.
Evidence and backups: out/archived/UN1CA-Detection-Repair-20261004.

Earlier joint-score Locator experiment (superseded):
Locator v3: conversion, 8-bit weights / 16-bit activations / per-axis enhanced
quantization and HTP_V73_SM8550_8MB cache preparation passed. Stock SNPE opens
the cached file and its byte buffer. DSP-only runtime-list inference passed
seven public inputs with relative RMSE 0.0146..0.0229 against saved float output.
Output shape remains [1,6300,29,1]. Stock MCA/SNAP passed seven samples with
120 frames each (840 executions), dispose and destructor. Samsung's native
decoder returned the same detections as the float reference; minimum box IoU
was 0.9532. Blank, horses and image3 returned zero people. Gallery untested.
No per-layer DSP profile captured.

ReID v11 (active EXPERIMENTAL ROM source): 8-bit per-axis weights, 16-bit
activations, 32-bit biases, minmax quantization, fresh V73 cache.
SHA256: f9ce6a502fa9ad1b33b5d20bfdd33159c9040613a5f737cc1f18ee8313788c2b
Graph adaptation: four HardSigmoid lowerings, eleven constant reciprocal
multiplications, four dynamic reciprocal-power multiplications, 96 identical
shared convolution parameter copies, and rank-four final pose concatenation.
All ten adapted float comparisons passed; max absolute drift 2.575e-5.
Both stock direct SNPE and stock MCA/SNAP executed all ten samples. SNAP ran
120 frames per sample (1200 executions) and closed/destructed each session.
Appearance cosines 0.98396..0.99578 passed. Full pose relative RMSE
0.16509..0.29838 FAILED the existing 0.20 gate on nine samples. A native test
also FAILED a confidence-value comparison on bus-0-brightness (difference 1).
Reliable body positions in host decoding were close, but that does not waive
either failed gate or establish a usable Samsung tracking pipeline.
This ReID is ACCURACY-UNVERIFIED. No acceptance thresholds were loosened.

Further trials: v12 enhanced quantization and v14 adjusted weights were worse;
v13 16-bit weights produced no quantized file despite zero exit; v15/v16
Newton reciprocal refinement failed V73 cache preparation at q::mul_op.
The v9/v10 final rank-changing output corrupted some pose rows on DSP; v11
rank-four concatenation avoided that observed corruption.

Archives (relative to repo root):
out/archived/UN1CA-SNPE210-Rebuild-Experiments-20261004/Locator-SNAP-native-passed
out/archived/UN1CA-SNPE210-Rebuild-Experiments-20261004/ReID-v11-accuracy-rejected
Rollback source/script backup:
out/archived/UN1CA-SNPE210-Rebuild-Experiments-20261004/rom-source-backup-20261004-1000

ReID v4: conversion, 8-bit weights / 8-bit activations / per-tensor minmax
quantization and V73 cache preparation passed. Stock SNPE opens file/buffer
and DSP-only inference produces both expected output shapes. QUALITY REJECTED:
feature cosines 0.8525..0.9565 (required >=0.98); pose relative RMSE up to 1.044.
Do not install this ReID as a replacement. On bus-0, stock float CPU inference
matches saved ONNX reference within 1.91e-5 for features and 1.89e-5 for pose.
This isolates the observed quality regression to the quantized execution path
on that sample, not the exact HardSigmoid lowering.

Adaptation: four HardSigmoid operators lowered to Mul/Add/Clip. ONNX Runtime
comparison across all ten person inputs passed; max absolute drift 1.67e-6.
ReID v2/v5 per-axis quantizer emits lockWeakPtr/Tensor pointer invalid errors
and produces no DLC despite returning exit code 0. The build script detects
this false success. Cloning shared weights in v8 fixed that observed failure.
ReID v3 with 16-bit activations fails V73 preparation at q::Div.exe. Dynamic
8-bit overrides applied, but remaining constant 16-bit divisions still failed;
the reciprocal adaptations above avoid those unsupported division operators.

Probe limitations
-----------------
The SDK Windows package has no Android snpe-net-run binary. A new minimal C API
probe dynamically loads only /vendor/lib64/libSNPE.so and selects DSP as the
sole runtime (or explicit CPU for the float control). It does not load SNPE 2.44.
The first standalone run crashed in vendor SNPE atexit teardown. The probe
now flushes results and uses _Exit, so results do not establish cleanup or
long-running session stability. This is a diagnostic, not a production adapter.
Test artifacts remain under /data/local/tmp/unica-*. SNAP diagnostics use su
to access the installed service; no system/vendor files, mounts or properties
were changed. The first incomplete MCA probe omitted tensor names and crashed
the SNAP service through a vector bounds exception; supplying input/output
names and CoreType=0 corrected the probe setup. CoreType=1 routed to unavailable
EDEN in this setup. These initial probe failures are not Gallery observations.
The corrected SNAP tests passed inference, dispose and destructor but use
_Exit to avoid standalone process-shutdown problems; not a longevity proof.

Reproduce from repo root (Python 3.10 orchestration environment)
-------------------------------------------------------------
py=/tmp/fancam-locator-sm8550-20261003/venv/bin/python
sdk=/mnt/c/Qualcomm/AIStack/SNPE/2.10.40.4
py36=/tmp/fancam-snpe210-python/bin/python
libs=/tmp/doc-dewarp-sm8550-sdk/deps/usr/lib/x86_64-linux-gnu
proto=unica/mods/rezoss/fancam/experimental
$py $proto/rebuild_snpe210.py --sdk "$sdk" --python36 "$py36" --runtime-libs "$libs" --source /tmp/fancam-locator-sm8550-20261003/model --destination /tmp/fancam-snpe210-locator-new --model locator
$py $proto/rebuild_snpe210.py --sdk "$sdk" --python36 "$py36" --runtime-libs "$libs" --source /tmp/fancam-reid-sm8550-20261003/reid-human-v6 --destination /tmp/fancam-snpe210-reid-new --model reid --lower-dynamic-div --lower-constant-div --unshare-conv-weights --pose-rank4-concat
$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang++ -std=c++17 -O2 -Wall -Wextra -Werror -static-libstdc++ -I"$sdk/include/SNPE" "$proto/snpe210_inference_probe.cpp" -ldl -o /tmp/fancam-snpe210-inference-probe
$py $proto/test_snpe210_device.py --model-dir /tmp/fancam-snpe210-locator-new --model locator --probe /tmp/fancam-snpe210-inference-probe
$py $proto/test_snpe210_device.py --model-dir /tmp/fancam-snpe210-reid-new --model reid --probe /tmp/fancam-snpe210-inference-probe
The last command is expected to FAIL full pose accuracy gates for ReID v11.
$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang++ -std=c++17 -O2 -Wall -Wextra -Werror -static-libstdc++ "$proto/snap_mca_probe.cpp" -ldl -o /tmp/fancam-snpe210-snap-mca-probe
$py $proto/test_snpe210_device.py --model-dir /tmp/fancam-snpe210-locator-new --model locator --probe /tmp/fancam-snpe210-snap-mca-probe --snap --frames 120
$py $proto/test_snpe210_device.py --model-dir /tmp/fancam-snpe210-reid-new --model reid --probe /tmp/fancam-snpe210-snap-mca-probe --snap --frames 120
ReID SNAP inference succeeds, then its reference check intentionally fails.
Every build/device test requires a fresh directory.

Python 3.6 tools: conda-forge Python 3.6.15; numpy1.19.5, onnx1.11.0,
protobuf3.19.6, scipy1.5.4, PyYAML6.0, packaging21.3, pandas1.1.5.
The orchestration/float comparisons use the existing Torch/ONNX Runtime venv.
