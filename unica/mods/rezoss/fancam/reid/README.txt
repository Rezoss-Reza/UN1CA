UN1CA ReID Human Experimental v1 - SM8550 / S23 Ultra
==================================================

This is a learned replacement experiment, not a conversion of recovered S26U
weights and not a completed My FanCam port. The donor ReID lacks static weights.
The tested DLC is now wired into customize.sh for dm3q through ../experimental/
as Reid.dlc. Native libraries, permissions and feature selection are unchanged;
the source integration has not been rebuilt, flashed or verified in Gallery.

Models and provenance
---------------------
Appearance: OSNet x0.25 trained on MSMT17, using the first 128 learned final-head
activations from its 512D feature. No random weights or synthetic feature head.
https://github.com/KaiyangZhou/deep-person-reid
Source commit: f8cd150fdf77e8d9e1ed143b7f308c2c609ded50
Weights: https://huggingface.co/kaiyangzhou/osnet
Revision: a5c5cc037c24235cda3b21085b93ad77c9616224
Weight SHA256: cf55163d78fc44c62c82f85ab62d39f10438679b5abe8c698ae08cfa84aa6e18
OSNet code is MIT licensed; the archive contains the upstream license.

Pose: official DWPose-t / RTMPose whole-body model (133 learned landmarks).
https://github.com/open-mmlab/mmpose
https://github.com/Tau-J/rtmlib
https://download.openmmlab.com/mmpose/v1/projects/rtmposev1/onnx_sdk/rtmpose-t_simcc-ucoco_dw-ucoco_270e-256x192-dcf277bf_20230728.zip
ONNX SHA256: 213aae5fd54473fec38a2621f9d658a7dbf6808653ac796ad3d0fa6f23c2a2b8
Review upstream model and training-dataset terms before distributing this DLC;
code licenses alone do not establish unrestricted checkpoint/dataset rights.

Contract and tests
------------------
Input images: float RGB NHWC [1,320,320,3], range 0..1. Our sample pipeline
letterboxes person crops with RGB 114 padding. Exact Samsung live preprocessing
still needs integration verification.
output_1_feature: [1,128,1,1], raw learned appearance activations.
output_0_pose: [1,174,640,1], 87 X distributions then 87 Y distributions.
Body/hands/feet are mapped using Samsung's recovered skeleton. Neck and pelvis
derive from learned shoulder/hip predictions; facial assignments are provisional.

QAIRT 2.44.0.260225; 8-bit weights, 16-bit activations, 32-bit biases,
per-axis weights, standard min/max calibration; HTP_V73_SM8550_8MB cache.
Rejected earlier enhanced-quantizer and 16-bit-weight trials remain in /tmp.

Passed on the connected SM8550: ten public-image person crops, all 683 layers on
DSP; reported sample average 17223 us. Float-reference feature cosine >=0.9825.
Samsung PoseRTMCC decoder and cosine-distance function passed native comparisons
for all ten samples / 100 feature pairs. All five brightness pairs were nearer
than different sample identities. See device-results.json and native-results.json.
These are sample inference measurements, not end-to-end video performance.

Limitations
-----------
One difficult person crop has zero confident body points even in the float model.
The selected 128D feature subset is not retrained or calibrated for Samsung's
tracking thresholds. No temporal, occlusion, viewpoint, multi-person video,
SNAP/MCA, Gallery preview or export validation has passed. Facial landmark
semantics and live preprocessing remain uncertain. Do not rename this over the
donor DLC and claim complete My FanCam support.
SDK DLC ZIP entries may have zero CRC fields; preserve SDK bytes and use SHA256,
SDK inspection and actual device inference rather than generic ZIP CRC repair.

Reproduction (run from repository root)
--------------------------------------
python3 unica/mods/rezoss/fancam/reid/export_reid.py --help
python3 unica/mods/rezoss/fancam/dlc_audit/run_candidate.py --help
python3 unica/mods/rezoss/fancam/reid/verify_native.py --help
Use the experiment's Python environment with torch, onnx, onnxruntime, numpy,
opencv, pyelftools and capstone, plus QAIRT and the pinned upstream models.
Each export/build must use a fresh folder. Device files stay under
/data/local/tmp/unica-dlc-*. Use /mnt/c/platform-tools/adb.exe.

Verify the preserved artifact:
cd out/archived/UN1CA-ReID-Human-SM8550-Experimental-v1
sha256sum -c SHA256SUMS

Inspect the exact Samsung library before compiling/running the native probe:
python3 unica/mods/rezoss/fancam/reid/inspect_native.py --help
Native library SHA256:
e8921a043f2050d69d11f906820a400054c835bebd0f743bff7a33405c42f835
Probe build:
$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang++ -std=c++17 -O2 -Wall -Wextra -Werror -static-libstdc++ unica/mods/rezoss/fancam/reid/native_reid_probe.cpp -ldl -o /tmp/native_reid_probe
